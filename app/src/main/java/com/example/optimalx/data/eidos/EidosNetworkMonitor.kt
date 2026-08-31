package com.example.optimalx.data.eidos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks validated internet connectivity and notifies listeners when the default network is lost
 * so OkHttp connection pools can be evicted after cellular handoff.
 */
class EidosNetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _hasValidatedInternet = MutableStateFlow(false)
    val hasValidatedInternet: StateFlow<Boolean> = _hasValidatedInternet.asStateFlow()

    private val networkLostListeners = CopyOnWriteArrayList<() -> Unit>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshValidatedState()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refreshValidatedState(capabilities)
        }

        override fun onLost(network: Network) {
            _hasValidatedInternet.value = false
            networkLostListeners.forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        refreshValidatedState()
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    fun addOnNetworkLostListener(listener: () -> Unit) {
        networkLostListeners.add(listener)
    }

    fun removeOnNetworkLostListener(listener: () -> Unit) {
        networkLostListeners.remove(listener)
    }

    fun hasValidatedInternetNow(): Boolean {
        refreshValidatedState()
        return _hasValidatedInternet.value
    }

    suspend fun awaitValidatedInternet(timeoutMs: Long = DEFAULT_AWAIT_MS): Boolean {
        if (hasValidatedInternetNow()) return true
        if (timeoutMs <= 0L) return false
        // No default network at all (Wi-Fi off, no cellular) — do not sit on the 15s handoff wait.
        val waitMs = if (currentCapabilities() == null) {
            timeoutMs.coerceAtMost(NO_NETWORK_GRACE_MS)
        } else {
            timeoutMs
        }
        return withTimeoutOrNull(waitMs) {
            hasValidatedInternet.filter { it }.first()
            true
        } ?: false
    }

    private fun refreshValidatedState(capabilities: NetworkCapabilities? = currentCapabilities()) {
        _hasValidatedInternet.value = capabilitiesHaveValidatedInternet(capabilities)
    }

    private fun currentCapabilities(): NetworkCapabilities? {
        val network = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getNetworkCapabilities(network)
    }

    companion object {
        const val DEFAULT_AWAIT_MS = 15_000L
        const val NO_NETWORK_GRACE_MS = 1_500L

        fun capabilitiesHaveValidatedInternet(capabilities: NetworkCapabilities?): Boolean {
            if (capabilities == null) return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
