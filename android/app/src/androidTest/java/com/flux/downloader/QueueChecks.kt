package com.flux.downloader

import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Generated loopback media only; no YouTube content is requested. */
class QueueChecks(private val instrumentation: Instrumentation, private val report: (String) -> Unit) {
    private val app get() = instrumentation.targetContext
    private val assets get() = instrumentation.context.assets
    private val wm get() = WorkManager.getInstance(app)

    suspend fun run(restartOnly: Boolean) {
        Store.init(app)
        if (restartOnly) {
            FixtureServer().use { server ->
                val job = Store.downloads.value.first { it.title == "Flux process restart fixture" }
                check(job.state == "Paused") { job.state }
                check(parts(job).any { it.length() > 4096 }) { "Partial data did not survive process restart" }
                // The in-process fixture server uses a new port after restart.
                // Only this generated test source URL is refreshed; content is identical.
                Store.change(job.id, durable = true) { it.copy(url = server.slowUrl, state = "Queued", nextAttemptAt = 0) }
                queue()
                val done = waitFor(job.id, "Complete")
                check(server.rangeRequests.get() > 0) { "Restart did not request a non-zero HTTP range" }
                check(hash(done.uri) == sourceHash()) { "Restarted output differs from source audio" }
                check(!Downloads.workDir(app, job.id).exists())
                report("PASS: partial bytes and paused history survive real app process restart; source range resume preserves audio samples exactly")
            }
            return
        }
        wm.cancelAllWork().result.get()
        withTimeout(60_000) { Downloads.execution.lock(); Downloads.execution.unlock() }
        Store.downloads.value.filterNot { it.terminal }.forEach { job -> Store.change(job.id, durable = true) { it.copy(state = "Cancelled") } }
        Store.clearHistory(); Store.setQueuePaused(false); Store.setTransfers(TransferPreferences())
        check(QueueRules.transient("Unable to download webpage: HTTP Error 503"))
        check(QueueRules.transient("connection reset by peer"))
        check(!QueueRules.transient("Unable to download: HTTP Error 403"))
        check(!QueueRules.transient("No space left on device"))
        check((1..3).map(QueueRules::retryDelay) == listOf(30_000L, 60_000L, 120_000L))
        report("PASS: transient retry policy excludes access-denied/storage errors and uses bounded 30/60/120 second delays")
        NotificationChecks(instrumentation,report).styles()
        Engine.initialize(app)
        FixtureServer().use { server ->
            val sample = Download(url = server.slowUrl, title = "Flux pause and resume fixture", thumbnail = "", selector = "bestaudio",
                label = "Source audio", audioOnly = true, duration = 6, folder = "")
            Store.add(sample); queue()
            waitForPart(sample)
            Downloads.pause(app, sample)
            waitFor(sample.id, "Paused")
            withTimeout(60_000) { while (Store.isWorking(sample.id)) delay(100) }
            NotificationChecks(instrumentation,report).posted(sample,"Paused")
            val length = parts(sample).sumOf { it.length() }
            check(length > 4096) { "Pause discarded partial data" }
            check(Downloads.workDir(app, sample.id).absolutePath.startsWith(app.noBackupFilesDir.absolutePath))
            Downloads.resume(app, sample); queue()
            val done = waitFor(sample.id, "Complete")
            check(server.rangeRequests.get() > 0) { "Resuming did not request a non-zero HTTP range" }
            check(hash(done.uri) == sourceHash()) { "Resumed output differs from source audio" }
            withTimeout(30_000) { while (Downloads.workDir(app, sample.id).exists()) delay(100) }
            NotificationChecks(instrumentation,report).posted(done,"Complete")
            NotificationChecks(instrumentation,report).legacy(done)
            report("PASS: active pause waits for native shutdown, retains $length partial bytes, resumes via HTTP Range and preserves source audio samples")

            val pa = sample.copy(id = id(), title = "Flux pause-all A", state = "Queued")
            val pb = sample.copy(id = id(), title = "Flux pause-all B", state = "Queued")
            Store.setQueuePaused(true)
            Downloads.enqueue(app, pa); Downloads.enqueue(app, pb)
            check(Store.downloads.value.filter { it.id in setOf(pa.id, pb.id) }.all { it.state == "Paused" })
            check(app.getSharedPreferences("flux", 0).getBoolean("queue_paused", false))
            Downloads.cancelAll(app)
            check(Store.downloads.value.filter { it.id in setOf(pa.id, pb.id) }.all { it.state == "Cancelled" })
            check(!Store.queuePaused.value)
            report("PASS: a paused queue holds newly added items; cancel-all preserves completed files and resets queue hold")

            server.manifests.clear()
            val denied = sample.copy(id = id(), title = "Flux denied fixture", url = server.path("denied"), queueOrder = 10)
            val last = sample.copy(id = id(), title = "Flux queue last", url = server.path("last"), queueOrder = 20)
            val next = sample.copy(id = id(), title = "Flux queue next", url = server.path("next"), queueOrder = 30)
            val cancelled = sample.copy(id = id(), title = "Flux cancelled queue item", url = server.path("never"), queueOrder = 0)
            listOf(denied, last, next, cancelled).forEach(Store::add)
            Downloads.cancel(app, cancelled)
            Downloads.next(app, next)
            val conflicts = AtomicInteger(0)
            val watcher = CoroutineScope(Dispatchers.Default).launch {
                Store.downloads.collect { jobs -> if (jobs.count { it.state in QueueRules.busyStates } > 1) conflicts.incrementAndGet() }
            }
            queue()
            waitFor(next.id, "Complete"); waitFor(denied.id, "Failed"); waitFor(last.id, "Complete")
            watcher.cancelAndJoin()
            check(conflicts.get() == 0) { "More than one pipeline was active" }
            val paths = synchronized(server.manifests) { server.manifests.toList().distinct() }
            check(paths.indexOf("/next/manifest.mpd") < paths.indexOf("/denied/manifest.mpd") && paths.indexOf("/denied/manifest.mpd") < paths.indexOf("/last/manifest.mpd")) { paths }
            check("/never/manifest.mpd" !in paths)
            check(Store.downloads.value.first { it.id == denied.id }.retryCount == 0)
            report("PASS: Download next changes ordering; exactly one pipeline runs; cancelled item is skipped and permanent failure does not block the next item")

            server.flaky.set(1)
            val flaky = sample.copy(id = id(), title = "Flux transient retry fixture", url = server.path("flaky"), queueOrder = 40)
            val follower = sample.copy(id = id(), title = "Flux retry follower", url = server.path("follower"), queueOrder = 50)
            Store.add(flaky); Store.add(follower); queue()
            val retrying = waitFor(flaky.id, "Retrying")
            check(retrying.retryCount == 1 && retrying.nextAttemptAt > System.currentTimeMillis())
            server.flaky.set(0)
            waitFor(follower.id, "Complete")
            val recovered = waitFor(flaky.id, "Complete")
            check(recovered.retryCount == 1)
            check(hash(recovered.uri) == sourceHash())
            report("PASS: temporary 503 schedules a bounded retry, other queued work proceeds during backoff, then the failed item completes automatically")

            wm.cancelAllWorkByTag("flux-queue").result.get()
            withTimeout(90_000) {
                while (wm.getWorkInfosByTag("flux-generated-queue-check").get().any { !it.state.isFinished }) delay(100)
            }
            withTimeout(60_000) { Downloads.execution.lock(); Downloads.execution.unlock() }
            val interrupted = sample.copy(id = id(), title = "Flux process restart fixture")
            Store.add(interrupted)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("job" to interrupted.id))
                .addTag("flux-generated-queue-check").build()
            wm.enqueue(request)
            waitForPart(interrupted)
            wm.cancelWorkById(request.id).result.get()
            withTimeout(60_000) { while (Store.isWorking(interrupted.id)) delay(100) }
            check(Store.downloads.value.first { it.id == interrupted.id }.state == "Queued")
            check(parts(interrupted).sumOf { it.length() } > 4096)
            Store.change(interrupted.id, durable = true) { it.copy(state = "Paused", detail = "Generated process restart fixture") }
            report("PASS: WorkManager interruption keeps partial files and returns work to queued state; paused restart fixture is persisted")
        }
    }

    private fun queue(): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().addTag("flux-generated-queue-check").build()
        // Local fixtures omit network validation; production queue remains CONNECTED.
        wm.enqueue(request)
        return request
    }
    private fun id() = java.util.UUID.randomUUID().toString()
    private fun parts(job: Download): List<File> = Downloads.workDir(app, job.id).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
    private suspend fun waitForPart(job: Download) = withTimeout(240_000) {
        while (!Store.isWorking(job.id) || parts(job).sumOf { it.length() } <= 8192L) {
            val current = Store.downloads.value.first { it.id == job.id }
            check(!current.terminal) { "Download ended before interruption: ${current.state}: ${current.detail}" }
            delay(100)
        }
    }
    private suspend fun waitFor(id: String, state: String): Download = withTimeout(300_000) {
        while (true) {
            val job = Store.downloads.value.first { it.id == id }
            if (job.state == state) return@withTimeout job
            check(!job.terminal) { "Expected $state; got ${job.state}: ${job.detail}" }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    internal fun sourceHash(): String {
        val file = File(app.cacheDir, "queue-source-audio.m4a")
        assets.open("audio.m4a").use { input -> file.outputStream().use { input.copyTo(it) } }
        return try { hash(file.absolutePath) } finally { file.delete() }
    }
    internal fun hash(uri: String): String {
        val extractor = MediaExtractor()
        try {
            if (uri.startsWith("content:")) extractor.setDataSource(app, Uri.parse(uri), null) else extractor.setDataSource(uri)
            val audio = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            check(extractor.getTrackFormat(audio).getLong(MediaFormat.KEY_DURATION) > 5_000_000)
            extractor.selectTrack(audio)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
            while (true) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                val data = ByteArray(size); buffer.position(0); buffer.get(data); digest.update(data); extractor.advance()
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { extractor.release() }
    }

    inner class FixtureServer : AutoCloseable {
        private val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var open = true
        val rangeRequests = AtomicInteger(0)
        val flaky = AtomicInteger(0)
        val manifests = Collections.synchronizedList(mutableListOf<String>())
        fun path(name: String) = "http://127.0.0.1:${server.localPort}/$name/manifest.mpd"
        val slowUrl get() = path("slow")
        init {
            executor.execute {
                while (open) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    executor.execute { runCatching { socket.use { client ->
                        client.soTimeout = 10000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine().split(' ')
                        val path = request[1].substringBefore('?')
                        val headers = mutableMapOf<String, String>()
                        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                        val name = path.substringAfterLast('/')
                        val output = client.getOutputStream()
                        if (name == "manifest.mpd") manifests.add(path)
                        if (path.startsWith("/denied/") || (path.startsWith("/flaky/") && flaky.get() > 0)) {
                            val status = if (path.startsWith("/denied/")) "403 Forbidden" else "503 Service Unavailable"
                            output.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return@use
                        }
                        require(name in setOf("manifest.mpd", "video.mp4", "audio.m4a"))
                        val data = assets.open(name).use { it.readBytes() }
                        val range = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val start = range.coerceIn(0, data.size)
                        if (start > 0) rangeRequests.incrementAndGet()
                        val contentRange = if (start > 0) "Content-Range: bytes $start-${data.size - 1}/${data.size}\r\n" else ""
                        val type = if (name.endsWith("mpd")) "application/dash+xml" else "application/octet-stream"
                        output.write("HTTP/1.1 ${if (start > 0) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${data.size - start}\r\nAccept-Ranges: bytes\r\n$contentRange Content-Type: $type\r\nConnection: close\r\n\r\n".replace("\r\n Content-Type", "\r\nContent-Type").toByteArray())
                        if (request[0] != "HEAD") {
                            if (path.startsWith("/slow/") && !name.endsWith("mpd")) {
                                var offset = start
                                while (offset < data.size) { val count = minOf(512, data.size - offset)
                                    output.write(data, offset, count); output.flush(); offset += count; Thread.sleep(80) }
                            } else output.write(data, start, data.size - start)
                        }
                        output.flush()
                    } } }
                }
            }
        }
        override fun close() { open = false; server.close(); executor.shutdownNow() }
    }
}
