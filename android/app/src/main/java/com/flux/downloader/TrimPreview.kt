package com.flux.downloader

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale

/** The same validated values feed both preview bounds and the export request. */
internal fun trimBounds(start:String,end:String,duration:Double):Pair<Long,Long>? = runCatching {
    val from=MediaEdits.seconds(start);val until=MediaEdits.seconds(end)
    require(duration.isFinite() && duration>0 && from<until && until<=duration)
    (from*1000).toLong() to (until*1000).toLong()
}.getOrNull()

internal fun trimTime(seconds:Double)=String.format(Locale.US,"%.3f",seconds)

@Composable
internal fun TrimPreview(source:Download,info:EditSource,start:String,end:String,active:Boolean,onRange:(String,String)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val bounds=trimBounds(start,end,info.seconds)
    val isActive by rememberUpdatedState(active)
    val view=remember(source.uri){VideoView(context).apply{tag="flux-trim-preview"}}
    var player by remember(source.uri){mutableStateOf<MediaPlayer?>(null)}
    var ready by remember(source.uri){mutableStateOf(false)}
    var playing by remember(source.uri){mutableStateOf(false)}
    var seeking by remember(source.uri){mutableStateOf(false)}
    var error by remember(source.uri){mutableStateOf("")}
    var position by remember(source.uri){mutableStateOf(0L)}
    fun pause(){seeking=false;playing=false;runCatching{if(view.isPlaying)view.pause()}}
    DisposableEffect(view,lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP)pause()}
        lifecycle.addObserver(observer)
        view.setOnPreparedListener {media ->
            player=media;ready=true
            media.setOnSeekCompleteListener {
                if(seeking && isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    seeking=false;view.start();playing=true
                }
            }
        }
        view.setOnCompletionListener {playing=false;seeking=false}
        view.setOnErrorListener {_,_,_->pause();ready=false;error="Android cannot preview this format on this device. You can still export it or choose a compatible MP4 output.";true}
        try{view.setVideoURI(Uri.parse(source.uri))}catch(_:Exception){error="Preview cannot open this saved file. Check its folder access."}
        onDispose {
            lifecycle.removeObserver(observer);pause()
            view.setOnPreparedListener(null);view.setOnCompletionListener(null);view.setOnErrorListener(null)
            player?.setOnSeekCompleteListener(null);view.stopPlayback();player=null
        }
    }
    LaunchedEffect(start,end,active){pause();position=bounds?.first?:0}
    LaunchedEffect(playing,bounds) {
        while(playing) {
            position=runCatching{view.currentPosition.toLong()}.getOrDefault(0)
            if(bounds==null || position>=bounds.second){pause();break}
            delay(40)
        }
    }
    Text("Select trim range",style=MaterialTheme.typography.titleSmall)
    if(info.seconds.isFinite() && info.seconds>0) {
        val total=info.seconds.toFloat()
        val from=(runCatching{MediaEdits.seconds(start).toFloat()}.getOrDefault(0f)).coerceIn(0f,total)
        val until=(runCatching{MediaEdits.seconds(end).toFloat()}.getOrDefault(total)).coerceIn(from,total)
        RangeSlider(enabled=active,value=from..until,valueRange=0f..total,onValueChange={range ->
            pause();onRange(trimTime(range.start.toDouble()),trimTime(kotlin.math.floor(range.endInclusive.toDouble().coerceAtMost(info.seconds)*1000)/1000))
        })
        Text("Drag the two handles or enter precise times below.",style=MaterialTheme.typography.bodySmall)
    }
    AndroidView(factory={view},modifier=Modifier.fillMaxWidth().height(if(info.video)190.dp else 48.dp))
    if(!info.video)Text("Audio preview",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=active && ready && bounds!=null && !seeking,onClick={
            if(playing)pause() else {
                val range=bounds?:return@OutlinedButton
                seeking=true
                runCatching{player!!.seekTo(range.first,MediaPlayer.SEEK_CLOSEST)}.onFailure{pause();error="Preview could not seek to this time. Try another range."}
            }
        }){Text(if(playing)"Pause preview" else "Play selected clip")}
    }
    if(playing)Text("Preview: ${trimTime(position/1000.0)} seconds")
    if(bounds==null)Text("Enter an end after the start, within the source duration.",color=MaterialTheme.colorScheme.error)
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    Text("Previews the selected part of the original file. Export codecs and quality are applied later; stream-copy cuts can follow different keyframe boundaries.",style=MaterialTheme.typography.bodySmall)
}
