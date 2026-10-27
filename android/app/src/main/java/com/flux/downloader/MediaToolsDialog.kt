package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

/** A dedicated, full-window editor rather than a small settings popup. */
@Composable
fun MediaToolsDialog(source:Download,onDismiss:()->Unit,onQueued:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var info by remember(source.id){mutableStateOf<EditSource?>(null)}
    var encoders by remember{mutableStateOf<Set<String>?>(null)}
    var operation by remember{mutableStateOf(EditOperation.Trim)}
    var preset by remember{mutableStateOf(if(source.audioOnly)EditPreset.OriginalAudio else EditPreset.Mkv)}
    var quality by remember{mutableStateOf(EditQuality.High)}
    var start by remember{mutableStateOf("0")};var end by remember{mutableStateOf(source.duration.takeIf{it>0}?.toString().orEmpty())}
    var clips by remember{mutableStateOf<List<TrimClip>>(emptyList())};var selected by remember{mutableStateOf(-1)}
    var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<Download?>(null)}
    fun select(index:Int){selected=index;start=trimTime(clips[index].start);end=trimTime(clips[index].end);message=""}
    fun range(from:String,until:String) {
        start=from;end=until
        if(selected in clips.indices && trimBounds(from,until,info?.seconds?:0.0)!=null) {
            clips=clips.toMutableList().apply{this[selected]=TrimClip(MediaEdits.seconds(from),MediaEdits.seconds(until))}
        }
    }
    LaunchedEffect(source.id) {
        try {
            val data=withContext(Dispatchers.IO){MediaEdits.probe(context,source)};info=data
            if(data.seconds>0)end=trimTime(kotlin.math.floor(data.seconds*1000)/1000)
            encoders=NativeMedia.encoders(context)
        }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to inspect this file."}
    }
    val multi=operation==EditOperation.Trim && clips.size>=2
    val available=info?.let{MediaEdits.presets(it,operation)}.orEmpty()
    LaunchedEffect(operation,info,multi){if(multi)preset=EditPreset.Mp4H264 else if(preset !in available && available.isNotEmpty())preset=available.first()}
    val valid=info!=null && (!multi || EditPreset.Mp4H264.encoders.all{it in encoders.orEmpty()}) && (operation!=EditOperation.Trim || (trimBounds(start,end,info?.seconds?:0.0)!=null && (clips.isEmpty() || selected>=0))) && encoders!=null && preset in available && preset.encoders.all{it in encoders.orEmpty()}
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)){Text("Video editor",style=MaterialTheme.typography.titleLarge);Text(source.title,maxLines=1,style=MaterialTheme.typography.bodySmall)}
                    TextButton(enabled=!busy,onClick=onDismiss){Text("Close media tools")}
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    val data=info
                    if(data!=null) {
                        val from=if(operation==EditOperation.Trim)start else "0"
                        val until=if(operation==EditOperation.Trim)end else trimTime(kotlin.math.floor(data.seconds*1000)/1000)
                        TrimPreview(source,data,from,until,active=confirm==null && !busy,showTrim=operation==EditOperation.Trim,onRange=::range)
                    } else if(message.isBlank())LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(operation==EditOperation.Trim) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value=start,onValueChange={range(it.take(24),end)},modifier=Modifier.weight(1f),label={Text("Start time")},singleLine=true,enabled=!busy)
                            OutlinedTextField(value=end,onValueChange={range(start,it.take(24))},modifier=Modifier.weight(1f),label={Text("End time")},singleLine=true,enabled=!busy)
                        }
                        Text("Seconds or HH:MM:SS · millisecond precision",style=MaterialTheme.typography.bodySmall)
                        if(data?.video==true) {
                            Text("Clip list · ${clips.size} / ${ClipEdits.MAX_CLIPS}",fontWeight=FontWeight.Bold)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled=!busy && selected==-1 && clips.size<ClipEdits.MAX_CLIPS && trimBounds(start,end,data.seconds)!=null,onClick={
                                    clips=clips+TrimClip(MediaEdits.seconds(start),MediaEdits.seconds(end));selected=clips.lastIndex;message=""
                                }){Text("Add clip")}
                                TextButton(enabled=!busy && clips.isNotEmpty() && selected>=0 && clips.size<ClipEdits.MAX_CLIPS,onClick={selected=-1;start="0";end=trimTime(kotlin.math.floor(data.seconds*1000)/1000)}){Text("New clip")}
                            }
                            if(clips.isNotEmpty() && selected==-1) {
                                Text("Add this selection to the clip list, or return to an existing clip before export.")
                                TextButton(onClick={select(clips.lastIndex)}){Text("Discard new selection")}
                            }
                            clips.forEachIndexed {index,clip ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        TextButton(onClick={select(index)}){Text("Clip ${index+1}${if(selected==index)" · selected" else ""}: ${trimTime(clip.start)} – ${trimTime(clip.end)}")}
                                        Row {
                                            TextButton(enabled=index>0,onClick={
                                                val values=clips.toMutableList()
                                                java.util.Collections.swap(values,index,index-1)
                                                clips=values
                                                select(index-1)
                                            }){Text("Up")}
                                            TextButton(enabled=index<clips.lastIndex,onClick={
                                                val values=clips.toMutableList()
                                                java.util.Collections.swap(values,index,index+1)
                                                clips=values
                                                select(index+1)
                                            }){Text("Down")}
                                            TextButton(onClick={
                                                clips=clips.filterIndexed{i,_->i!=index}
                                                if(clips.isNotEmpty())select(index.coerceAtMost(clips.lastIndex)) else selected=-1
                                            }){Text("Remove clip ${index+1}")}
                                        }
                                    }
                                }
                            }
                            if(clips.isNotEmpty())Text("${trimTime(clips.sumOf{it.seconds})} seconds selected · clips join in the order shown. Selecting a clip lets you adjust its times.")
                            if(multi)Text("Multiple clips export as one H.264/AAC MP4. Video is re-encoded for consistent cuts; audio is re-encoded when present.")
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        EditOperation.entries.forEach {option ->FilterChip(selected=operation==option,onClick={operation=option},enabled=!busy && (option!=EditOperation.Audio || info?.audio==true),label={Text(option.label)})}
                    }
                    Box {
                        OutlinedButton(enabled=!busy && info!=null && !multi,onClick={menu=true}){Text(preset.label)}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            available.forEach {option ->DropdownMenuItem(enabled=encoders!=null && option.encoders.all{it in encoders.orEmpty()},text={Text(option.label+if(encoders!=null && !option.encoders.all{it in encoders.orEmpty()})" · unavailable" else "")},onClick={preset=option;menu=false})}
                        }
                    }
                    if(!preset.copies && preset !in setOf(EditPreset.Flac,EditPreset.Wav)) {
                        Text("Output quality",fontWeight=FontWeight.SemiBold)
                        EditQuality.entries.forEach {option->FilterChip(selected=quality==option,onClick={quality=option},enabled=!busy,label={Text(option.label)})}
                        Text(if(preset==EditPreset.Mp4H264)"H.264 CRF ${quality.crf} · AAC ${quality.aac} kbps" else if(preset==EditPreset.Mp3)"MP3 ${quality.mp3} kbps" else "AAC ${quality.aac} kbps")
                    }
                    Text(if(preset.copies)"Stream-copy cuts follow packet/keyframe boundaries and may differ from the preview. Choose H.264 + AAC for more precise video cuts." else "Encoding cannot restore lost source detail. Quality and codec changes are applied at export. FLAC/WAV may be larger; WAV uses 16-bit PCM.",style=MaterialTheme.typography.bodySmall)
                    Text("Creates a new file in your download folder. Original stays unchanged. Uses the first video/audio tracks; extra tracks, chapters and metadata are excluded. Interrupted exports restart.",style=MaterialTheme.typography.bodySmall)
                    Text("Working space: ${info?.bytes?.let(StorageBudget::required)?.let{sizeLabel(it)+" estimated"}?:"estimate unavailable"}. Repeated clips and encoded files may need more.",style=MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                if(message.isNotBlank())Text(message,modifier=Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)
                Button(modifier=Modifier.fillMaxWidth().padding(16.dp),enabled=valid && !busy,onClick={
                    runCatching {if(multi)ClipEdits.prepare(source,info!!,clips,quality,Store.folder.value) else MediaEdits.prepare(source,info!!,operation,preset,quality,start,end,Store.folder.value)}
                        .onSuccess{confirm=it}.onFailure{message=it.message?:"Check the selected clips and format."}
                }){Text("Review export")}
            }
        }
        val job=confirm
        if(job!=null)AlertDialog(onDismissRequest={if(!busy)confirm=null},title={Text("Create a new media file?")},text={Text("${job.label}\n${if(job.editSegments.isNotBlank())"${clips.size} selected clips will merge into one MP4 in list order." else operation.label}\n${if(preset.copies)"Source encoding is copied." else "Re-encoding can change quality."}\nYour original stays unchanged. The result appears in Library.")},
            confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{
                try {withContext(Dispatchers.IO){MediaEdits.enqueue(context,job)};confirm=null;onQueued()}
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to queue export.";confirm=null}finally{busy=false}
            }}){Text("Create new file")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=null}){Text("Keep browsing")}})
    }
}
