package com.flux.downloader

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

enum class EditOperation(val label:String) { Trim("Trim clip"), Audio("Extract audio"), Convert("Convert format") }
enum class EditQuality(val label:String,val crf:Int,val aac:Int,val mp3:Int) {
    High("Higher quality",18,256,320), Balanced("Balanced",23,192,192), Small("Smaller file",28,128,128)
}
enum class EditPreset(val label:String,val extension:String,val mime:String,val audioOnly:Boolean,val encoders:Set<String> = emptySet()) {
    Mkv("MKV · copy original streams","mkv","video/x-matroska",false),
    Mp4Copy("MP4 · copy compatible streams","mp4","video/mp4",false),
    Mp4H264("MP4 · H.264 + AAC","mp4","video/mp4",false,setOf("libx264","aac")),
    OriginalAudio("MKA · copy original audio","mka","audio/x-matroska",true),
    Aac("M4A · AAC","m4a","audio/mp4",true,setOf("aac")),
    Mp3("MP3","mp3","audio/mpeg",true,setOf("libmp3lame")),
    Flac("FLAC","flac","audio/flac",true,setOf("flac")),
    Wav("WAV · 16-bit PCM","wav","audio/wav",true,setOf("pcm_s16le"));
    val copies get()=encoders.isEmpty()
}
data class EditSource(val video:Boolean,val audio:Boolean,val seconds:Double,val bytes:Long)

