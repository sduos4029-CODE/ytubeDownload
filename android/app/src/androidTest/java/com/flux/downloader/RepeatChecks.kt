package com.flux.downloader

import android.app.Instrumentation
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*

class RepeatChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    suspend fun run(restart: Boolean = false) {
        Store.init(app)
        val helper=QueueChecks(instrumentation,report)
        if(restart) {
            val source=Store.downloads.value.first { it.id=="repeat-source" }
            val id=app.getSharedPreferences("repeat-check",0).getString("copy",null)
            val copy=Store.downloads.value.first { it.id==id }
            check(source.favourite && source.state=="Complete" && copy.state=="Complete" && copy.uri!=source.uri)
            check(helper.hash(source.uri)==helper.hash(copy.uri))
            report("PASS: independent repeated download and original favourite/history/files persist through process restart")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.execution.lock();Downloads.execution.unlock() }
        Store.downloads.value.filterNot { it.terminal }.forEach { old -> Store.change(old.id,true) { it.copy(state="Cancelled") } }
        Store.setQueuePaused(true)
        val source=LibraryChecks(instrumentation,report).fixture("repeat-source").copy(favourite=true,retryCount=3,nextAttemptAt=1234,progress=100f)
        Store.add(source)
        check(RepeatDownloads.sourceKey("https://youtu.be/fixture?t=4")==RepeatDownloads.sourceKey(source.url))
        check(RepeatDownloads.sourceKey("https://www.youtube.com/shorts/fixture")=="fixture")
        check(RepeatDownloads.sourceKey("http://youtube.com/watch?v=fixture")==null)
        check(runCatching { RepeatDownloads.prepare(source.copy(state="Failed"),"") }.isFailure)
        check(runCatching { RepeatDownloads.prepare(source.copy(url="https://evil.invalid/a"),"") }.isFailure)
        val clean=RepeatDownloads.prepare(source.copy(pendingUri=source.uri),"content://new/folder",42)
        check(clean.id!=source.id && clean.created==42L && clean.folder=="content://new/folder")
        check(clean.uri.isEmpty() && clean.mime.isEmpty() && clean.pendingUri.isEmpty() && clean.bytes==0L && clean.progress==0f && clean.retryCount==0 && clean.nextAttemptAt==0L && !clean.favourite)
        check(clean.selector==source.selector && clean.replaceEmbeddedAudio==source.replaceEmbeddedAudio)
        val bytes=app.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { it.readBytes() }
        Store.setFolder("content://new/folder")
        val outcomes=coroutineScope { (1..8).map { async(Dispatchers.IO) { Downloads.downloadAgain(app,source.id) } }.awaitAll() }
        check(outcomes.count { it.created }==1 && outcomes.map { it.job.id }.distinct().size==1)
        val queued=outcomes.first().job
        check(queued.state=="Paused" && queued.folder=="content://new/folder")
        check(Store.downloads.value.first { it.id==source.id }==source)
        Store.setFolder("")
        Store.change(queued.id,true) { it.copy(state="Cancelled") };Store.removeHistory(setOf(queued.id))
        report("PASS: fresh identity/current-folder/reset state, original metadata/favourite unchanged, paused queue respected and concurrent duplicate requests coalesced")
        helper.FixtureServer().use { server ->
            val next=Downloads.downloadAgain(app,source.id).job
            check(next.folder.isBlank())
            // Only the generated fixture URL changes. Release input validation
            // still accepts HTTPS YouTube links only; no test bypass is shipped.
            Store.change(next.id,true) { it.copy(url=server.path("repeat"),state="Queued") }
            Store.setQueuePaused(false)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
            withTimeout(90000) { while(Store.downloads.value.first { it.id==next.id }.also { check(it.state!="Failed") { it.detail } }.state!="Complete")delay(100) }
            val copy=Store.downloads.value.first { it.id==next.id }
            check(copy.uri!=source.uri && helper.hash(copy.uri)==helper.sourceHash())
            check(bytes.contentEquals(app.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { it.readBytes() }))
            check(Store.downloads.value.first { it.id==source.id }==source)
            app.getSharedPreferences("repeat-check",0).edit().putString("copy",copy.id).commit()
            Store.setQueuePaused(true)
            report("PASS: new queue item downloads generated media to a distinct URI with source audio intact while original file bytes/history/favourite stay unchanged")
        }
    }
}
