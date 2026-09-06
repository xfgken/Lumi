package com.xfgken.Lumi.vpn

import android.content.Context
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底层物理网络观察者（Bedlam 同源）：
 *  - 持续跟踪当前最佳非 VPN 网络
 *  - 网络可用/变化 -> onAvailable（供 VpnService.setUnderlyingNetworks）
 *  - 网络切换稳定后 -> onSettledChange（供上层决定是否重建/重连）
 */
class UnderlyingNetworkObserver(
    context: Context,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onAvailable: (Network?) -> Unit,
    private val onSettledChange: () -> Unit,
) {
    private var seenInitial = false
    private var debounceJob: Job? = null

    @Volatile
    private var lastNetwork: Network? = null

    private val listener = DefaultNetworkListener(context) { network ->
        lastNetwork = network
        onAvailable(network)
        if (!seenInitial) {
            seenInitial = true
            Log.i(TAG, "Initial underlying network: $network")
            return@DefaultNetworkListener
        }
        Log.i(TAG, "Underlying network changed: $network")
        if (network == null) return@DefaultNetworkListener
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            onSettledChange()
        }
    }

    /** 最近一次观察到的底层网络（供 TUN 建立后补报） */
    fun current(): Network? = lastNetwork

    fun start() = listener.start()

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        listener.stop()
    }

    companion object {
        private const val TAG = "UnderlyingNetwork"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }
}