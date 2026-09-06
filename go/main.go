// H2A Go 核心：官方 apernet/hysteria core/v2 客户端绑定
//
// 构建方式（详见 build_android.sh）：
//   CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
//   CC="zig cc -target aarch64-linux-android --sysroot=<ndk-sysroot>" \
//   go build -buildmode=c-shared -o libh2a.so
//
// 导出符号（cgo //export，供 jni_glue.c 调用）：
//   long long GoConnect(char* json, int len);
//   void      GoStop(long long sid);
//   int       GoSetTunFd(long long sid, int fd);
//   char*     GoGetStats(long long sid);   // caller free()
//   char*     GoGetLogs(void);             // caller free()，取走后清空
//   void      GoClearLogs(void);
//   void      GoSetProxyMode(int mode);
package main

/*
#cgo LDFLAGS: -lm
#include <stdlib.h>
#include <jni.h>

// jni_glue.c（包内 C 源，cgo 自动编译）提供：JNI_OnLoad / Java_* / h2aProtectFd
extern void h2aProtectFd(int fd);
*/
import "C"

import (
	"fmt"
	"sync"
	"unsafe"
)

// safeCall：包住入口函数，把 panic 转为错误返回，避免 c-shared 进程 abort
func safeCall(fn func() error) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("Go panic: %v", r)
			logLine("E", "PANIC 已捕获: "+fmt.Sprint(r))
		}
	}()
	return fn()
}

// 全局会话表（sessionId -> *Hy2Session）
var (
	sessionsMu sync.Mutex
	sessions   = make(map[int64]*Hy2Session)
	nextID     int64 = 1
)

//export GoConnect
func GoConnect(jsonPtr *C.char, n C.int) (sid C.longlong) {
	sid = -2
	_ = safeCall(func() error {
		if jsonPtr == nil || n <= 0 {
			return fmt.Errorf("参数为空")
		}
		raw := C.GoBytes(unsafe.Pointer(jsonPtr), n)
		cfg, err := parseConfig(raw)
		if err != nil {
			logLine("E", "配置解析失败: "+err.Error())
			return nil
		}
		sess, err := newHy2Session(cfg)
		if err != nil {
			logLine("E", "hysteria2 连接失败: "+err.Error())
			sid = -3
			return nil
		}
		sessionsMu.Lock()
		id := nextID
		nextID++
		sessions[id] = sess
		sessionsMu.Unlock()
		logLine("I", "HY2 会话已建立 -> "+cfg.serverAddr())
		sid = C.longlong(id)
		return nil
	})
	return sid
}

//export GoStop
func GoStop(sid C.longlong) {
	id := int64(sid)
	sessionsMu.Lock()
	sess, ok := sessions[id]
	if ok {
		delete(sessions, id)
	}
	sessionsMu.Unlock()
	if ok {
		sess.close()
		logLine("I", "HY2 会话已关闭")
	}
}

//export GoSetTunFd
func GoSetTunFd(sid C.longlong, fd C.int) (ret C.int) {
	ret = -9
	_ = safeCall(func() error {
		id := int64(sid)
		sessionsMu.Lock()
		sess, ok := sessions[id]
		sessionsMu.Unlock()
		if !ok {
			ret = -1
			return nil
		}
		if err := sess.attachTun(int(fd)); err != nil {
			logLine("E", "TUN 接管失败: "+err.Error())
			ret = -2
			return nil
		}
		logLine("I", "TUN fd 已接管，开始转发流量")
		ret = 0
		return nil
	})
	return ret
}

//export GoGetStats
func GoGetStats(sid C.longlong) *C.char {
	id := int64(sid)
	sessionsMu.Lock()
	sess, ok := sessions[id]
	sessionsMu.Unlock()
	if !ok {
		return C.CString("")
	}
	return C.CString(sess.statsJSON())
}

//export GoGetLogs
func GoGetLogs() *C.char {
	return C.CString(drainLogs())
}

//export GoClearLogs
func GoClearLogs() {
	clearLogs()
}

//export GoSetProxyMode
func GoSetProxyMode(mode C.int) {
	setProxyMode(int(mode))
}

//export GoSetCnList
func GoSetCnList(text *C.char, n C.int) {
	if text == nil || n <= 0 {
		return
	}
	rules.loadCnIPText(string(C.GoBytes(unsafe.Pointer(text), n)))
}

// cgoProtectFd：供非 cgo 文件调用（hysteria.go/tun.go）
func cgoProtectFd(fd int) {
	C.h2aProtectFd(C.int(fd))
}

func main() {} // c-shared 模式必需