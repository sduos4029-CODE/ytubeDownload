package com.flux.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransferPreferences(val parallel: Int = 1, val wifiOnly: Boolean = false) {
    fun normalized() = copy(parallel = parallel.coerceIn(1, 3))
}

object NetworkGate {
    private val mutableWifi = MutableStateFlow(false)
    val wifi = mutableWifi.asStateFlow()
    @Volatile private var manager: ConnectivityManager? = null
    @Volatile private var testWifi: Boolean? = null
    @Synchronized fun start(context: Context) {
        if (manager != null) return
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        manager = connectivity
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        })
        refresh()
    }
    private fun refresh() {
        val connectivity = manager
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        mutableWifi.value = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun allowed(): Boolean {
        val onWifi = (if (BuildConfig.DEBUG) testWifi else null) ?: mutableWifi.value
        return !Store.transfers.value.wifiOnly || onWifi
    }
    // Debug instrumentation simulates a handover without changing the host's network.
    fun testWifi(value: Boolean?) {
        check(BuildConfig.DEBUG) { "Connectivity simulation is disabled in release builds" }
        testWifi = value
    }
}
