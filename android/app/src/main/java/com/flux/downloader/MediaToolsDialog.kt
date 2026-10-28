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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var preset by remember{mutableStateOf(if(source.audioOnly)EditPreset.OriginalAudio else EditPreset.Mp4H264)}
    var quality by remember{mutableStateOf(EditQuality.High)}
    var start by remember{mutableStateOf("0")};var end by remember{mutableStateOf(source.duration.takeIf{it>0}?.toString().orEmpty())}
    var clips by remember{mutableStateOf<List<TrimClip>>(emptyList())};var selected by remember{mutableStateOf(-1)}
    var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var menu by remember{mutableStateOf(false)};var confirm by remember{mutableStateOf<Download?>(null)}
    val playback=remember(source.id){TrimPlayback()}
    val scroll=rememberScrollState()
    var advanced by remember{mutableStateOf(false)}
    var joining by remember{mutableStateOf(false)}
    var undoClips by remember{mutableStateOf<List<TrimClip>?>(null)}
    fun nextRange() {
        selected=-1
        val total=info?.seconds?:0.0
        val last=clips.lastOrNull()?.end?:0.0
        start=trimTime(if(total-last>=0.1)last else 0.0)
        end=trimTime(kotlin.math.floor(total*1000)/1000)
    }
    fun select(index:Int) {
        selected=index;start=trimTime(clips[index].start);end=trimTime(clips[index].end);message=""
        scope.launch{scroll.animateScrollTo(0)}
    }
    fun range(from:String,until:String) {start=from;end=until;message=""}
    fun move(index:Int,delta:Int) {
        val target=index+delta
        clips=clips.toMutableList().apply{java.util.Collections.swap(this,index,target)}
        if(selected==index)selected=target else if(selected==target)selected=index
        undoClips=null
    }
    LaunchedEffect(source.id) {
        try {
            val data=withContext(Dispatchers.IO){MediaEdits.probe(context,source)};info=data
            if(data.seconds>0)end=trimTime(kotlin.math.floor(data.seconds*1000)/1000)
            encoders=NativeMedia.encoders(context)
        }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to inspect this file."}
    }
    val multi=operation==EditOperation.Trim && joining && clips.size>=2
    val available=info?.let{MediaEdits.presets(it,operation)}.orEmpty()
    LaunchedEffect(operation,info,multi){if(multi)preset=EditPreset.Mp4H264 else if(preset !in available && available.isNotEmpty())preset=if(EditPreset.Mp4H264 in available)EditPreset.Mp4H264 else available.first()}
    val rangeValid=trimBounds(start,end,info?.seconds?:0.0)!=null
    val listMode=operation==EditOperation.Trim && joining && clips.isNotEmpty()
    val editing=operation==EditOperation.Trim && joining && selected>=0
    val valid=info!=null && !editing && (!multi || EditPreset.Mp4H264.encoders.all{it in encoders.orEmpty()}) &&
        (operation!=EditOperation.Trim || if(joining)listMode else rangeValid) && encoders!=null && preset in available && preset.encoders.all{it in encoders.orEmpty()}
    val canSave=rangeValid && runCatching{MediaEdits.seconds(end)-MediaEdits.seconds(start)+1e-9>=0.1}.getOrDefault(false)
    fun review() {
        runCatching {
            if(multi)ClipEdits.prepare(source,info!!,clips,quality,Store.folder.value)
            else {
                val clip=if(listMode)clips.single() else null
                MediaEdits.prepare(source,info!!,operation,preset,quality,clip?.let{trimTime(it.start)}?:start,clip?.let{trimTime(it.end)}?:end,Store.folder.value)
            }
        }.onSuccess{confirm=it}.onFailure{message=it.message?:"Check the selected clips and format."}
    }
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)){Text("Video editor",style=MaterialTheme.typography.titleLarge);Text(source.title,maxLines=1,style=MaterialTheme.typography.bodySmall)}
                    TextButton(enabled=!busy,onClick=onDismiss){Text("Close media tools")}
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    val data=info
                    if(operation==EditOperation.Trim && data?.video==true) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=!joining,enabled=!editing,onClick={joining=false;selected=-1;message=""},label={Text("Trim one video")})
                            FilterChip(selected=joining,enabled=!editing,onClick={joining=true;selected=-1;message=""},label={Text("Join clips")})
                        }
                        Text(if(joining)"1. Choose a range  2. Add clip  3. Repeat, then export" else "1. Drag Start / End  2. Play to check  3. Export",fontWeight=FontWeight.SemiBold)
                        Text("Keep the selected part. Everything outside it is removed from the new video.",style=MaterialTheme.typography.bodySmall)
                    }
                    if(data!=null) {
                        val from=if(operation==EditOperation.Trim)start else "0"
                        val until=if(operation==EditOperation.Trim)end else trimTime(kotlin.math.floor(data.seconds*1000)/1000)
                        TrimPreview(source,data,from,until,active=confirm==null && !busy,showTrim=operation==EditOperation.Trim,controls=playback,onRange=::range)
                    } else if(message.isBlank())LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(operation==EditOperation.Trim) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value=start,onValueChange={range(it.take(24),end)},modifier=Modifier.weight(1f),label={Text("Start time")},singleLine=true,enabled=!busy)
                            OutlinedTextField(value=end,onValueChange={range(start,it.take(24))},modifier=Modifier.weight(1f),label={Text("End time")},singleLine=true,enabled=!busy)
                        }
                        Text("Seconds or HH:MM:SS · millisecond precision",style=MaterialTheme.typography.bodySmall)
                        if(data?.video==true && joining) {
                            Text("Your clips · ${clips.size} / ${ClipEdits.MAX_CLIPS}",fontWeight=FontWeight.Bold)
                            if(clips.isEmpty())Text("Choose a range, then tap Add clip. Repeat to build one video.",style=MaterialTheme.typography.bodySmall)
                            else {
                                Text("Only added clips export. Tap to adjust; swipe to see more.",style=MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    clips.forEachIndexed {index,clip ->
                                        OutlinedCard(onClick={select(index)},modifier=Modifier.width(220.dp).semantics{contentDescription="Edit clip ${index+1}"},
                                            colors=CardDefaults.outlinedCardColors(containerColor=if(selected==index)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                                            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                                Text("Clip ${index+1}${if(selected==index)" · editing" else ""}",fontWeight=FontWeight.SemiBold)
                                                Text("${trimTime(clip.start)} → ${trimTime(clip.end)}",style=MaterialTheme.typography.bodySmall)
                                                Text("${trimTime(clip.seconds)} seconds",style=MaterialTheme.typography.bodySmall)
                                                Row {
                                                    TextButton(enabled=!busy && index>0,onClick={move(index,-1)},modifier=Modifier.semantics{contentDescription="Move clip ${index+1} earlier"}){Text("←")}
                                                    TextButton(enabled=!busy && index<clips.lastIndex,onClick={move(index,1)},modifier=Modifier.semantics{contentDescription="Move clip ${index+1} later"}){Text("→")}
                                                    TextButton(enabled=!busy,onClick={
                                                        undoClips=clips
                                                        clips=clips.filterIndexed{i,_->i!=index}
                                                        if(selected==index)nextRange() else if(selected>index)selected--
                                                    },modifier=Modifier.semantics{contentDescription="Remove clip ${index+1}"}){Text("Remove")}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if(undoClips!=null)TextButton(onClick={clips=undoClips!!;undoClips=null;nextRange()}){Text("Undo removal")}
                            if(multi)Text("All added clips merge into one MP4 with source audio when present.",style=MaterialTheme.typography.bodySmall)

                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        EditOperation.entries.forEach {option ->FilterChip(selected=operation==option,onClick={operation=option},enabled=!busy && (option!=EditOperation.Audio || info?.audio==true),label={Text(option.label)})}
                    }
                    TextButton(onClick={advanced=!advanced}){Text(if(advanced)"Hide export settings" else "Output format & quality")}
                    if(advanced) {
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

                }
                HorizontalDivider()
                if(message.isNotBlank())Text(message,modifier=Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)
                if(info!=null && encoders==null)Text("Preparing export tools…",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                if(info!=null)TextButton(enabled=playback.canPlay && !busy,onClick={playback.toggle()},modifier=Modifier.fillMaxWidth()) {
                    Text(if(playback.playing)"Pause preview" else "Play selected clip")
                }
                if(editing)Text("Clip ${selected+1} · $start → $end",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                else if(listMode)Text("Export: ${clips.size} clips · ${trimTime(clips.sumOf{it.seconds})} seconds",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                else if(operation==EditOperation.Trim && rangeValid)Text("Keep $start → $end · ${trimTime(MediaEdits.seconds(end)-MediaEdits.seconds(start))} seconds",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                if(operation==EditOperation.Trim && joining && clips.isEmpty())Text("Add your first clip to enable export.",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(operation==EditOperation.Trim && info?.video==true && joining) {
                        OutlinedButton(modifier=Modifier.weight(1f),enabled=!busy && canSave && (editing || clips.size<ClipEdits.MAX_CLIPS),onClick={
                            val clip=TrimClip(MediaEdits.seconds(start),MediaEdits.seconds(end))
                            if(editing)clips=clips.toMutableList().apply{this[selected]=clip} else clips=clips+clip
                            undoClips=null;nextRange();message=""
                        }){Text(if(editing)"Save clip" else "Add clip")}
                    }
                    if(editing)TextButton(modifier=Modifier.weight(1f),enabled=!busy,onClick={nextRange();message=""}){Text("Cancel clip edit")}
                    else Button(modifier=Modifier.weight(1f),enabled=valid && !busy,onClick=::review){Text(if(operation==EditOperation.Trim && !joining)"Export trimmed video" else "Review export")}
                }

            }
        }
        val job=confirm
        val singleTrim=operation==EditOperation.Trim && !joining
        if(job!=null)AlertDialog(onDismissRequest={if(!busy)confirm=null},title={Text(if(singleTrim)"Save trimmed video?" else "Create a new media file?")},text={Text("${if(singleTrim)"Keep ${trimTime(job.editStart)} → ${trimTime(job.editEnd)} seconds\nNew duration: ${trimTime(job.editEnd-job.editStart)} seconds\n" else ""}${job.label}\n${if(job.editSegments.isNotBlank())"${clips.size} selected clips will merge into one MP4 in list order." else operation.label}\n${if(preset.copies)"Source encoding is copied." else "Re-encoding can change quality."}\nYour original stays unchanged. The result appears in Library.\n${if(Store.queuePaused.value)"Your queue is paused. Resume it in Downloads to start trimming." else "Trimming starts in Downloads; you can follow its progress there."}")},
            confirmButton={TextButton(enabled=!busy,onClick={busy=true;scope.launch{
                try {withContext(Dispatchers.IO){MediaEdits.enqueue(context,job)};confirm=null;onQueued()}
                catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to queue export.";confirm=null}finally{busy=false}
            }}){Text(if(singleTrim)"Trim & save" else "Create new file")}},dismissButton={TextButton(enabled=!busy,onClick={confirm=null}){Text("Keep editing")}})
    }
}
