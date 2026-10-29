package com.flux.downloader

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale

/** Accepts saved video identities, never arbitrary incoming URLs. */
class PlaybackActivity:ComponentActivity() {
    companion object {fun intent(context:Context,id:String)=Intent(context,PlaybackActivity::class.java).putExtra("download_id",id)}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);volumeControlStream=AudioManager.STREAM_MUSIC;Store.init(this)
        val job=Store.downloads.value.firstOrNull {it.id==intent.getStringExtra("download_id") && it.state=="Complete" && !it.audioOnly && !it.isPhoto && it.uri.startsWith("content://")}
        setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFBAACFF),secondary=Color(0xFF85E5C4))) {
            if(job==null)Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
                Text("This saved video is no longer available.");Button(onClick={finish()}){Text("Close player")}
            } else SavedVideoPlayer(this,job)
        }}
    }
}
internal object PlaybackControls {
    const val SEEK_MS=10000L
    fun seek(position:Long,delta:Long,duration:Long)=(position.coerceAtLeast(0)+delta).coerceIn(0,duration.coerceAtLeast(0))
    fun level(start:Float,dy:Float,height:Float)=if(height<=0 || !dy.isFinite())start.coerceIn(0f,1f) else (start-dy/height*1.5f).coerceIn(0f,1f)
    fun time(ms:Long):String {
        val s=ms.coerceAtLeast(0)/1000
        return if(s>=3600)String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60) else String.format(Locale.US,"%d:%02d",s/60,s%60)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedVideoPlayer(activity:PlaybackActivity,job:Download) {
    val audio=remember {activity.getSystemService(AudioManager::class.java)}
    val view=remember(job.id){VideoView(activity).apply{tag="flux-saved-player";setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN)}}
    var player by remember {mutableStateOf<MediaPlayer?>(null)}
    var ready by remember {mutableStateOf(false)}
    var duration by remember {mutableLongStateOf(0)}
    var position by rememberSaveable(job.id){mutableLongStateOf(0)}
    var wantsPlay by rememberSaveable(job.id){mutableStateOf(true)}
    var playing by remember {mutableStateOf(false)}
    var scrubbing by remember {mutableStateOf(false)}
    var resumeScrub by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    var feedback by remember {mutableStateOf("")}
    var settings by remember {mutableStateOf(false)}
    var volume by remember {mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))}
    val originalBrightness=remember {activity.window.attributes.screenBrightness}
    var brightness by rememberSaveable(job.id){mutableFloatStateOf(if(originalBrightness>=0)originalBrightness else runCatching{Settings.System.getInt(activity.contentResolver,Settings.System.SCREEN_BRIGHTNESS)/255f}.getOrDefault(.5f).coerceIn(.05f,1f))}
    var brightnessChanged by rememberSaveable(job.id){mutableStateOf(false)}
    fun pause(){wantsPlay=false;playing=false;view.keepScreenOn=false;runCatching{view.pause()}}
    fun setBrightness(value:Float){
        brightness=value.coerceIn(.05f,1f);brightnessChanged=true
        activity.window.attributes=activity.window.attributes.apply{screenBrightness=brightness}
        feedback="Brightness ${(brightness*100).roundToInt()}%"
    }
    fun setVolume(value:Float){
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        runCatching{audio.setStreamVolume(AudioManager.STREAM_MUSIC,(value.coerceIn(0f,1f)*max).roundToInt(),0)}
            .onFailure{feedback="Volume is managed by your device."}
            .onSuccess{volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/max;feedback="Volume ${(volume*100).roundToInt()}%"}
    }
    fun seek(delta:Long){
        if(!ready)return
        position=PlaybackControls.seek(position,delta,duration)
        runCatching{player?.seekTo(position,MediaPlayer.SEEK_CLOSEST)}.onFailure{error="This video cannot seek on this device."}
        feedback=if(delta>0)"Forward 10 seconds" else "Back 10 seconds"
    }
    fun toggle(){
        if(!ready)return
        if(view.isPlaying)pause() else {
            if(position>=duration-200){position=0;view.seekTo(0)}
            wantsPlay=true;view.start();playing=true;view.keepScreenOn=true
        }
    }
    fun external(){
        pause()
        runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.uri),job.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
            .onFailure{Toast.makeText(activity,"No compatible video app is installed, or folder access is missing.",Toast.LENGTH_LONG).show()}
    }
    val liveSeek by rememberUpdatedState<(Long)->Unit>(::seek)
    val liveVolume by rememberUpdatedState<(Float)->Unit>(::setVolume)
    val liveBrightness by rememberUpdatedState<(Float)->Unit>(::setBrightness)
    val liveVolumeValue by rememberUpdatedState(volume)
    val liveBrightnessValue by rememberUpdatedState(brightness)
    val livePosition by rememberUpdatedState(position)
    val liveWantsPlay by rememberUpdatedState(wantsPlay)
    DisposableEffect(view){
        view.setOnPreparedListener{media->
            player=media;duration=media.duration.toLong().coerceAtLeast(0);ready=true
            if(livePosition>0)media.seekTo(livePosition.coerceAtMost(duration),MediaPlayer.SEEK_CLOSEST)
            media.setOnSeekCompleteListener{if(!scrubbing)position=view.currentPosition.toLong().coerceAtLeast(0)}
            if(liveWantsPlay && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){view.start();playing=true;view.keepScreenOn=true}
        }
        view.setOnCompletionListener{position=duration;pause()}
        view.setOnErrorListener{_,_,_->ready=false;pause();error="Android cannot play this video on this device. Try another video app or convert it with Media tools.";true}
        onDispose{pause();view.setOnPreparedListener(null);view.setOnCompletionListener(null);view.setOnErrorListener(null);player?.setOnSeekCompleteListener(null);view.stopPlayback();player=null}
    }
    LaunchedEffect(job.id){
        val available=withContext(Dispatchers.IO){LibraryDetails.inspect(activity,job).available}
        if(available)runCatching{view.setVideoURI(Uri.parse(job.uri))}.onFailure{error="Cannot open this saved video. Check its download folder and permissions."}
        else error="This file is missing or its folder access has changed. Check File details in Library."
    }
    DisposableEffect(activity){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_PAUSE || event==Lifecycle.Event.ON_STOP){
                position=runCatching{view.currentPosition.toLong()}.getOrDefault(position);pause()
                activity.window.attributes=activity.window.attributes.apply{screenBrightness=originalBrightness}
            } else if(event==Lifecycle.Event.ON_RESUME){
                volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                if(brightnessChanged)activity.window.attributes=activity.window.attributes.apply{screenBrightness=brightness}
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose{activity.lifecycle.removeObserver(observer);activity.window.attributes=activity.window.attributes.apply{screenBrightness=originalBrightness}}
    }
    LaunchedEffect(view){while(true){
        if(ready && !scrubbing){
            if(view.isPlaying)position=view.currentPosition.toLong().coerceAtLeast(0)
            playing=view.isPlaying;view.keepScreenOn=playing
            volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        };delay(200)
    }}
    LaunchedEffect(feedback){if(feedback.isNotBlank()){delay(1200);feedback=""}}
    Column(Modifier.fillMaxSize().background(Color(0xFF0C0D13)).safeDrawingPadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={activity.finish()}){Icon(Icons.Rounded.Close,"Close player")}
            Text(job.title,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            IconButton(onClick={settings=true}){Icon(Icons.Rounded.Tune,"Playback settings")}
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),contentAlignment=Alignment.Center){
            AndroidView(factory={FrameLayout(activity).apply{addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT,Gravity.CENTER))}},modifier=Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().semantics{contentDescription="Video gestures"}
                .pointerInput(job.id){detectTapGestures(onDoubleTap={point->liveSeek(if(point.x>=size.width/2f)PlaybackControls.SEEK_MS else -PlaybackControls.SEEK_MS)})}
                .pointerInput(job.id){
                    var right=false;var initial=0f;var distance=0f
                    detectVerticalDragGestures(onDragStart={point->right=point.x>=size.width/2f;initial=if(right)liveVolumeValue else liveBrightnessValue;distance=0f},onVerticalDrag={change,dy->
                        change.consume();distance+=dy;val next=PlaybackControls.level(initial,distance,size.height.toFloat())
                        if(right)liveVolume(next) else liveBrightness(next)
                    })
                })
            if(!ready && error.isBlank())CircularProgressIndicator(Modifier.size(32.dp))
            if(feedback.isNotBlank())Surface(shape=MaterialTheme.shapes.large,color=Color.Black.copy(alpha=.8f),modifier=Modifier.padding(16.dp)){Text(feedback,Modifier.padding(18.dp))}
        }
        Surface(color=Color(0xFF171822)){
            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Text("${PlaybackControls.time(position)} / ${PlaybackControls.time(duration)}",style=MaterialTheme.typography.labelMedium)
                Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=ready,modifier=Modifier.semantics{contentDescription="Video position"},
                    onValueChange={if(!scrubbing){resumeScrub=view.isPlaying;pause();scrubbing=true};position=it.toLong()},onValueChangeFinished={
                        scrubbing=false;runCatching{player?.seekTo(position,MediaPlayer.SEEK_CLOSEST)}
                        if(resumeScrub){wantsPlay=true;view.start();playing=true};resumeScrub=false
                    })
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                    IconButton(enabled=ready,onClick={seek(-PlaybackControls.SEEK_MS)}){Icon(Icons.Rounded.Replay10,"Back 10 seconds")}
                    FilledIconButton(enabled=ready,onClick=::toggle,modifier=Modifier.padding(horizontal=24.dp)){Icon(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,if(playing)"Pause video" else "Play video")}
                    IconButton(enabled=ready,onClick={seek(PlaybackControls.SEEK_MS)}){Icon(Icons.Rounded.Forward10,"Forward 10 seconds")}
                }
                TextButton(onClick=::external,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Open in another app")}
            }
        }
    }
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding()){
            Text("Playback controls",style=MaterialTheme.typography.titleLarge)
            Text("Volume ${(volume*100).roundToInt()}%")
            Slider(value=volume,onValueChange=::setVolume,modifier=Modifier.semantics{contentDescription="Playback volume"})
            Text("Brightness ${(brightness*100).roundToInt()}%")
            Slider(value=brightness,valueRange=.05f..1f,onValueChange=::setBrightness,modifier=Modifier.semantics{contentDescription="Playback brightness"})
            Text("Swipe up/down on the right for volume, on the left for brightness. Double-tap right to go forward 10 seconds; double-tap left to go back. Brightness changes apply only to this player window.",style=MaterialTheme.typography.bodySmall)
            Button(onClick={settings=false},modifier=Modifier.fillMaxWidth().padding(top=16.dp)){Text("Done with playback settings")}
        }
    }
}
