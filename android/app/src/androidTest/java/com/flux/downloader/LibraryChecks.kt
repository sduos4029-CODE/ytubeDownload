package com.flux.downloader

import android.app.Instrumentation
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.WorkManager
import kotlinx.coroutines.withTimeout
import java.io.File

class LibraryChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    fun fixture(id: String, mime: String = "audio/mp4"): Download {
        val root = File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "library-checks").apply { mkdirs() }
        val file = File(root, "$id.m4a")
        instrumentation.context.assets.open("audio.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Download(id=id, url="https://www.youtube.com/watch?v=fixture", title="Library fixture $id", thumbnail="",
            selector="bestaudio", label="Source audio", audioOnly=mime.startsWith("audio/"), duration=6, folder="",
            state="Complete", uri=FileProvider.getUriForFile(app,"${app.packageName}.files",file).toString(), mime=mime,
            bytes=file.length())
    }
    suspend fun run() {
        Store.init(app)
        kotlin.run {
            val shown=linkedSetOf("one","two")
            val selected=LibraryResultSelection.toggle(shown,setOf("hidden"))
            check(selected==setOf("hidden","one","two") && LibraryResultSelection.unselect(shown,selected))
            check(LibraryResultSelection.toggle(shown,selected)==setOf("hidden"))
            val partial=LibraryResultSelection.toggle(shown,setOf("one"));check(partial==shown)
            val almostFull=(1..99).map {"kept-$it"}.toSet()
            val capped=LibraryResultSelection.toggle(shown,almostFull)
            check(capped.size==100 && capped.containsAll(almostFull) && LibraryResultSelection.unselect(shown,capped))
            check(LibraryResultSelection.toggle(shown,capped)==almostFull)
            check(LibraryResultSelection.toggle(emptySet(),selected)==selected)
            report("PASS: results selection toggle preserves hidden selections, fills partial selection and respects the 100-item cap")
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.execution.lock(); Downloads.execution.unlock() }
        Store.setQueuePaused(true)
        val one=fixture("library-one"); val two=fixture("library-two","video/mp4")
        Store.add(one); Store.add(two)
        val single=LibraryActions.shareIntent(app,listOf(one))
        check(single.action==Intent.ACTION_SEND && single.type==one.mime)
        check(single.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        check(single.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        check(single.clipData!!.itemCount==1 && single.clipData!!.getItemAt(0).uri.toString()==one.uri)
        val multiple=LibraryActions.shareIntent(app,listOf(one,two))
        check(multiple.action==Intent.ACTION_SEND_MULTIPLE && multiple.type=="*/*" && multiple.clipData!!.itemCount==2)
        check(multiple.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.map { it.toString() }==listOf(one.uri,two.uri))
        check(runCatching { LibraryActions.shareIntent(app,listOf(one.copy(state="Failed"))) }.isFailure)
        check(runCatching { LibraryActions.shareIntent(app,listOf(one.copy(uri="file:///private/data"))) }.isFailure)
        check(runCatching { LibraryActions.shareIntent(app,List(101) { one }) }.isFailure)
        val missing=fixture("library-missing")
        app.contentResolver.delete(android.net.Uri.parse(missing.uri),null,null)
        check(runCatching { LibraryActions.shareIntent(app,listOf(one,missing)) }.isFailure)
        report("PASS: single/multiple sharing uses content URIs, read-only ClipData grants and mixed MIME; missing, failed, unsafe and excessive selections rejected")
        val active=one.copy(id="library-active",state="Paused",uri="")
        val pending=one.copy(id="library-pending",state="Cancelled",pendingUri=one.uri)
        val working=one.copy(id="library-working",state="Failed",uri="")
        val failed=one.copy(id="library-failed",state="Failed",uri="")
        listOf(active,pending,working,failed).forEach(Store::add)
        Store.workStarted(working)
        val parts=Downloads.workDir(app,failed.id).apply { mkdirs() }
        File(parts,"fixture.part").writeText("partial")
        try {
            val removed=LibraryActions.removeHistory(app,setOf(one.id,failed.id,active.id,pending.id,working.id))
            check(removed==2)
            check(Store.downloads.value.none { it.id in setOf(one.id,failed.id) })
            check(Store.downloads.value.any { it.id==two.id })
            check(Store.downloads.value.count { it.id in setOf(active.id,pending.id,working.id) }==3)
            check(!parts.exists())
            check(app.contentResolver.openInputStream(android.net.Uri.parse(one.uri))!!.use { it.readBytes().size.toLong() }==one.bytes)
            report("PASS: selective history removal keeps saved bytes and unrelated history; active, working and pending-save entries protected; failed partials cleaned")
        } finally {
            Store.workFinished(working.id)
            Store.change(pending.id,true) { it.copy(pendingUri="") }
            Store.change(active.id,true) { it.copy(state="Cancelled") }
            LibraryActions.removeHistory(app,setOf(active.id,pending.id,working.id))
        }
    }
    fun restart() {
        Store.init(app)
        check(Store.downloads.value.none { it.id in setOf("library-one","library-failed") })
        check(Store.downloads.value.any { it.id=="library-two" })
        val saved=File(app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),"library-checks/library-one.m4a")
        check(saved.isFile && saved.length()>0)
        report("PASS: selective removal persists through process restart; other saved history remains")
    }
}
