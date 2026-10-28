package com.flux.downloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TransferPreferences(val parallel: Int = 1, val wifiOnly: Boolean = false, val rateKiB:Int=0) {
    fun normalized() = copy(parallel = parallel.coerceIn(1, 3),rateKiB=rateKiB.takeIf{it in DownloadLimits.rates}?:0)
}

object DownloadLimits {
    val rates=listOf(0,256,512,1024,2048,5120,10240)
    fun label(rate:Int)=if(rate==0)"Unlimited" else if(rate<1024)"$rate KiB/s" else "${rate/1024} MiB/s"
    fun apply(request:com.yausername.youtubedl_android.YoutubeDLRequest,prefs:TransferPreferences) {
        val rate=prefs.normalized().rateKiB
        request.addOption("--concurrent-fragments",if(rate>0)"1" else "3")
        if(rate>0)request.addOption("--limit-rate",(rate*1024).toString())
    }
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
