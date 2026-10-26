package com.flux.downloader

import android.app.Instrumentation
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Collections

class ProgressChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    suspend fun run() {
        val exact=TransferProgress.parse("""FLUX_PROGRESS {"downloaded":1024,"total":4096,"speed":512.5,"eta":6,"format":"v1","video":"avc1","audio":"none"}""")!!
        check(exact.percent==25f && !exact.estimated && exact.stream=="Video stream" && exact.eta==6L)
        val unknown=TransferProgress.parse("""FLUX_PROGRESS {"downloaded":100,"total":"NA","estimate":null,"speed":"NA","eta":null,"video":"none","audio":"opus"}""")!!
        check(unknown.total==null && unknown.percent==null && unknown.speed==null && unknown.eta==null && unknown.stream=="Audio stream")
        val estimate=TransferProgress.parse("""FLUX_PROGRESS {"downloaded":50,"estimate":200,"speed":-1,"eta":-1}""")!!
        check(estimate.estimated && estimate.percent==25f && estimate.speed==null && estimate.eta==null)
        check(TransferProgress.parse("FLUX_PROGRESS invalid")==null)
        check(TransferProgress.parse("""FLUX_PROGRESS {"downloaded":-1}""")==null)
        check(TransferProgress.parse("[download] Destination: private-file")==null)
        check(TransferSnapshot(200,100,false,null,null,"","Media stream").percent==null)
        check(!exact.copy(updated=100).fresh(5101) && exact.copy(updated=100).fresh(5100))
        check(remainingTime(3661)=="1h 1m" && remainingTime(65)=="1m 5s")
        report("PASS: exact/estimated/unknown progress, missing and invalid metrics, stale-rate expiry and hour-long ETA formatting")
        Store.init(app)
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.execution.lock(); Downloads.execution.unlock() }
        Store.downloads.value.filterNot { it.terminal }.forEach { job -> Store.change(job.id,true) { it.copy(state="Cancelled") } }
        Store.setQueuePaused(false); Store.setTransfers(TransferPreferences(2,false))
        val helper=QueueChecks(instrumentation,report)
        helper.FixtureServer().use { server ->
            val video=Download(url=server.path("slow/progress-video"),title="Flux progress video fixture",thumbnail="",selector="bestvideo+bestaudio",label="180p",audioOnly=false,duration=6,folder="")
            val audio=video.copy(id=java.util.UUID.randomUUID().toString(),title="Flux progress audio fixture",selector="bestaudio",audioOnly=true)
            val samples=Collections.synchronizedList(mutableListOf<Pair<String,TransferSnapshot>>())
            val watcher=CoroutineScope(Dispatchers.Default).launch {
                TransferProgress.live.collect { values -> values.filterKeys { it in setOf(video.id,audio.id) }.forEach { samples.add(it.key to it.value) } }
            }
            try {
                Store.add(video); Store.add(audio)
                WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
                withTimeout(240000) {
                    while (listOf(video,audio).any { item -> Store.downloads.value.first { it.id==item.id }.also { check(it.state!="Failed") { it.detail } }.state!="Complete" }) delay(100)
                }
                withTimeout(10000) { while (listOf(video,audio).any { Store.isWorking(it.id) }) delay(100) }
                val captured=synchronized(samples) { samples.toList() }
                check(captured.any { it.first==video.id && it.second.stream=="Video stream" }) { "Missing native video telemetry: $captured" }
                check(captured.any { it.first==video.id && it.second.stream=="Audio stream" }) { "Missing stream switch" }
                check(captured.any { it.first==audio.id && it.second.downloaded>0 && it.second.speed!=null && it.second.eta!=null }) { "Missing native speed/ETA" }
                check(listOf(video,audio).all { it.id !in TransferProgress.live.value })
                val expected=helper.sourceHash()
                listOf(video,audio).forEach { item -> check(helper.hash(Store.downloads.value.first { it.id==item.id }.uri)==expected) }
                report("PASS: genuine native structured progress reports per-job bytes/speed/ETA and video-to-audio stream switch; concurrent outputs preserve source audio; live metrics cleaned after completion")
            } finally { watcher.cancelAndJoin() }
        }
    }
}
