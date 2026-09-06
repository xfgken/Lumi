// TUN 转发层：gvisor netstack（tun2socks v2.7.0 core）+ 官方 HY2 核心
//
// 数据流：
//   Android VpnService TUN fd
//     -> iobased LinkEndpoint
//     -> gvisor netstack (TCP/UDP 终止)
//     -> TransportHandler (本文件)
//     -> 分流：DIRECT(protect 直连) / PROXY(官方 hysteria2)
//   DNS(53) 特殊处理：按 QNAME 国别路由，防止泄漏
package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
	"golang.org/x/sys/unix"
)

const (
	tunMTU        = 1400
	udpTimeout    = 2 * time.Minute
	dnsTimeout    = 4 * time.Second
	chinaDNS      = "223.5.5.5:53" // 阿里 DNS（直连解析）
	remoteDNS     = "8.8.8.8:53"   // 远端解析（经 HY2）
	directDNSv6   = "2400:3200::1"
	remoteDNSIPv6 = "2001:4860:4860::8888"
)

// startTunLoop：接管 fd，启动 gvisor 栈转发（由 attachTun 持锁调用，不得自行加锁）
func (s *Hy2Session) startTunLoop(fd int) (context.CancelFunc, error) {
	// dup fd 防止 Java 侧意外 close 影响
	dup, err := dupFd(fd)
	if err != nil {
		return nil, fmt.Errorf("dup tun fd: %w", err)
	}
	file := os.NewFile(uintptr(dup), "h2a-tun")

	ep, err := iobased.New(file, tunMTU, 0)
	if err != nil {
		_ = file.Close()
		return nil, fmt.Errorf("create link endpoint: %w", err)
	}

	h := &stackHandler{sess: s}
	_, err = core.CreateStack(&core.Config{
		LinkEndpoint:     ep,
		TransportHandler: h,
	})
	if err != nil {
		_ = file.Close()
		return nil, fmt.Errorf("create netstack: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	// 监听关闭
	go func() {
		<-ctx.Done()
		_ = file.Close()
	}()
	logLine("I", "gvisor netstack 已启动 (MTU="+strconv.Itoa(tunMTU)+")")
	return cancel, nil
}

// ---- TransportHandler ----

type stackHandler struct {
	sess *Hy2Session
}

func (h *stackHandler) HandleTCP(conn adapter.TCPConn) {
	go h.handleTCP(conn)
}

func (h *stackHandler) HandleUDP(conn adapter.UDPConn) {
	go h.handleUDP(conn)
}

// dupFd：复制 fd
func dupFd(fd int) (int, error) {
	return unix.Dup(fd)
}

var (
	cnDirect    atomicBool
	abroadProxy atomicBool
)

type atomicBool struct {
	v int32
}

func (b *atomicBool) Load() bool { return b.v == 1 }
func (b *atomicBool) Store(x bool) {
	if x {
		b.v = 1
	} else {
		b.v = 0
	}
}

func init() {
	cnDirect.Store(true)
	abroadProxy.Store(true)
}

func (h *stackHandler) handleTCP(conn adapter.TCPConn) {
	defer conn.Close()
	id := conn.ID()
	dst := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprintf("%d", id.LocalPort))

	route, reason := h.routeDecision(id.LocalAddress.String(), "")
	logLine("D", "TCP "+dst+" -> "+reason)

	var (
		up   net.Conn
		err  error
	)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	switch route {
	case outDirect:
		up, err = dialDirectTCP(ctx, dst)
	default:
		up, err = h.sess.dialProxyTCP(ctx, dst)
	}
	if err != nil {
		logLine("W", "TCP dial "+dst+" 失败: "+err.Error())
		return
	}
	defer up.Close()

	// 双向转发
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(up, conn)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, up)
		done <- struct{}{}
	}()
	<-done
}

func (h *stackHandler) handleUDP(conn adapter.UDPConn) {
	defer conn.Close()
	id := conn.ID()
	dst := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprintf("%d", id.LocalPort))

	// DNS 特判
	if id.LocalPort == 53 {
		h.handleDNS(conn)
		return
	}

	route, reason := h.routeDecision(id.LocalAddress.String(), "")
	logLine("D", "UDP "+dst+" -> "+reason)

	switch route {
	case outDirect:
		h.udpDirect(conn, dst)
	default:
		h.udpProxy(conn, dst)
	}
}

// ---- DIRECT UDP：直连 socket（protect） ----
func (h *stackHandler) udpDirect(conn adapter.UDPConn, dst string) {
	raddr, err := net.ResolveUDPAddr("udp", dst)
	if err != nil {
		return
	}
	d := net.Dialer{Control: protectControl}
	pc, err := d.DialContext(context.Background(), "udp", dst)
	if err != nil {
		logLine("W", "UDP dial "+dst+" 失败: "+err.Error())
		return
	}
	defer pc.Close()
	_ = raddr

	// 读客户端 -> 直连
	go func() {
		buf := make([]byte, 64*1024)
		for {
			n, addr, err := conn.ReadFrom(buf)
			if err != nil {
				return
			}
			if addr == nil {
				continue
			}
			if _, err := pc.Write(buf[:n]); err != nil {
				return
			}
		}
	}()
	// 直连响应 -> 客户端
	buf := make([]byte, 64*1024)
	for {
		n, err := pc.Read(buf)
		if err != nil {
			return
		}
		// 源地址即发起会话的客户端
		if _, err := conn.WriteTo(buf[:n], conn.RemoteAddr()); err != nil {
			return
		}
	}
}

