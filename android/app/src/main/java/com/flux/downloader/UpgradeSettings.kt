package com.flux.downloader

import android.content.*
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class UpgradePreferences(val language:String="",val startAt:Long=0,val chargingOnly:Boolean=false)
data class PreferenceBackup(val upgraded:UpgradePreferences,val transfer:TransferPreferences,val library:LibraryPreferences,val preset:DownloadPreset,val named:Pair<List<NamedPreset>,String>?=null) {
    fun summary()="Audio: ${AudioLanguages.label(upgraded.language)}\nQuality preset: ${named?.let {data->data.first.firstOrNull {it.id==data.second}?.name} ?: preset.label}\nNetwork: ${if(transfer.wifiOnly)"Wi-Fi only" else "Any connected network"}\nPower: ${if(upgraded.chargingOnly)"Charging required" else "Charging optional"}\nParallel downloads: ${transfer.parallel}\nSpeed cap per download: ${DownloadLimits.label(transfer.rateKiB)}\nLibrary: ${library.layout.name} · ${library.size.name} · ${library.sort.name}\nNamed presets: ${named?.let {"${it.first.size} presets will replace saved named presets"} ?: "kept; stock default restored"}"
}

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
        require(AudioLanguages.valid(value.language)) {"Choose a language, or use a code such as en, hi or en-US."}
        require(value.startAt>=0)
        check(prefs.edit().putString("language",value.language).putLong("startAt",value.startAt).putBoolean("charging",value.chargingOnly).commit())
        preferences.value=value
    }
    fun backup():String=JSONObject().apply {
        put("format","flux-preferences");put("version",1)
        put("language",preferences.value.language);put("chargingOnly",preferences.value.chargingOnly)
        put("parallel",Store.transfers.value.parallel);put("wifiOnly",Store.transfers.value.wifiOnly)
        put("rateKiB",Store.transfers.value.rateKiB);put("downloadPreset",DownloadPresets.default.value.name)
        put("namedPresets",JSONObject().put("items",org.json.JSONArray().apply{NamedPresets.items.value.forEach{put(it.json())}}).put("defaultId",NamedPresets.default.value?.id.orEmpty()))
        put("layout",Store.library.value.layout.name);put("size",Store.library.value.size.name);put("sort",Store.library.value.sort.name)
    }.toString(2)
    fun inspectBackup(text:String):PreferenceBackup {
        require(text.toByteArray().size<=65536) {"Preference backup is too large."}
        try {
        val o=JSONObject(text)
        require(o.optString("format")=="flux-preferences" && o.optInt("version")==1) {"Unsupported preference backup."}
        val allowed=setOf("format","version","language","chargingOnly","parallel","wifiOnly","layout","size","sort","rateKiB","downloadPreset","namedPresets")
        require(o.keys().asSequence().all {it in allowed}) {"Backup contains unsupported fields. Credentials cannot be restored."}
        val upgraded=UpgradePreferences(o.getString("language"),0,o.getBoolean("chargingOnly"))
        require(AudioLanguages.valid(upgraded.language)) {"The backup contains an invalid audio language."}
        val transfer=TransferPreferences(o.getInt("parallel"),o.getBoolean("wifiOnly"),o.optInt("rateKiB",0))
        require(transfer.parallel in 1..3 && transfer.rateKiB in DownloadLimits.rates){"The backup contains unsupported download limits."}
        val preset=runCatching{DownloadPreset.valueOf(o.optString("downloadPreset","Best"))}.getOrElse{error("The backup contains an unsupported quality preset.")}
        val library=runCatching{LibraryPreferences(LibraryLayout.valueOf(o.getString("layout")),LibrarySize.valueOf(o.getString("size")),LibrarySort.valueOf(o.getString("sort")))}.getOrElse{error("The backup contains unsupported Library display settings.")}
        val named=o.optJSONObject("namedPresets")?.let {node->
            require(node.keys().asSequence().all {it in setOf("items","defaultId")}) {"Named preset backup contains unsupported fields."}
            val items=NamedPresets.decode(node.getJSONArray("items").toString())
            val id=node.getString("defaultId")
            require(id.isEmpty() || items.any {it.id==id}) {"Named default preset is missing from this backup."}
            items to id
        }
        require(!o.has("namedPresets") || named!=null) {"Invalid named preset backup."}
        return PreferenceBackup(upgraded,transfer,library,preset,named)
        }catch(e:org.json.JSONException){throw IllegalArgumentException("Choose a valid Flux preferences backup file.",e)}
    }
    fun restore(context:Context,text:String) {
        val data=inspectBackup(text)
        set(data.upgraded.copy(startAt=preferences.value.startAt));Store.setLibrary(data.library);Store.setTransfers(data.transfer);DownloadPresets.set(data.preset)
        data.named?.let {NamedPresets.replace(it.first,it.second)}
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
