package com.xfgken.Lumi.core

import android.net.Uri
import com.xfgken.Lumi.model.NodeConfig
import java.net.URLDecoder

/**
 * hysteria2:// URI 解析器。
 *
 * 支持格式（与官方客户端一致）：
 * hysteria2://password@host:port/?sni=example.com&insecure=1&obfs=salamander&obfs-password=xxx&up=100&down=200#name
 */
object Hysteria2UriParser {

    private const val SCHEME_HY2 = "hysteria2"
    private const val SCHEME_HY = "hy2"
    private const val SCHEME_HYSTERIA = "hysteria"

    /**
     * 解析 URI 为节点配置。
     * 兼容性说明：auth(密码) 允许为空（服务端未启用认证时）；
     * 服务器地址/端口缺失才视为非法。
     * @throws IllegalArgumentException 格式非法时抛出
     */
    fun parse(uriText: String): NodeConfig {
        val raw = uriText.trim()
        if (raw.isEmpty()) throw IllegalArgumentException("链接为空")
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase()
        if (scheme != SCHEME_HY2 && scheme != SCHEME_HY && scheme != SCHEME_HYSTERIA) {
            throw IllegalArgumentException("不是 hysteria2:// 链接")
        }
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("缺少服务器地址")

        // userInfo: auth（可空）。手动提取更宽容，兼容部分客户端省略 userinfo 的写法
        val userInfo = run {
            val at = raw.indexOf('@')
            if (at > raw.indexOf("://") + 2) {
                val head = raw.substring(raw.indexOf("://") + 3, at)
                head.substringAfter(':', head) // 兼容 "user:pass" 与 "pass" 两种形式
            } else null
        }
        val password = userInfo?.let { decode(it) } ?: ""

        val port = if (uri.port > 0) uri.port else 443

        // fragment 为节点名（可重复，作为 remark 而非 name）
        val fragment = uri.fragment?.let { decode(it) } ?: ""

        // query 参数
        fun q(key: String): String? =
            uri.getQueryParameter(key) ?: uri.getQueryParameter(key.replace('-', '_'))

        val sni = q("sni") ?: ""
        val insecure = q("insecure")?.let { it == "1" || it.equals("true", true) } ?: false
        val obfs = q("obfs") ?: ""
        val obfsPassword = q("obfs-password") ?: q("obfs_password") ?: ""
        val up = q("up")?.toIntOrNull() ?: 0
        val down = q("down")?.toIntOrNull() ?: 0

        // 默认名字取 host；fragment 作为 remark 备选
        val name = fragment.ifBlank { uri.host!! }

        return NodeConfig(
            name = name,
            server = uri.host!!,
            port = port,
            password = password,
            sni = sni,
            insecure = insecure,
            obfsType = if (obfs.equals("salamander", true)) "salamander" else "",
            obfsPassword = obfsPassword,
            upMbps = up,
            downMbps = down,
            remark = fragment
        )
    }

    /** 序列化节点为 hysteria2:// 链接（不含 remark 防重复拼接） */
    fun toUri(node: NodeConfig): String {
        val sb = StringBuilder("hysteria2://")
        sb.append(encodeUserInfo(node.password)).append('@')
        sb.append(node.server).append(':').append(node.port)
        val params = mutableListOf<String>()
        if (node.sni.isNotBlank()) params += "sni=${encode(node.sni)}"
        if (node.insecure) params += "insecure=1"
        if (node.obfsType == "salamander" && node.obfsPassword.isNotBlank()) {
            params += "obfs=salamander"
            params += "obfs-password=${encode(node.obfsPassword)}"
        }
        if (node.upMbps > 0) params += "up=${node.upMbps}"
        if (node.downMbps > 0) params += "down=${node.downMbps}"
        if (params.isNotEmpty()) sb.append("/?").append(params.joinToString("&"))
        if (node.name.isNotBlank()) sb.append('#').append(encode(node.name))
        return sb.toString()
    }

    /** 序列化节点为 hysteria2:// 链接（不含 remark 防重复拼接） */

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun encode(s: String): String =
        android.net.Uri.encode(s)

    private fun encodeUserInfo(pwd: String): String =
        pwd.replace(":", "%3A").replace("@", "%40").replace("/", "%2F").replace("?", "%3F")
}