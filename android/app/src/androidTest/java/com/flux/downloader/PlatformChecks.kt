package com.flux.downloader

import android.app.Instrumentation
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject

class PlatformChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    companion object {
        const val POST = "https://www.instagram.com/reel/FluxDemo01/"
        fun video(): Video = Video.from(JSONObject("""{"id":"FluxDemo01","extractor_key":"Instagram","title":"Public Instagram fixture","uploader":"QA creator","webpage_url":"https://www.instagram.com/reel/FluxDemo01/","duration":6,"formats":[{"format_id":"video","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},{"format_id":"audio","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128}]}"""),POST)
    }
    suspend fun run(restart: Boolean = false) {
        Store.init(app)
        if(restart) {
            val original=Store.downloads.value.first { it.id=="instagram-saved-fixture" }
            check(MediaLinks.parse(original.url)?.platform==MediaPlatform.Instagram)
            check(LibraryDetails.source(original.url)==POST && RepeatDownloads.sourceKey(original.url)=="instagram:FluxDemo01")
            check(original.favourite && LibraryDetails.inspect(app,original).available)
            report("PASS: Instagram source URL, favourite and saved-file access persist across process restart")
            return
        }
        check(Engine.target("music live")=="ytsearch5:music live")
        check(Engine.target("instagram.com/creator/reel/FluxDemo01/?igsh=tracking")==POST)
        check(Engine.target("HTTPS://WWW.INSTAGRAM.COM/reel/FluxDemo01/")==POST)
        check(Engine.target("HTTPS://WWW.YOUTUBE.COM/watch?v=fixture")=="https://www.youtube.com/watch?v=fixture")
        check(MediaLinks.shared("Watch this: $POST")==POST)
        check(MediaLinks.shared("https://evil.invalid/a $POST")==POST)
        check(MediaLinks.shared("Watch https://youtu.be/fixture")=="https://youtu.be/fixture")
        check(MediaLinks.shared("Profile https://www.instagram.com/creator/")=="https://www.instagram.com/creator/")
        check(MediaLinks.shared("https://evil.invalid/a").isEmpty())
        listOf("http://www.instagram.com/reel/FluxDemo01/","https://www.instagram.com/creator/","https://www.instagram.com/stories/creator/123/","https://www.instagram.com/share/reel/FluxDemo01/","https://www.threads.com/@creator/post/FluxDemo01","https://instagram.com.evil.invalid/reel/FluxDemo01/","https://user@www.instagram.com/reel/FluxDemo01/","https://www.instagram.com:444/reel/FluxDemo01/").forEach { check(runCatching { Engine.target(it) }.isFailure) { it } }
        val video=video()
        check(video.platform==MediaPlatform.Instagram && video.url==POST && video.thumbnail.isEmpty())
        check(video.key!=video.copy(platform=MediaPlatform.YouTube).key)
        check(AudioSelection.plan(video,video.formats.first()).selector=="video+(bestaudio[format_id!*=drc]/bestaudio)")
        val unknown=Video.from(JSONObject("""{"id":"FluxDemo01","extractor_key":"Instagram","formats":[{"format_id":"direct","ext":"mp4","acodec":"none"}]}"""),POST)
        check(unknown.formats.single().video && unknown.formats.single().label=="Source video")
        check(AudioSelection.plan(unknown,unknown.formats.single()).description=="Source reports no audio")
        check(MediaFormat.from(JSONObject("""{"format_id":"portrait","ext":"mp4","width":720,"height":1280,"vcodec":"avc1","acodec":"none"}""")).label=="720x1280")
        check(runCatching { MediaLinks.validateInstagramMetadata(JSONObject("""{"_type":"playlist","entries":[]} """),POST) }.isFailure)
        check(runCatching { MediaLinks.validateInstagramMetadata(JSONObject("""{"formats":[{"ext":"jpg","height":1080}]}"""),POST) }.isFailure)
        check(runCatching { MediaLinks.validateInstagramMetadata(JSONObject("""{"formats":[]} """),POST) }.isFailure)
        MediaLinks.validateInstagramMetadata(JSONObject("""{"id":"FluxDemo01","formats":[{"format_id":"v","ext":"mp4","height":1080,"vcodec":"avc1"}]}"""),POST)
        check(RepeatDownloads.sourceKey(POST)=="instagram:FluxDemo01")
        check(RepeatDownloads.sourceKey("https://youtu.be/FluxDemo01")!="instagram:FluxDemo01")
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.awaitIdle() }
        Store.downloads.value.filterNot { it.terminal }.forEach { old -> Store.change(old.id,true) { it.copy(state="Cancelled") } }
        Store.setQueuePaused(true);Store.setTransfers(TransferPreferences(1,false))
        val saved=LibraryChecks(instrumentation,report).fixture("instagram-saved-fixture").copy(url=POST,title="Instagram saved fixture",favourite=true)
        Store.add(saved)
        val repeat=Downloads.downloadAgain(app,saved.id)
        check(repeat.created && repeat.job.url==POST && repeat.job.state=="Paused")
        Store.change(repeat.job.id,true) { it.copy(state="Cancelled") };Store.removeHistory(setOf(repeat.job.id))
        QueueChecks(instrumentation,report).FixtureServer().use { server ->
            val sourceVideo=video()
            val plan=AudioSelection.plan(sourceVideo,sourceVideo.formats.first())
            val job=Download(url=sourceVideo.url,title="Instagram-model generated transfer",thumbnail="",selector=plan.selector,label="1080p",audioOnly=false,duration=6,folder="",replaceEmbeddedAudio=plan.replaceEmbeddedAudio)
            Store.add(job)
            // The generated local MPD replaces transport only; no live Meta
            // media is downloaded or private access bypass added to release.
            Store.change(job.id,true) { it.copy(url=server.path("platform")) }
            Store.setQueuePaused(false)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
            withTimeout(120000) { while(Store.downloads.value.first { it.id==job.id }.also { check(it.state!="Failed") { it.detail } }.state!="Complete")delay(100) }
            val result=Store.downloads.value.first { it.id==job.id }
            val media=QueueChecks(instrumentation,report)
            check(media.hash(result.uri)==media.sourceHash())
            Store.setQueuePaused(true)
            withTimeout(60000) { Downloads.awaitIdle() }
        }
        report("PASS: platform URL/share validation, preserved Instagram identity, codec/unknown-quality handling, single-video eligibility, repeat support and generated A/V transfer with exact source audio")
    }
}