object MediaEdits {
    val execution=Mutex()
    suspend fun awaitIdle(){execution.withLock {}}
    fun seconds(value:String):Double {
        require(value.matches(Regex("[0-9]{1,6}(:[0-5][0-9]){0,2}(\\.[0-9]{1,3})?"))) {"Use seconds or HH:MM:SS, with up to three decimal places."}
        val parts=value.split(':').map(String::toDouble)
        val result=parts.fold(0.0){a,b->a*60+b}
        require(result.isFinite() && result in 0.0..604800.0){"Choose a time within seven days."};return result
    }
    fun probe(context:Context,source:Download):EditSource {
        require(source.state=="Complete" && !source.isPhoto && source.uri.startsWith("content://")){"Choose a saved audio or video file."}
        val uri=Uri.parse(source.uri);var bytes=0L
        context.contentResolver.openAssetFileDescriptor(uri,"r")?.use {bytes=it.length.coerceAtLeast(0)}?:error("File access is unavailable. Restore its folder access.")
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(context,uri,null)
            val tracks=(0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video=tracks.any {it.getString("mime").orEmpty().startsWith("video/")}
            val audio=tracks.any {it.getString("mime").orEmpty().startsWith("audio/")}
            require(video || audio){"Android could not inspect this source's audio/video tracks."}
            val duration=tracks.mapNotNull {if(it.containsKey("durationUs"))it.getLong("durationUs") else null}.maxOrNull()?.div(1000000.0)?:source.duration.toDouble()
            return EditSource(video,audio,duration,bytes)
        } finally {extractor.release()}
    }
    fun presets(source:EditSource,operation:EditOperation)=EditPreset.entries.filter {
        if(operation==EditOperation.Audio || !source.video)it.audioOnly && source.audio else !it.audioOnly
    }
    fun prepare(source:Download,info:EditSource,operation:EditOperation,preset:EditPreset,quality:EditQuality,start:String,end:String,folder:String):Download {
        require(source.state=="Complete" && !source.isPhoto && source.uri.startsWith("content://"))
        require(preset in presets(info,operation)){"This output does not match the selected media."}
        val from=if(operation==EditOperation.Trim)seconds(start) else 0.0
        val until=if(operation==EditOperation.Trim)seconds(end) else 0.0
        if(operation==EditOperation.Trim) {
            require(until>from){"End time must be after the start time."}
            require(info.seconds<=0 || until<=info.seconds+0.1){"End time exceeds the source duration."}
        }
        return Download(url="",title="${source.title} · ${operation.label}",thumbnail=source.thumbnail,selector="local-edit",label=preset.label,audioOnly=preset.audioOnly,
            duration=if(operation==EditOperation.Trim)kotlin.math.ceil(until-from).toLong() else kotlin.math.ceil(info.seconds).toLong(),folder=folder,
            detail="Local edit queued · original file kept",expectsAudio=info.audio,collection=source.collection,tags=source.tags,
            editOperation=operation.name,editPreset=preset.name,editQuality=quality.name,editSourceUri=source.uri,editSourceFolder=source.folder,
            editStart=from,editEnd=until,expectedBytes=info.bytes)
    }
    fun enqueue(context:Context,job:Download) {
        require(job.isLocalEdit);Store.add(job.copy(state=if(Store.queuePaused.value)"Paused" else "Queued",queueOrder=Store.nextQueueOrder()))
        if(!Store.queuePaused.value)kick(context)
    }
    fun kick(context:Context) {
        val work=OneTimeWorkRequestBuilder<MediaEditWorker>().addTag("flux-media-tools").build()
        WorkManager.getInstance(context).enqueueUniqueWork("flux-media-tools",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
    }
    fun arguments(job:Download,input:File,output:File):List<String> {
        val preset=EditPreset.valueOf(job.editPreset);val quality=EditQuality.valueOf(job.editQuality)
        val operation=EditOperation.valueOf(job.editOperation)
        require(job.editStart.isFinite() && job.editEnd.isFinite() && job.editStart>=0 && job.editEnd<=604800)
        if(operation==EditOperation.Trim)require(job.editEnd>job.editStart)
        fun time(value:Double)=String.format(Locale.US,"%.3f",value)
        return buildList {
            addAll(listOf("-hide_banner","-nostdin","-y","-loglevel","error","-progress","pipe:1","-nostats","-protocol_whitelist","file,pipe","-format_whitelist","mov,matroska,webm,mp3,aac,flac,ogg,wav","-i",input.absolutePath))
            if(operation==EditOperation.Trim)addAll(listOf("-ss",time(job.editStart),"-t",time(job.editEnd-job.editStart)))
            addAll(listOf("-map_metadata","-1","-map_chapters","-1"))
            if(preset.audioOnly)addAll(listOf("-vn","-map","0:a:0")) else addAll(listOf("-map","0:v:0","-map","0:a:0?"))
            when(preset) {
                EditPreset.Mkv,EditPreset.Mp4Copy,EditPreset.OriginalAudio ->addAll(listOf("-c","copy"))
                EditPreset.Mp4H264 ->addAll(listOf("-c:v","libx264","-preset","veryfast","-crf",quality.crf.toString(),"-pix_fmt","yuv420p","-vf","pad=ceil(iw/2)*2:ceil(ih/2)*2","-c:a","aac","-b:a","${quality.aac}k","-threads","2"))
                EditPreset.Aac ->addAll(listOf("-c:a","aac","-b:a","${quality.aac}k"))
                EditPreset.Mp3 ->addAll(listOf("-c:a","libmp3lame","-b:a","${quality.mp3}k"))
                EditPreset.Flac ->addAll(listOf("-c:a","flac"))
                EditPreset.Wav ->addAll(listOf("-c:a","pcm_s16le"))
            }
            if(preset in setOf(EditPreset.Mp4Copy,EditPreset.Mp4H264,EditPreset.Aac))addAll(listOf("-movflags","+faststart"))
            addAll(listOf("-avoid_negative_ts","make_zero",output.absolutePath))
        }
    }
}

object NativeMedia {
    private var cached:Set<String>?=null
    private val lock=Mutex()
    suspend fun encoders(context:Context):Set<String> =lock.withLock {
        cached?.let{return@withLock it}
        Engine.initialize(context)
        val output=StringBuilder()
        withTimeout(20000){run(context,listOf("-hide_banner","-encoders"),{}, {line ->if(output.length<200000)output.appendLine(line)})}
        output.lines().mapNotNull {it.trim().split(Regex("\\s+")).getOrNull(1)}.toSet().also {cached=it}
    }
    suspend fun run(context:Context,args:List<String>,control:suspend ()->Unit,line:(String)->Unit)=withContext(Dispatchers.IO) {
        coroutineScope {
            control();currentCoroutineContext().ensureActive()
            val builder=ProcessBuilder(listOf(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").absolutePath)+args).redirectErrorStream(true)
            val packages=File(context.noBackupFilesDir,"youtubedl-android/packages")
            builder.environment()["LD_LIBRARY_PATH"]="${File(packages,"ffmpeg/usr/lib")}:${File(packages,"python/usr/lib")}:${context.applicationInfo.nativeLibraryDir}"
            val process=builder.start()
            val reader=async(Dispatchers.IO) {process.inputStream.bufferedReader().use {input ->while(true){val text=input.readLine()?:break;line(text.take(4096))}}}
            try {
                while(process.isAlive || !reader.isCompleted){control();delay(100)}
                reader.await();control()
                check(process.waitFor()==0){"The media engine could not create this output. Try MKV or a different format."}
            } finally {
                withContext(NonCancellable) {
                    if(process.isAlive)process.destroyForcibly()
                    process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)
                    runCatching {process.inputStream.close()}
                    reader.cancelAndJoin()
                }
            }
        }
    }
}
