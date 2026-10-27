package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun MediaToolsDialog(source:Download,onDismiss:()->Unit,onQueued:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var info by remember(source.id){mutableStateOf<EditSource?>(null)}
    var encoders by remember{mutableStateOf<Set<String>?>(null)}
    var operation by remember{mutableStateOf(EditOperation.Trim)}
    var preset by remember{mutableStateOf(if(source.audioOnly)EditPreset.OriginalAudio else EditPreset.Mkv)}
    var quality by remember{mutableStateOf(EditQuality.Balanced)}
    var start by remember{mutableStateOf("0")};var end by remember{mutableStateOf(source.duration.takeIf{it>0}?.toString().orEmpty())}
    var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<Download?>(null)}
    LaunchedEffect(source.id) {
        try {
            val data=withContext(Dispatchers.IO){MediaEdits.probe(context,source)};info=data
            if(data.seconds>0)end=trimTime(kotlin.math.floor(data.seconds*1000)/1000)
            encoders=NativeMedia.encoders(context)
        }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to inspect this file."}
    }
    val available=info?.let{MediaEdits.presets(it,operation)}.orEmpty()
    LaunchedEffect(operation,info){if(preset !in available && available.isNotEmpty())preset=available.first()}
    val valid=info!=null && (operation!=EditOperation.Trim || trimBounds(start,end,info?.seconds?:0.0)!=null) && encoders!=null && preset in available && preset.encoders.all{it in encoders.orEmpty()}
    if(confirm!=null)AlertDialog(onDismissRequest={if(!busy)confirm=null},title={Text("Create a new media file?")},text={Text("${operation.label}\n${preset.label}\n${if(preset.copies)"Stream copying keeps the source encoding." else if(preset==EditPreset.Flac)"FLAC storage cannot restore detail lost in the source." else if(preset==EditPreset.Wav)"16-bit PCM output may reduce precision from higher-bit-depth sources." else quality.label+" · re-encoding can lose quality."}\nThe original stays unchanged. Exports use the current download folder and appear in Library.")},
        confirmButton={TextButton(enabled=!busy,onClick={busy=true;val job=confirm!!;scope.launch{
            try {withContext(Dispatchers.IO){MediaEdits.enqueue(context,job)};confirm=null;onQueued()}
            catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to queue this export.";confirm=null}finally{busy=false}
        }}){Text("Create new file")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=null}){Text("Keep browsing")}})
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Media tools")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(source.title,fontWeight=FontWeight.Bold)
            Text("Create a new file from this saved item. No upload or new download is needed.")
            if(encoders==null && message.isBlank())LinearProgressIndicator(Modifier.fillMaxWidth())
            EditOperation.entries.forEach {option ->
                FilterChip(selected=operation==option,onClick={operation=option},enabled=!busy && (option!=EditOperation.Audio || info?.audio==true),label={Text(option.label)})
            }
            if(operation==EditOperation.Trim) {
                info?.let { data -> TrimPreview(source,data,start,end,active=confirm==null && !busy){from,until->start=from;end=until} }
                OutlinedTextField(value=start,onValueChange={start=it.take(24)},label={Text("Start · seconds or HH:MM:SS")},singleLine=true,enabled=!busy)
                OutlinedTextField(value=end,onValueChange={end=it.take(24)},label={Text("End · seconds or HH:MM:SS")},singleLine=true,enabled=!busy)
                Text("Source duration: ${info?.seconds?.takeIf{it>0}?.let{durationLabel(it.toLong())}?:"Not reported"}")
            }
            Box {
                OutlinedButton(enabled=!busy && info!=null,onClick={menu=true}){Text(preset.label)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    available.forEach {option ->DropdownMenuItem(enabled=encoders!=null && option.encoders.all{it in encoders.orEmpty()},text={Text(option.label+if(encoders!=null && !option.encoders.all{it in encoders.orEmpty()})" · unavailable" else "")},onClick={preset=option;menu=false})}
                }
            }
            if(!preset.copies && preset !in setOf(EditPreset.Flac,EditPreset.Wav)) {
                Text("Output quality",fontWeight=FontWeight.SemiBold)
                EditQuality.entries.forEach {option->FilterChip(selected=quality==option,onClick={quality=option},enabled=!busy,label={Text(option.label)})}
                Text(if(preset==EditPreset.Mp4H264)"H.264 CRF ${quality.crf} · AAC ${quality.aac} kbps" else if(preset==EditPreset.Mp3)"MP3 ${quality.mp3} kbps" else "AAC ${quality.aac} kbps")
            }
            Text(when {
                preset.copies && operation==EditOperation.Trim ->"Stream-copy trimming follows packet/keyframe boundaries and may differ from the requested times. Use H.264 + AAC for more precise video cuts when available. Short cuts without a video keyframe may fail safely."
                preset==EditPreset.Mp4Copy ->"Remuxing keeps compatible source codecs. Some codecs cannot be stored in MP4; choose MKV or re-encode if this fails."
                preset==EditPreset.Flac ->"FLAC preserves decoded samples but cannot restore detail lost in a lossy source. Files can be larger."
                preset==EditPreset.Wav ->"WAV exports 16-bit PCM. It can be much larger and may reduce precision from higher-bit-depth sources."
                preset.copies ->"Original streams are copied without re-encoding. MKA is Matroska audio; some players may need a different format."
                else ->"Re-encoding changes quality and can take time. Higher bitrate does not restore missing detail. H.264 video uses at most one pixel of padding for even dimensions."
            },style=MaterialTheme.typography.bodySmall)
            Text("Uses the first video/audio tracks. Extra tracks, subtitles, chapters and source metadata are excluded. Local tools process one item at a time. A paused or interrupted edit restarts from the beginning.",style=MaterialTheme.typography.bodySmall)
            Text("Working space: ${info?.bytes?.let(StorageBudget::required)?.let{sizeLabel(it)+" estimated"}?:"estimate unavailable"}. Large WAV/FLAC or re-encoded outputs may need more. Destination free space can be unknown.",style=MaterialTheme.typography.bodySmall)
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=valid && !busy,onClick={
        runCatching{MediaEdits.prepare(source,info!!,operation,preset,quality,start,end,Store.folder.value)}.onSuccess{confirm=it}.onFailure{message=it.message?:"Check the selected times and format."}
    }){Text("Review export")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("Close media tools")}})
}
