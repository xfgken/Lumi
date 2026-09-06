// Hysteria2 会话封装：配置解析、官方 core 连接、TUN 转发编排
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

// ---- 代理模式（与 Kotlin ProxyMode 一致）----
const (
	modeGlobal = 0
	modeRule   = 1
	modeDirect = 2
)

var proxyMode atomic.Int32

func setProxyMode(m int) {
	proxyMode.Store(int32(m))
	logLine("I", fmt.Sprintf("代理模式已切换: %d", m))
}

// ---- 从 Kotlin 传入的 JSON 配置 ----
type coreConfig struct {
	Server     string `json:"server"`
	Port       int    `json:"port"`
	Password   string `json:"password"`
	SNI        string `json:"sni"`
	Insecure   bool   `json:"insecure"`
	Obfs       struct {
		Type     string `json:"type"`
		Password string `json:"password"`
	} `json:"obfs"`
	Bandwidth struct {
		Up   uint64 `json:"up"`
		Down uint64 `json:"down"`
	} `json:"bandwidth"`
	Mode        int  `json:"mode"`
	CnDirect    bool `json:"cnDirect"`
	AbroadProxy bool `json:"abroadProxy"`
}

func parseConfig(raw []byte) (*coreConfig, error) {
	var cfg coreConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, err
	}
	if cfg.Server == "" || cfg.Port <= 0 || cfg.Port > 65535 {
		return nil, fmt.Errorf("服务器或端口无效")
	}
	// auth 允许为空（服务端未启用认证），交由服务端决定是否拒绝
	if cfg.Mode >= modeGlobal && cfg.Mode <= modeDirect {
		proxyMode.Store(int32(cfg.Mode))
	}
	// 规则策略（默认中国直连/国外代理）
	cnDirect.Store(cfg.CnDirect)
	abroadProxy.Store(cfg.AbroadProxy)
	return &cfg, nil
}

func (c *coreConfig) serverAddr() string {
	return net.JoinHostPort(c.Server, fmt.Sprintf("%d", c.Port))
}

// ---- protect 后的 socket 控制 ----
func protectControl(network, address string, c syscall.RawConn) error {
	var err error
	cerr := c.Control(func(fd uintptr) {
		protectFdGo(int(fd))
	})
	if cerr != nil {
		err = cerr
	}
	return err
}

// protectFdGo：调用 C 层（main.go preamble 提供 h2aProtectFd -> Java protect）
func protectFdGo(fd int) {
	if fd <= 0 {
		return
	}
	cgoProtectFd(fd)
}

// 自定义 ConnFactory：创建 UDP socket 并回调 Java protect
type protectConnFactory struct{}

func (f *protectConnFactory) New(addr net.Addr) (net.PacketConn, error) {
	lc := net.ListenConfig{Control: protectControl}
	return lc.ListenPacket(context.Background(), "udp", ":0")
}

// ---- 会话 ----
type Hy2Session struct {
	cfg      *coreConfig
	cli      client.Client
	handInfo *client.HandshakeInfo

	mu        sync.Mutex
	tunActive bool
	cancel    context.CancelFunc

	downBytes atomic.Int64
	upBytes   atomic.Int64
	conns     atomic.Int32
	startAt   atomic.Int64
}

