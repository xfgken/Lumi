package com.xfgken.Lumi.model

/**
 * Hysteria2 节点配置。
 *
 * 对应 hysteria2:// URI 的全部可携带参数。
 * password / obfsPassword 落库前必须经 KeystoreCipher 加密。
 */
data class NodeConfig(
    val id: Long = 0L,
    val name: String,
    val server: String,
    val port: Int = 443,
    val password: String = "",
    val sni: String = "",
    val insecure: Boolean = false,
    val obfsType: String = "",        // "" | "salamander"
    val obfsPassword: String = "",
    val upMbps: Int = 0,              // 0 = 不限制
    val downMbps: Int = 0,
    val pingMs: Int = -1,             // -1 = 未测
    val remark: String = "",
    val isSelected: Boolean = false
) {

    /** 测延迟 / 展示用短名称 */
    fun displayName(): String = name.ifBlank { server }

    companion object {
        /** 延迟状态码（落库 pingMs 列）：-1 未测 / -2 连接超时 / -3 不可达 */
        const val PING_UNTESTED = -1
        const val PING_TIMEOUT = -2
        const val PING_UNREACHABLE = -3

        fun empty() = NodeConfig(name = "", server = "")
    }
}