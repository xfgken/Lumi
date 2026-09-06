package com.xfgken.Lumi.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

/**
 * 节点延迟测试结果。
 * Ok=测得 RTT（含 RST：主机可达端口关闭，RTT 仍有效）；Timeout=连接超时；Unreachable=不可达。
 */
sealed class PingResult {
    data class Ok(val ms: Int) : PingResult()
    object Timeout : PingResult()
    object Unreachable : PingResult()
}

/**
 * 节点延迟测试（借鉴 Bedlam）：选取「非 VPN」底层网络做 TCP 连接计时，
 * 避免测试流量进入自身 TUN；RST 视为主机可达（RTT 有效）。
 */
object PingUtil {

    private const val TIMEOUT_MS = 3_000
    private const val NETWORK_WAIT_MS = 2_000L

    /** @return Ok(延迟毫秒) / Timeout(超时) / Unreachable(不可达) */
    suspend fun measure(context: Context, host: String, port: Int): PingResult =
        withContext(Dispatchers.IO) {
            val network = underlyingNetwork(context) ?: return@withContext PingResult.Unreachable
            val factory = network.socketFactory ?: return@withContext PingResult.Unreachable
            try {
                factory.createSocket().use { socket ->
                    val start = System.currentTimeMillis()
                    try {
                        socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                    } catch (_: SocketTimeoutException) {
                        return@withContext PingResult.Timeout
                    } catch (_: ConnectException) {
                        // RST：端口关闭但主机响应，RTT 仍有效
                    } catch (_: IOException) {
                        return@withContext PingResult.Unreachable
                    }
                    val ms = System.currentTimeMillis() - start
                    if (ms < TIMEOUT_MS) PingResult.Ok(ms.toInt()) else PingResult.Timeout
                }
            } catch (_: IOException) {
                PingResult.Unreachable
            }
        }

    private suspend fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // 先看当前网络是否已是非 VPN
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (active != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return active
        }
        // 否则等待系统给出非 VPN 网络
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        return withTimeoutOrNull(NETWORK_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(network)
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
                runCatching { cm.registerNetworkCallback(request, callback) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}