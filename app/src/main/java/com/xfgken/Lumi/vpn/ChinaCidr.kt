package com.xfgken.Lumi.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.IpPrefix
import java.net.InetAddress
import java.net.Inet4Address

/**
 * 中国大陆 IPv4 段表（assets/china_ipv4.txt，由 17mon/china_ip_list 合并而来）。
 * 供"规则模式：中国流量直连"使用（API 33+ VpnService.Builder.excludeRoute）。
 *
 * 稳定性说明：系统对 VpnService.Builder 的排除条数有隐性容忍上限，一次性排除
 * 数千条小段可能导致系统不把该 VPN 判定为"活动 VPN"（状态栏图标消失）。
 * 因此这里按覆盖 IP 数降序取前 MAX_EXCLUDE_ROUTES 条：大段优先，用最少条数
 * 覆盖最多国内流量，同时让系统正常显示 VPN 图标。
 *
 * 覆盖测算（china_ipv4.txt 共 7456 条，IP 空间 3.54 亿）：
 *  500 条 → 87.9%；600 条 → 91.0%；800 条 → 94.7%；1000 条 → 96.7%
 * （数据源段间无相邻冗余，CIDR 合并无收益，覆盖只能随条数增长。）
 */
object ChinaCidr {

    /** 单次最多写入系统的排除路由条数（Bedlam 官方直连源 ≈600 条量级实测安全；本值 800 处于安全区） */
    const val MAX_EXCLUDE_ROUTES: Int = 800

    @Volatile
    private var cached: List<IpPrefix>? = null

    /** 加载并缓存中国段（仅在 API 33+ 调用，IpPrefix 构造需 API 31+） */
    @SuppressLint("NewApi")
    fun load(context: Context): List<IpPrefix> {
        cached?.let { return it }
        val list = ArrayList<IpPrefix>(7500)
        try {
            context.assets.open("china_ipv4.txt").bufferedReader().use { r ->
                r.forEachLine { line ->
                    val s = line.trim()
                    if (s.isEmpty() || '/' !in s) return@forEachLine
                    val slash = s.indexOf('/')
                    val ip = s.substring(0, slash)
                    val pfx = s.substring(slash + 1).toIntOrNull() ?: return@forEachLine
                    if (pfx !in 0..32) return@forEachLine
                    runCatching {
                        val addr = InetAddress.getByName(ip)
                        list.add(IpPrefix(addr, pfx))
                    }
                }
            }
        } catch (_: Exception) {
        }
        cached = list
        return list
    }

    /**
     * 精简中国段：按覆盖 IP 数降序，取前 [maxRoutes] 条。
     * 覆盖数 = 2^(32-prefix)。大段优先，保证以最少条数覆盖最多国内流量。
     */
    @SuppressLint("NewApi")
    fun loadCompact(context: Context, maxRoutes: Int = MAX_EXCLUDE_ROUTES): List<IpPrefix> {
        val all = load(context)
        if (all.size <= maxRoutes) return all
        val sorted = all.sortedByDescending { p ->
            val a = p.address
            if (a is Inet4Address) 1L shl (32 - p.prefixLength) else 0L
        }
        return sorted.take(maxRoutes)
    }

    fun cachedSize(): Int = cached?.size ?: 0
}