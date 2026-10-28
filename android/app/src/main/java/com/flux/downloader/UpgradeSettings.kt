package com.flux.downloader

import android.content.*
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class UpgradePreferences(val language:String="",val startAt:Long=0,val chargingOnly:Boolean=false)

object UpgradeSettings {
    val preferences=MutableStateFlow(UpgradePreferences())
    private lateinit var prefs:android.content.SharedPreferences
    fun init(context:Context) {
        if(::prefs.isInitialized)return
        prefs=context.getSharedPreferences("flux_upgrade",0)
        preferences.value=UpgradePreferences(prefs.getString("language","").orEmpty(),prefs.getLong("startAt",0),prefs.getBoolean("charging",false))
        ChargingGate.start(context)
    }
    fun set(value:UpgradePreferences) {
        require(value.language.isEmpty() || value.language.matches(Regex("[A-Za-z0-9_-]{2,20}"))) {"Use a language code such as en, hi or en-US."}
        require(value.startAt>=0)
        check(prefs.edit().putString("language",value.language).putLong("startAt",value.startAt).putBoolean("charging",value.chargingOnly).commit())
        preferences.value=value
    }
    fun backup():String=JSONObject().apply {
        put("format","flux-preferences");put("version",1)
        put("language",preferences.value.language);put("chargingOnly",preferences.value.chargingOnly)
        put("parallel",Store.transfers.value.parallel);put("wifiOnly",Store.transfers.value.wifiOnly)
        put("rateKiB",Store.transfers.value.rateKiB);put("downloadPreset",DownloadPresets.default.value.name)
        put("layout",Store.library.value.layout.name);put("size",Store.library.value.size.name);put("sort",Store.library.value.sort.name)
    }.toString(2)
    fun restore(context:Context,text:String) {
        require(text.toByteArray().size<=65536) {"Preference backup is too large."}
        val o=JSONObject(text)
        require(o.optString("format")=="flux-preferences" && o.optInt("version")==1) {"Unsupported preference backup."}
        val allowed=setOf("format","version","language","chargingOnly","parallel","wifiOnly","layout","size","sort","rateKiB","downloadPreset")
        require(o.keys().asSequence().all {it in allowed}) {"Backup contains unsupported fields. Credentials cannot be restored."}
        val upgraded=UpgradePreferences(o.getString("language"),0,o.getBoolean("chargingOnly"))
        require(upgraded.language.isEmpty() || upgraded.language.matches(Regex("[A-Za-z0-9_-]{2,20}")))
        val transfer=TransferPreferences(o.getInt("parallel"),o.getBoolean("wifiOnly"),o.optInt("rateKiB",0))
        require(transfer.parallel in 1..3 && transfer.rateKiB in DownloadLimits.rates)
        val preset=DownloadPreset.valueOf(o.optString("downloadPreset","Best"))
        val library=LibraryPreferences(LibraryLayout.valueOf(o.getString("layout")),LibrarySize.valueOf(o.getString("size")),LibrarySort.valueOf(o.getString("sort")))
        set(upgraded);Store.setLibrary(library);Store.setTransfers(transfer);DownloadPresets.set(preset)
        Downloads.recover(context)
    }
}

object ChargingGate {
    val charging=MutableStateFlow(false)
    private var started=false
    private var simulation:Boolean?=null
    fun start(context:Context) {
        if(started)return
        started=true
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {
                charging.value=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context.applicationContext,receiver,IntentFilter(Intent.ACTION_BATTERY_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun allowed()=!UpgradeSettings.preferences.value.chargingOnly || ((if(BuildConfig.DEBUG)simulation else null)?:charging.value)
    fun simulate(value:Boolean?) {check(BuildConfig.DEBUG);simulation=value}
}

object TransferGate {
    fun allowed()=NetworkGate.allowed() && ChargingGate.allowed()
    val reason get()=if(!ChargingGate.allowed())"Waiting for charging" else "Waiting for Wi-Fi"
}
