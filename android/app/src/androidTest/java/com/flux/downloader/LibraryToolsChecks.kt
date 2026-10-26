package com.flux.downloader

import android.app.Instrumentation
import android.net.Uri
import androidx.work.WorkManager
import kotlinx.coroutines.withTimeout

class LibraryToolsChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    suspend fun run(restart: Boolean = false) {
        Store.init(app)
        val ids=setOf("library-tools-one","library-tools-two")
        if(restart) {
            val saved=Store.downloads.value.filter { it.id in ids }
            check(saved.size==2 && saved.all { it.favourite && LibraryDetails.inspect(app,it).available })
            check(libraryJobs(saved,"tools","All",LibrarySort.Title,true,LibraryStatus.Completed).size==2)
            report("PASS: bulk favourite flags, completed filtering and saved-file access survive process restart")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.awaitIdle() }
        Store.setQueuePaused(true)
        val helper=LibraryChecks(instrumentation,report)
        val one=helper.fixture("library-tools-one")
        val two=helper.fixture("library-tools-two","video/mp4")
        val failed=one.copy(id="library-tools-failed",state="Failed",uri="",bytes=Long.MAX_VALUE)
        val cancelled=one.copy(id="library-tools-cancelled",state="Cancelled",uri="")
        val active=one.copy(id="library-tools-active",state="Queued",uri="")
        val all=listOf(one,two,failed,cancelled,active)
        check(libraryJobs(all,"","All",LibrarySort.Newest).size==4)
        check(libraryJobs(all,"","All",LibrarySort.Newest,false,LibraryStatus.Failed).single().id==failed.id)
        check(libraryJobs(all,"","All",LibrarySort.Newest,false,LibraryStatus.Cancelled).single().id==cancelled.id)
        check(libraryJobs(all,"tools","Audio",LibrarySort.Title,false,LibraryStatus.Completed).single().id==one.id)
        val summary=selectionSummary(all)
        check(summary.completed==2 && summary.knownBytes==one.bytes+two.bytes && summary.unknownSizes==0)
        val unknown=selectionSummary(listOf(one,two.copy(bytes=0)))
        check(unknown.knownBytes==one.bytes && unknown.unknownSizes==1 && unknown.label.contains("unknown"))
        check(selectionSummary(listOf(one.copy(bytes=0))).label.contains("unknown"))
        check(selectionSummary(listOf(failed)).completed==0)
        check(selectionSummary(listOf(one.copy(bytes=Long.MAX_VALUE),two.copy(bytes=1))).knownBytes==null)
        all.forEach(Store::add)
        val before=app.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }
        val selected=all.map { it.id }.toSet()+"missing-id"
        check(Store.setFavourites(selected,true)==2)
        check(Store.setFavourites(selected,true)==0)
        check(Store.downloads.value.filter { it.id in setOf(failed.id,cancelled.id,active.id) }.none { it.favourite })
        check(libraryJobs(Store.downloads.value,"tools","All",LibrarySort.Title,true,LibraryStatus.Completed).size==2)
        check(Store.setFavourites(setOf(one.id),false)==1)
        check(!Store.downloads.value.first { it.id==one.id }.favourite && Store.downloads.value.first { it.id==two.id }.favourite)
        check(runCatching { Store.setFavourites((1..101).map { "oversize-$it" }.toSet(),true) }.isFailure)
        check(Store.setFavourites(ids,true)==1)
        check(Store.downloads.value.first { it.id==one.id }.copy(favourite=false)==one)
        check(before.contentEquals(app.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }))
        Store.change(active.id,true) { it.copy(state="Cancelled") };Store.removeHistory(setOf(active.id))
        report("PASS: status/search/type/favourite combinations, known/unknown/overflow size summaries, idempotent bounded bulk favourite changes, protected non-completed entries and unchanged saved bytes")
    }
}
