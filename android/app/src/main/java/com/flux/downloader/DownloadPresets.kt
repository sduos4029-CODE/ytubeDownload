package com.flux.downloader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

enum class DownloadPreset(val label:String,val height:Int=0,val audioOnly:Boolean=false) {
    Best("Best available"), FullHD("Up to 1080p",1080), DataSaver("Up to 720p",720), Audio("Audio only",audioOnly=true);
    fun choose(video:Video):MediaFormat? {
        if(audioOnly)return AudioSelection.preferred(video)?:video.formats.filter{it.audio && !it.video}.asReversed().sortedBy{it.isDrc}.firstOrNull()
        return video.formats.filter{it.video && (height==0 || (if(it.width>0 && it.height>it.width)it.width else it.height) in 1..height)}
            .sortedWith(compareByDescending<MediaFormat>{it.height}.thenByDescending{it.fps}.thenBy{it.extension}).firstOrNull()
    }
}
object DownloadPresets {
    private lateinit var prefs:android.content.SharedPreferences
    val default=MutableStateFlow(DownloadPreset.Best)
    fun init(context:Context) {
        if(::prefs.isInitialized)return
        prefs=context.getSharedPreferences("flux_presets",0)
        default.value=runCatching{DownloadPreset.valueOf(prefs.getString("default","Best")!!)}.getOrDefault(DownloadPreset.Best)
    }
    fun set(value:DownloadPreset) {
        check(prefs.edit().putString("default",value.name).commit());default.value=value
    }
}
@Composable
fun DownloadPresetSettingsCard() {
    val preset by DownloadPresets.default.collectAsState()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("Default download preset",style=MaterialTheme.typography.titleMedium)
            Text("Starting quality for single-video downloads. Review and change formats before saving.",style=MaterialTheme.typography.bodySmall)
            DownloadPreset.entries.forEach {option->FilterChip(selected=preset==option,onClick={DownloadPresets.set(option)},label={Text(option.label)})}
            Text("Uses source formats without upscaling. Playlist batches keep their existing best-available selection.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