// ---- PROXY UDP：每客户端会话一条 HyUDPConn ----
func (h *stackHandler) udpProxy(conn adapter.UDPConn, dst string) {
	// 仅处理该客户端会话：持续读客户端数据，经 hy2 发往目标
	udpConn, err := h.sess.hyUDP()
	if err != nil {
		logLine("W", "HY2 UDP 会话失败: "+err.Error())
		return
	}
	defer udpConn.Close()

	// 上行：客户端 -> hy2 -> 目标
	go func() {
		buf := make([]byte, 64*1024)
		for {
			n, _, err := conn.ReadFrom(buf)
			if err != nil {
				_ = udpConn.Close()
				return
			}
			if err := udpConn.Send(buf[:n], dst); err != nil {
				_ = udpConn.Close()
				return
			}
		}
	}()
	// 下行：hy2 响应 -> 客户端
	buf := make([]byte, 64*1024)
	for {
		data, _, err := udpConn.Receive()
		if err != nil {
			return
		}
		n := copy(buf, data)
		if _, err := conn.WriteTo(buf[:n], conn.RemoteAddr()); err != nil {
			return
		}
	}
}

// ---- DNS 分流（防泄漏） ----
func (h *stackHandler) handleDNS(conn adapter.UDPConn) {
	clientAddr := conn.RemoteAddr()
	buf := make([]byte, 64*1024)
	for {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			return
		}
		q := buf[:n]
		qname := dnsQName(q)

		// 决定 DNS 出口
		var (
			resp []byte
			rerr error
		)
		if qname != "" && !rules.isCnDomain(qname) && h.sess.udpEnabled() {
			resp, rerr = h.remoteResolve(q)
		} else {
			resp, rerr = h.directResolve(q)
		}
		if rerr != nil {
			logLine("W", "DNS 解析失败 "+qname+": "+rerr.Error())
			continue
		}
		if resp == nil {
			continue
		}
		_, _ = conn.WriteTo(resp, clientAddr)
	}
}

// 直连 DNS（protect 绕开 TUN）
func (h *stackHandler) directResolve(q []byte) ([]byte, error) {
	d := net.Dialer{Control: protectControl, Timeout: dnsTimeout}
	pc, err := d.Dial("udp", chinaDNS)
	if err != nil {
		return nil, err
	}
	defer pc.Close()
	_ = pc.SetDeadline(time.Now().Add(dnsTimeout))
	if _, err := pc.Write(q); err != nil {
		return nil, err
	}
	resp := make([]byte, 4096)
	n, err := pc.Read(resp)
	if err != nil {
		return nil, err
	}
	return resp[:n], nil
}

// 远端 DNS（经 HY2，屏蔽本地运营商嗅探）
func (h *stackHandler) remoteResolve(q []byte) ([]byte, error) {
	uc, err := h.sess.hyUDP()
	if err != nil {
		return nil, err
	}
	defer uc.Close()

	done := make(chan struct {
		data []byte
		err  error
	}, 1)
	go func() {
		data, _, rerr := uc.Receive()
		if rerr != nil {
			done <- struct {
				data []byte
				err  error
			}{nil, rerr}
			return
		}
		cp := make([]byte, len(data))
		copy(cp, data)
		// 返回数据即本查询响应（会话独占）
		done <- struct {
			data []byte
			err  error
		}{cp, nil}
	}()
	if err := uc.Send(q, remoteDNS); err != nil {
		return nil, err
	}
	select {
	case r := <-done:
		return r.data, r.err
	case <-time.After(dnsTimeout):
		return nil, fmt.Errorf("timeout")
	}
}

// ---- 分流决策 ----
func (h *stackHandler) routeDecision(ipStr, _ string) (int, string) {
	mode := int(proxyMode.Load())
	switch mode {
	case modeDirect:
		return outDirect, "DIRECT(模式:直连)"
	case modeGlobal:
		return outProxy, "PROXY(模式:全局)"
	}
	// RULE：GeoIP
	ip := net.ParseIP(ipStr)
	if rules.isCnIP(ip) {
		if cnDirect.Load() {
			return outDirect, "DIRECT(中国IP)"
		}
		return outProxy, "PROXY(中国IP-用户设代理)"
	}
	if abroadProxy.Load() {
		return outProxy, "PROXY(国外IP)"
	}
	return outDirect, "DIRECT(国外IP-用户设直连)"
}

// ---- DNS QNAME 嗅探（首个问题名，最小实现） ----
func dnsQName(pkt []byte) string {
	if len(pkt) < 12 {
		return ""
	}
	// 跳过 Header(12B)，解析 Questions
	pos := 12
	var name []byte
	for pos < len(pkt) {
		l := int(pkt[pos])
		if l == 0 {
			break
		}
		if l&0xC0 == 0xC0 {
			// 压缩指针，结束
			pos += 2
			name = append(name, '.')
			break
		}
		pos++
		if pos+l > len(pkt) {
			return ""
		}
		if len(name) > 0 {
			name = append(name, '.')
		}
		name = append(name, pkt[pos:pos+l]...)
		pos += l
	}
	return string(name)
}

// 保留 IPv6 DNS 常量引用（IPv6 会话场景预留）
var _ = directDNSv6
var _ = remoteDNSIPv6