package com.xfgken.Lumi.vpn

/**
 * VPN 连接状态（首页大按钮状态机）。
 */
enum class VpnState {
    /** 未连接 */
    DISCONNECTED,

    /** 正在请求授权 / 启动服务 */
    CONNECTING,

    /** 已连接 */
    CONNECTED,

    /** 正在断开 */
    DISCONNECTING
}

/** 代理模式（与 SettingsStore 一致） */
object ProxyMode {
    const val GLOBAL = 0      // 全部走 HY2
    const val RULE = 1        // 国内直连 / 国外代理
    const val DIRECT = 2      // 全部直连
}