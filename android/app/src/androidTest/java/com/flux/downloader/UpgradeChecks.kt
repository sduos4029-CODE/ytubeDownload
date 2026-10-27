package com.flux.downloader

import android.app.Instrumentation
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.work.WorkManager
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.yausername.youtubedl_android.YoutubeDL

class UpgradeChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    suspend fun run(restart:Boolean=false) {
        Store.init(app)
        if(restart) {
            val saved=Store.downloads.value.first {it.id=="upgrade-verified"}
            check(saved.collection=="Travel" && saved.tags=="trip, Hindi" && saved.verification.contains("verified"))
            check(LibraryDetails.inspect(app,saved).name!!.startsWith("Flux trip 001"))
            check(UpgradeSettings.preferences.value.language=="hi" && UpgradeSettings.preferences.value.chargingOnly)
            check(Store.downloads.value.first {it.id=="upgrade-future"}.scheduledAt>System.currentTimeMillis())
            UpgradeSettings.set(UpgradePreferences());Store.setTransfers(TransferPreferences());ChargingGate.simulate(null)
            report("PASS: renamed real file, collection/tags, verification, language/power preferences and per-item schedule survive process restart")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get();withTimeout(60000){Downloads.awaitIdle()}
        Store.setQueuePaused(true);UpgradeSettings.set(UpgradePreferences())
        val base=Download(id="upgrade-future",url="https://www.youtube.com/watch?v=Upgraded01",title="Future item",thumbnail="",selector="best",label="source",audioOnly=false,duration=6,folder="",scheduledAt=System.currentTimeMillis()+3600000)
        check(QueueRules.next(listOf(base),System.currentTimeMillis())==null)
        check(QueueRules.next(listOf(base),base.scheduledAt)==base)
        Store.add(base.copy(state="Paused"))
        check(StorageBudget.required(100)==StorageBudget.RESERVE+300)
        check(StorageBudget.required(0)==null && StorageBudget.required(Long.MAX_VALUE)==null)
        UpgradeSettings.set(UpgradePreferences("hi",0,true));ChargingGate.simulate(false);check(!ChargingGate.allowed())
        ChargingGate.simulate(true);check(ChargingGate.allowed());ChargingGate.simulate(null)
        val vf=MediaFormat("v","mp4",360,30,0,100,true,false,"avc1","none")
        val en=MediaFormat("a-en","m4a",0,0,128,20,false,true,"none","mp4a",language="en")
        val hi=en.copy(id="a-hi",language="hi")
        val video=Video("Upgraded01","Language test","Flux","",6,listOf(vf,en,hi))
        check(AudioSelection.preferred(video)?.id=="a-hi")
        check(AudioSelection.preferred(video,"fr")==null)
        val prepared=MediaDownloads.prepare(video,"");check(prepared.selector=="v+a-hi" && prepared.expectedBytes==120L && prepared.expectsAudio)
        check(MediaDownloads.prepare(video,"",vf,honourPreference=false).selector.contains("bestaudio"))
        check(Download.from(prepared.json())==prepared)
        val old=prepared.json().apply {listOf("scheduledAt","expectedBytes","expectsAudio","subtitleLanguage","verification","collection","tags").forEach {remove(it)}}
        check(Download.from(old).scheduledAt==0L && Download.from(old).collection.isBlank())
        report("PASS: audio-language selection/fallback, combined source-space budget with unknown/overflow estimates, charging gate, schedule due-time rules and legacy job migration")
        val playlist=JSONObject("""{"entries":[{"id":"Playlist01","title":"First"},{"id":"Playlist01","title":"Duplicate"},{"id":"Private001","availability":"private"},{"id":"Playlist02","title":"Second"}]}""")
        val parsed=PlaylistSelection.parse(playlist)
        check(parsed.size==2 && parsed.map {it.itemIndex}==listOf(1,2) && parsed.all {it.itemCount==2 && it.url.startsWith("https://www.youtube.com/watch?v=")})
        check(PlaylistSelection.isPlaylist("https://www.youtube.com/playlist?list=PL1234567890"))
        check(!PlaylistSelection.isPlaylist("https://evil.example/playlist?list=PL1234567890"))
        check(!PlaylistSelection.isPlaylist("https://www.youtube.com/watch?v=Playlist01&list=PL1234567890"))
        val subtitles=JSONObject("""{"subtitles":{"en":[{"ext":"vtt","url":"https://www.youtube.com/sub"}],"bad":[{"ext":"vtt","url":"http://bad.example/sub"}],"--all":[{"ext":"srt","url":"https://www.youtube.com/sub"}]}}""")
        check(SubtitleTracks.languages(subtitles)==listOf("en"))
        val request=YoutubeDLRequest(video.url);SubtitleTracks.apply(request,"en")
        check(request.getOption("--sub-langs")=="en" && request.hasOption("--embed-subs") && request.getOption("--remux-video")=="mkv")
        check(runCatching{SubtitleTracks.apply(request,"en,all")}.isFailure)
        report("PASS: playlist canonical video selection/dedup/private filtering and manual-subtitle HTTPS/language validation with native embed/remux request options")
        subtitleIntegration()
        val combined=instrumentation.context.assets.open("combined.mp4").use {it.readBytes()}
        val a=persist("Upgrade original A.mp4",combined);val b=persist("Upgrade original B.mp4",combined)
        val silent=persist("Upgrade silent.mp4",instrumentation.context.assets.open("video.mp4").use {it.readBytes()})
        val job=base.copy(id="upgrade-verified",title="Original title",state="Complete",uri=a.toString(),mime="video/mp4",scheduledAt=0,expectsAudio=true)
        val verification=MediaVerification.inspect(app,a,job);check(verification.contains("Video + audio"))
        check(runCatching{MediaVerification.inspect(app,silent,job)}.isFailure)
        val malformed=persist("Malformed.mp4","<html>not video</html>".toByteArray())
        check(runCatching{MediaVerification.inspect(app,malformed,job)}.isFailure)
        Store.add(job.copy(verification=verification));val duplicate=job.copy(id="upgrade-duplicate",uri=b.toString());Store.add(duplicate)
        val groups=LibraryOrganisation.duplicates(app,listOf(job,duplicate,job.copy(id="silent",uri=silent.toString()))){_,_->}
        check(groups.single().toSet()==setOf(job.id,duplicate.id))
        LibraryOrganisation.set(setOf(job.id),"Travel","trip, Hindi, trip")
        val organised=Store.downloads.value.first {it.id==job.id}
        check(libraryJobs(listOf(organised),"Hindi","All",LibrarySort.Newest)==listOf(organised))
        val plan=LibraryOrganisation.preview(app,listOf(organised),"Flux trip")
        check(plan.single().next.endsWith(".mp4") && plan.single().next.startsWith("Flux trip 001"))
        val renamed=LibraryOrganisation.rename(app,plan);check(renamed==(1 to 0))
        check(app.contentResolver.openInputStream(Uri.parse(Store.downloads.value.first{it.id==job.id}.uri))!!.use{it.readBytes()}.contentEquals(combined))
        report("PASS: real MediaStore track/sample verification and missing-audio rejection, SHA-256 duplicate detection, searchable metadata and previewed actual file rename preserve byte-identical media")
        scheduleIntegration(combined)
        val backup=UpgradeSettings.backup();val json=JSONObject(backup)
        check(!backup.contains("token",true) && !backup.contains("https://") && !backup.contains("cookie",true) && !backup.contains("startAt"))
        val before=UpgradeSettings.preferences.value
        check(runCatching{UpgradeSettings.restore(app,JSONObject(backup).put("serverKey","must-not-restore").toString())}.isFailure)
        check(UpgradeSettings.preferences.value==before)
        check(runCatching{UpgradeSettings.restore(app,JSONObject(backup).put("layout","Invalid").toString())}.isFailure)
        check(UpgradeSettings.preferences.value==before)
        UpgradeSettings.restore(app,json.put("parallel",2).put("layout","Grid").toString())
        check(Store.transfers.value.parallel==2 && Store.library.value.layout==LibraryLayout.Grid)
        Store.setLibrary(LibraryPreferences());Store.setTransfers(TransferPreferences())
        UpgradeSettings.set(UpgradePreferences("hi",System.currentTimeMillis()+3600000,true))
        report("PASS: allowlisted preference export/restore excludes credentials, addresses, folder grants and schedules; invalid backups cannot partially change preferences")
    }
    private suspend fun scheduleIntegration(bytes:ByteArray) {
        UpgradeSettings.set(UpgradePreferences("hi"));Store.setTransfers(TransferPreferences())
        val server=ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));val pool=Executors.newCachedThreadPool();val requests=AtomicInteger()
        pool.execute {while(!server.isClosed)try {
            val socket=server.accept();pool.execute {runCatching {socket.use {connection ->
                val reader=connection.getInputStream().bufferedReader();val method=reader.readLine().substringBefore(' ')
                while(!reader.readLine().isNullOrEmpty()){}
                requests.incrementAndGet()
                connection.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());if(method!="HEAD")write(bytes);flush()}
            }}}
        }catch(_:Exception){}}
        try {
            Store.setQueuePaused(false)
            val timed=Download(id="upgrade-timed",url="http://127.0.0.1:${server.localPort}/media.mp4",title="Scheduled native fixture",thumbnail="",selector="best",label="source",audioOnly=false,duration=6,folder="",scheduledAt=System.currentTimeMillis()+3600000,expectsAudio=true)
            Downloads.enqueue(app,timed)
            delay(2500);check(requests.get()==0)
            Downloads.startNow(app,Store.downloads.value.first{it.id==timed.id})
            val done=withTimeout(60000){
                while(true){val job=Store.downloads.value.first{it.id==timed.id};check(job.state!="Failed"){job.detail};if(job.state=="Complete")return@withTimeout job;delay(100)}
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            check(done.scheduledAt==0L && done.verification.contains("Video + audio") && requests.get()>0)
            report("PASS: future WorkManager item makes no media request; Start now bypasses the delayed wake and completes real native transfer/save/verification")
        }finally {
            Store.setQueuePaused(true);withTimeout(60000){Downloads.awaitIdle()}
            WorkManager.getInstance(app).cancelAllWork().result.get();server.close();pool.shutdownNow()
        }
    }
    private suspend fun subtitleIntegration()=withContext(Dispatchers.IO) {
        Engine.initialize(app)
        val work=File(app.noBackupFilesDir,"subtitle-integration").apply {mkdirs()}
        try {
            val source=File(work,"source.mp4");instrumentation.context.assets.open("combined.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
            val caption=File(work,"source.vtt").apply {writeText("WEBVTT\n\n00:00:00.000 --> 00:00:02.000\nFlux subtitle integration\n")}
            val info=File(work,"info.json").apply {writeText(JSONObject().put("id","Subtitle01").put("title","Subtitle fixture").put("extractor","generic").put("extractor_key","Generic").put("webpage_url","https://www.youtube.com/watch?v=Subtitle01").put("duration",6)
                .put("formats",JSONArray().put(JSONObject().put("format_id","source").put("url",source.toURI().toString()).put("protocol","file").put("ext","mp4").put("vcodec","avc1").put("acodec","mp4a.40.2")))
                .put("subtitles",JSONObject().put("en",JSONArray().put(JSONObject().put("ext","vtt").put("url",caption.toURI().toString())))).toString())}
            val request=Engine.secureOptions(YoutubeDLRequest(emptyList<String>())).apply {
                addOption("--load-info-json",info.absolutePath);addOption("--enable-file-urls") // Only generated local test media.
                addOption("--format","source");addOption("--output",File(work,"output.%(ext)s").absolutePath)
            }
            SubtitleTracks.apply(request,"en")
            YoutubeDL.getInstance().execute(request)
            val output=File(work,"output.mkv");check(output.isFile)
            check(output.readBytes().toString(Charsets.ISO_8859_1).contains("Flux subtitle integration")) {"Caption text was not embedded in the generated MKV"}
            report("PASS: native yt-dlp/FFmpeg embeds generated VTT captions into MKV without re-encoding source video/audio")
        }finally {work.deleteRecursively()}
    }
    private fun persist(name:String,bytes:ByteArray):Uri {
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/FluxTests")
        })!!
        app.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)};return uri
    }
}
