package com.flux.downloader

import android.app.Instrumentation
import android.content.ContentValues
import android.media.MediaExtractor
import android.net.Uri
import android.provider.MediaStore
import androidx.work.WorkManager
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class MediaToolsChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    suspend fun run(restart:Boolean=false) {
        Store.init(app)
        if(restart) {
            val saved=Store.downloads.value.first {it.id=="media-tools-saved"}
            check(saved.isLocalEdit && saved.editPreset==EditPreset.Aac.name && saved.state=="Complete")
            check(saved.url.isBlank() && RepeatDownloads.sourceKey(saved.url)==null)
            check(LibraryDetails.inspect(app,saved).available && tracks(saved.uri).all {it.startsWith("audio/")})
            val pending=Store.downloads.value.first {it.id=="media-tools-paused"}
            check(pending.state=="Paused" && pending.editOperation==EditOperation.Trim.name && pending.editEnd==2.0)
            check(QueueRules.next(listOf(pending.copy(state="Queued")),System.currentTimeMillis())==null)
            Downloads.cancel(app,pending);Store.setTransfers(TransferPreferences());NetworkGate.testWifi(null)
            report("PASS: local export identity/file/quality and paused trim source/range survive force-stop; local jobs cannot enter the network downloader")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get();Downloads.awaitIdle();MediaEdits.awaitIdle()
        Store.setQueuePaused(true);UpgradeSettings.set(UpgradePreferences());Engine.initialize(app)
        val bytes=instrumentation.context.assets.open("combined.mp4").use{it.readBytes()}
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"Flux media tools source.mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/FluxTests")
        })!!
        app.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        val source=Download(id="media-tools-source",url="",title="Flux media tools source",thumbnail="",selector="fixture",label="source",audioOnly=false,duration=6,folder="",state="Complete",uri=uri.toString(),mime="video/mp4",bytes=bytes.size.toLong(),collection="Edits",tags="fixture")
        Store.add(source)
        val before=hash(uri);val info=MediaEdits.probe(app,source)
        check(info.video && info.audio && info.seconds>0)
        check(MediaEdits.seconds("00:01:02.250")==62.25)
        for(invalid in listOf("-1","NaN","1;rm","00:99:00","http://x"))check(runCatching{MediaEdits.seconds(invalid)}.isFailure)
        check(runCatching{MediaEdits.prepare(source,info,EditOperation.Trim,EditPreset.Mkv,EditQuality.Balanced,"3","2","")}.isFailure)
        val encoders=NativeMedia.encoders(app)
        check("aac" in encoders && "pcm_s16le" in encoders)
        report("PASS: bundled native media engine starts, source tracks/duration probe correctly, invalid trim values are rejected and available encoders are discovered")
        Store.setTransfers(TransferPreferences(1,true));NetworkGate.testWifi(false);Store.setQueuePaused(false)
        val trim=MediaEdits.prepare(source,info,EditOperation.Trim,EditPreset.Mkv,EditQuality.Balanced,"0","2","")
        check(Download.from(trim.json())==trim && QueueRules.next(listOf(trim),System.currentTimeMillis())==null)
        MediaEdits.enqueue(app,trim);val trimmed=await(trim.id)
        val trimmedInfo=MediaEdits.probe(app,trimmed)
        check(trimmedInfo.video && trimmedInfo.audio && trimmedInfo.seconds in 0.5..3.5)
        check(trimmed.uri!=source.uri && trimmed.verification.contains("Video + audio"))
        report("PASS: real offline WorkManager trim copies source streams into a new MKV, saves/verifies both tracks and limits duration without modifying the original")
        val audio=MediaEdits.prepare(source,info,EditOperation.Audio,EditPreset.Aac,EditQuality.High,"","","")
        MediaEdits.enqueue(app,audio);val extracted=await(audio.id)
        check(extracted.audioOnly && extracted.mime=="audio/mp4" && tracks(extracted.uri).single().startsWith("audio/"))
        Store.add(extracted.copy(id="media-tools-saved"))
        val copy=MediaEdits.prepare(source,info,EditOperation.Convert,EditPreset.Mp4Copy,EditQuality.Balanced,"","","")
        MediaEdits.enqueue(app,copy);check(tracks(await(copy.id).uri).any{it.startsWith("video/")})
        for(preset in listOf(EditPreset.OriginalAudio,EditPreset.Mp3,EditPreset.Flac,EditPreset.Wav,EditPreset.Mp4H264)) {
            if(!preset.encoders.all{it in encoders})continue
            val operation=if(preset.audioOnly)EditOperation.Audio else EditOperation.Trim
            val job=MediaEdits.prepare(source,info,operation,preset,EditQuality.Small,"1","2","")
            MediaEdits.enqueue(app,job);val result=await(job.id)
            check(tracks(result.uri).any{it.startsWith(if(preset.audioOnly)"audio/" else "video/")})
            if(preset==EditPreset.Mp4H264)check(MediaEdits.probe(app,result).seconds in 0.5..2.0)
            report("PASS: native ${preset.name} export creates a separate readable file with the expected tracks")
        }
        check(hash(uri)==before)
        val command=MediaEdits.arguments(trim,File("/safe/input.media"),File("/safe/output.mkv"))
        check(command[command.indexOf("-protocol_whitelist")+1]=="file,pipe" && !command.contains("http") && command.contains("-format_whitelist"))
        check(!UpgradeSettings.backup().contains("editSource"))
        cancellation(bytes)
        val paused=MediaEdits.prepare(source,info,EditOperation.Trim,EditPreset.Mkv,EditQuality.Balanced,"0","2","")
        Store.add(paused.copy(id="media-tools-paused",state="Paused"))
        Store.setQueuePaused(true);MediaEdits.awaitIdle();Store.setTransfers(TransferPreferences());NetworkGate.testWifi(null)
        check(File(app.noBackupFilesDir,"download-parts").listFiles().orEmpty().none{it.name in setOf(trim.id,audio.id,copy.id)})
        report("PASS: original bytes remain SHA-256 identical, local-only protocols and fixed argument lists enforced, paused metadata persists and completed export workspaces are removed")
    }
    private suspend fun cancellation(bytes:ByteArray)=coroutineScope {
        val input=File(app.noBackupFilesDir,"media-cancel-fixture.mp4").apply{writeBytes(bytes)}
        val started=AtomicBoolean(false)
        val task=async(Dispatchers.IO){NativeMedia.run(app,listOf("-hide_banner","-nostdin","-loglevel","error","-progress","pipe:1","-re","-stream_loop","-1","-protocol_whitelist","file,pipe","-i",input.absolutePath,"-map","0:v:0","-c","copy","-f","null","-"),{}, {if(it.startsWith("out_time="))started.set(true)})}
        try {
            withTimeout(15000){while(!started.get())delay(20)}
            withTimeout(5000){task.cancelAndJoin()}
            report("PASS: cancelling an active native export stops and joins FFmpeg and its reader before returning")
        }finally{task.cancelAndJoin();input.delete()}
    }
    private suspend fun await(id:String):Download=withTimeout(90000) {
        while(true){val job=Store.downloads.value.first{it.id==id};check(job.state!="Failed"){job.detail};if(job.state=="Complete")return@withTimeout job;delay(50)}
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun tracks(address:String):List<String> {
        val media=MediaExtractor()
        try{media.setDataSource(app,Uri.parse(address),null);return (0 until media.trackCount).map{media.getTrackFormat(it).getString("mime").orEmpty()}}finally{media.release()}
    }
    private fun hash(uri:Uri)=app.contentResolver.openInputStream(uri)!!.use{input->val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().toList()}
}
