package com.flux.downloader

import android.app.Instrumentation
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.atomic.AtomicInteger

class TransferChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    private val wm get() = WorkManager.getInstance(app)
    suspend fun run() {
        Store.init(app)
        wm.cancelAllWork().result.get()
        withTimeout(60_000) { Downloads.execution.lock(); Downloads.execution.unlock() }
        Store.downloads.value.filterNot { it.terminal }.forEach { old -> Store.change(old.id, durable=true) { it.copy(state="Cancelled") } }
        Store.clearHistory(); Store.setQueuePaused(false)
        val helper = QueueChecks(instrumentation, report)
        val maximum = AtomicInteger(0)
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            Store.downloads.collect { jobs -> maximum.accumulateAndGet(jobs.count { it.state in QueueRules.busyStates }, ::maxOf) }
        }
        try {
            helper.FixtureServer().use { server ->
                NetworkGate.testWifi(true)
                Store.setTransfers(TransferPreferences(2, false))
                val jobs = (1..5).map { n -> Download(url=server.path("slow/item$n"), title="Flux parallel fixture $n", thumbnail="",
                    selector="bestaudio", label="Source audio", audioOnly=true, duration=6, folder="", queueOrder=n.toLong()) }
                jobs.take(4).forEach(Store::add)
                wm.enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().addTag("flux-transfer-check").build())
                await { jobs.take(2).all { state(it) == "Downloading" } }
                check(jobs.drop(2).take(2).all { state(it) == "Queued" })
                check(maximum.get() <= 2)
                report("PASS: limit 2 starts two independent item pipelines and holds later jobs")
                Store.setTransfers(TransferPreferences(1, false))
                await { state(jobs[2]) == "Downloading" }
                check(jobs.take(2).all { state(it) == "Complete" })
                check(state(jobs[3]) == "Queued")
                report("PASS: lowering the limit lets active items finish and does not refill slots above the new limit")
                await { partialBytes(jobs[2]) > 8192 }
                NetworkGate.testWifi(false)
                Store.setTransfers(TransferPreferences(1, true))
                await { state(jobs[2]) == "Queued" && !Store.isWorking(jobs[2].id) }
                val bytes = partialBytes(jobs[2]); check(bytes > 8192)
                Store.add(jobs[4])
                delay(1200)
                check(jobs.drop(2).all { state(it) == "Queued" })
                check(partialBytes(jobs[2]) == bytes)
                check(Store.downloads.value.none { it.state in QueueRules.busyStates })
                report("PASS: Wi-Fi-only policy stops active transfer on a simulated cellular handover, retains $bytes bytes and blocks queued work")
                NetworkGate.testWifi(true)
                Store.setTransfers(TransferPreferences(3, true))
                await { jobs.drop(2).all { state(it) == "Downloading" } }
                check(maximum.get() <= 3)
                jobs.forEach { job -> await { state(job) == "Complete" } }
                check(maximum.get() == 3)
                check(server.rangeRequests.get() > 0)
                val expected = helper.sourceHash()
                jobs.forEach { job ->
                    val result = Store.downloads.value.first { it.id == job.id }
                    check(helper.hash(result.uri) == expected) { "Mixed/corrupted audio for ${job.title}" }
                }
                report("PASS: restoring Wi-Fi resumes via HTTP Range; limit 3 runs three pipelines; every output has exact source audio samples")
                Store.setTransfers(TransferPreferences(2, true))
                val prefs = app.getSharedPreferences("flux",0)
                check(prefs.getInt("parallel_downloads",0)==2 && prefs.getBoolean("wifi_only",false))
                report("PASS: simultaneous-download and Wi-Fi preferences are durably persisted")
            }
        } finally {
            watcher.cancelAndJoin()
            NetworkGate.testWifi(null)
        }
    }
    private fun state(job: Download) = Store.downloads.value.first { it.id == job.id }.also {
        check(it.state != "Failed" && it.state != "Cancelled") { "${it.title}: ${it.state}: ${it.detail}" }
    }.state
    private fun partialBytes(job: Download) = Downloads.workDir(app,job.id).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.sumOf { it.length() }
    private suspend fun await(test: () -> Boolean) = withTimeout(300_000) { while (!test()) delay(75) }
}