func newHy2Session(cfg *coreConfig) (*Hy2Session, error) {
	// 解析服务器（此时 VPN 尚未接管流量，无环路问题）
	// IPv4 优先：多数移动网络无 IPv6 出口，若域名同时有 A/AAAA 记录，
	// ResolveUDPAddr 可能返回 IPv6 造成 UDP 黑洞（握手超时）。
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	host, portStr, err := net.SplitHostPort(cfg.serverAddr())
	if err != nil {
		return nil, fmt.Errorf("服务器地址格式错误: %w", err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("服务器端口错误: %w", err)
	}
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip4", host)
	if len(ips) == 0 {
		ips, err = net.DefaultResolver.LookupIP(ctx, "ip", host)
	}
	if err != nil || len(ips) == 0 {
		if err == nil {
			err = fmt.Errorf("无可用地址")
		}
		return nil, fmt.Errorf("解析服务器地址失败: %w", err)
	}
	udpAddr := &net.UDPAddr{IP: ips[0], Port: port}

	sni := cfg.SNI
	if sni == "" {
		sni = cfg.Server
	}

	cli, hand, err := client.NewClient(&client.Config{
		ConnFactory: &protectConnFactory{},
		ServerAddr:  udpAddr,
		Auth:        cfg.Password,
		TLSConfig: client.TLSConfig{
			ServerName:         sni,
			InsecureSkipVerify: cfg.Insecure,
		},
		QUICConfig: client.QUICConfig{
			InitialStreamReceiveWindow:     8 * 1024 * 1024,
			MaxStreamReceiveWindow:         8 * 1024 * 1024,
			InitialConnectionReceiveWindow: 16 * 1024 * 1024,
			MaxConnectionReceiveWindow:     16 * 1024 * 1024,
			MaxIdleTimeout:                 60 * time.Second,
			KeepAlivePeriod:                15 * time.Second,
		},
		BandwidthConfig: client.BandwidthConfig{
			MaxTx: cfg.Bandwidth.Up,
			MaxRx: cfg.Bandwidth.Down,
		},
	})
	if err != nil {
		return nil, err
	}

	sess := &Hy2Session{
		cfg:      cfg,
		cli:      cli,
		handInfo: hand,
	}
	sess.startAt.Store(time.Now().Unix())
	_ = ctx

	logLine("I", fmt.Sprintf(
		"HY2 握手成功 udp=%v tx=%d rx=%d", hand.UDPEnabled, hand.Tx, hand.ServerAddr))
	return sess, nil
}

// attachTun：接管 TUN fd，启动 IP 栈转发（tun.go 实现）
func (s *Hy2Session) attachTun(fd int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tunActive {
		return fmt.Errorf("TUN 已激活")
	}
	cancel, err := s.startTunLoop(fd)
	if err != nil {
		return err
	}
	s.cancel = cancel
	s.tunActive = true
	return nil
}

// udpEnabled：服务器是否支持 UDP 转发（DNS/QUIC 等依赖）
func (s *Hy2Session) udpEnabled() bool {
	return s.handInfo != nil && s.handInfo.UDPEnabled
}

func (s *Hy2Session) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	_ = s.cli.Close()
}

func (s *Hy2Session) statsJSON() string {
	return fmt.Sprintf(
		`{"down":%d,"up":%d,"conn":%d,"time":%d}`,
		s.downBytes.Load(), s.upBytes.Load(), s.conns.Load(),
		time.Now().Unix()-s.startAt.Load(),
	)
}

// dialProxyTCP：经 HY2 访问目标（host:port）
func (s *Hy2Session) dialProxyTCP(ctx context.Context, hostPort string) (net.Conn, error) {
	conn, err := s.cli.TCP(hostPort)
	if err != nil {
		return nil, err
	}
	s.conns.Add(1)
	return &countingConn{Conn: conn, sess: s, write: &s.upBytes, read: &s.downBytes, onClose: func() { s.conns.Add(-1) }}, nil
}

// dialDirectTCP：直连（socket 需 protect 绕开 TUN）
func dialDirectTCP(ctx context.Context, hostPort string) (net.Conn, error) {
	d := net.Dialer{Control: protectControl, Timeout: 15 * time.Second}
	return d.DialContext(ctx, "tcp", hostPort)
}

// hyUDP：经 HY2 的 UDP session（返回后由调用方 Send/Receive）
func (s *Hy2Session) hyUDP() (client.HyUDPConn, error) {
	return s.cli.UDP()
}

// ---- 流量计数包装 ----
type countingConn struct {
	net.Conn
	sess    *Hy2Session
	read    *atomic.Int64
	write   *atomic.Int64
	onClose func()
	once    sync.Once
}

func (c *countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.read.Add(int64(n))
		c.sess.downBytes.Add(int64(n))
	}
	return n, err
}

func (c *countingConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		c.write.Add(int64(n))
		c.sess.upBytes.Add(int64(n))
	}
	return n, err
}

func (c *countingConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.onClose)
	return err
}