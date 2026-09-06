// 环形日志缓冲：Kotlin 侧轮询拉取（GoGetLogs 取走即清空）
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

// stderr 输出（logcat 可见）
var stderrWriter io.Writer = os.Stderr

type logEntry struct {
	T int64  `json:"t"`
	L string `json:"l"`
	M string `json:"m"`
}

var (
	logMu     sync.Mutex
	logBuffer []logEntry
)

const maxLogs = 800

func logLine(level, msg string) {
	logMu.Lock()
	logBuffer = append(logBuffer, logEntry{T: time.Now().UnixMilli(), L: level, M: msg})
	if len(logBuffer) > maxLogs {
		logBuffer = logBuffer[len(logBuffer)-maxLogs:]
	}
	logMu.Unlock()
	// 同时输出到 stderr 便于 logcat 排查
	fmt.Fprintf(stderrWriter, "[h2a:%s] %s\n", level, msg)
}

func drainLogs() string {
	logMu.Lock()
	defer logMu.Unlock()
	if len(logBuffer) == 0 {
		return ""
	}
	b, err := json.Marshal(logBuffer)
	logBuffer = nil
	if err != nil {
		return ""
	}
	return string(b)
}

func clearLogs() {
	logMu.Lock()
	logBuffer = nil
	logMu.Unlock()
}