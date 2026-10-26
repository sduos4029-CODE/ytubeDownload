package com.flux.downloader

import android.app.Instrumentation
import android.net.Uri
import androidx.work.WorkManager
import kotlinx.coroutines.withTimeout

class FavouritesChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    suspend fun run(restart: Boolean = false) {
        Store.init(app)
        if (restart) {
            val saved=Store.downloads.value.first { it.id=="favourite-survivor" }
            check(saved.favourite && LibraryDetails.inspect(app,saved).available)
            check(libraryJobs(Store.downloads.value,"favourite survivor","Audio",LibrarySort.Title,true).single().id==saved.id)
            report("PASS: favourite flag and filtered Library survive force-stop/restart; saved file remains accessible")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.execution.lock(); Downloads.execution.unlock() }
        Store.setQueuePaused(true)
        val helper=LibraryChecks(instrumentation,report)
        val one=helper.fixture("favourite-survivor").copy(title="Favourite survivor",audioDescription="AAC · 44.1 kHz · Stereo")
        val two=helper.fixture("favourite-other","video/mp4").copy(title="Favourite video")
        val paused=one.copy(id="favourite-paused",state="Paused",uri="")
        val legacy=one.json().apply { remove("favourite") }
        check(!Download.from(legacy).favourite)
        check(Download.from(one.copy(favourite=true).json()).favourite)
        listOf(one,two,paused).forEach(Store::add)
        val before=app.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }
        Store.toggleFavourite(one.id);Store.toggleFavourite(two.id);Store.toggleFavourite(paused.id)
        check(!Store.downloads.value.first { it.id==paused.id }.favourite)
        check(libraryJobs(Store.downloads.value,"favourite","Audio",LibrarySort.Newest,true).single().id==one.id)
        check(libraryJobs(Store.downloads.value,"favourite","Video",LibrarySort.Title,true).single().id==two.id)
        Store.toggleFavourite(two.id)
        check(!Store.downloads.value.first { it.id==two.id }.favourite)
        val details=LibraryDetails.inspect(app,one)
        check(details.available && details.bytes==one.bytes && details.name=="${one.id}.m4a")
        val after=app.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }
        check(before.contentEquals(after))
        val missing=helper.fixture("favourite-missing").copy(favourite=true)
        Store.add(missing);app.contentResolver.delete(Uri.parse(missing.uri),null,null)
        check(!LibraryDetails.inspect(app,missing).available)
        check(Store.downloads.value.first { it.id==missing.id }.favourite)
        check(!LibraryDetails.inspect(app,one.copy(uri="file:///private/data")).available)
        check(!LibraryDetails.inspect(app,paused).available)
        check(LibraryDetails.source(one.url)==one.url)
        listOf("http://youtube.com/watch?v=1","https://evil.invalid/a","javascript:alert(1)","https://youtube.com@evil.invalid/a","https://user@youtube.com/watch?v=1","https://youtube.com:444/a","plain search","https://youtube.com/\ninvalid").forEach { check(LibraryDetails.source(it)==null) }
        Store.change(paused.id,true) { it.copy(state="Cancelled") }
        Store.removeHistory(setOf(two.id,paused.id,missing.id))
        check(Store.downloads.value.none { it.id==missing.id })
        report("PASS: legacy/default and JSON favourite migration; audio/video/search filtering; mark/unmark and removal; provider metadata and missing-file handling; saved bytes unchanged; source-link validation")
    }
}
