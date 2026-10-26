package com.flux.downloader

import android.Manifest
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidMediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID


class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Downloads.execution.withLock {
        Store.init(applicationContext)
        // Only one scheduler owns the queue. Independent item tasks provide
        // bounded parallelism without sharing process IDs or output files.
        Store.downloads.value.filter { it.state in QueueRules.busyStates }.forEach { old ->
            Store.change(old.id, durable = true) {
                if (it.state == "Pausing") it.copy(state = "Paused", detail = "Paused · downloaded data kept")
                else it.copy(state = "Queued", detail = "Recovering interrupted download")
            }
        }
        val legacyId = inputData.getString("job")
        if (legacyId != null) {
            val job = Store.downloads.value.find { it.id == legacyId }
            if (job != null && !Store.queuePaused.value && NetworkGate.allowed() && job.state in QueueRules.runnableStates) {
                DownloadTask(applicationContext, job.id, { isStopped }, { prepareForeground() }).run(job)
                if (Store.downloads.value.find { it.id == job.id }?.state == "Retrying") Downloads.kick(applicationContext)
            }
            return@withLock Result.success()
        }
        supervisorScope {
            val active = linkedMapOf<String, Deferred<Unit>>()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    active.filterValues { it.isCompleted }.keys.toList().forEach { key -> active.remove(key)?.await() }
                    if (!Store.queuePaused.value && NetworkGate.allowed()) {
                        while (active.size < Store.transfers.value.parallel) {
                            val job = QueueRules.next(Store.downloads.value.filterNot { it.id in active }, System.currentTimeMillis()) ?: break
                            prepareForeground()
                            active[job.id] = async { DownloadTask(applicationContext, job.id, { isStopped }, { prepareForeground() }).run(job) }
                        }
                    }
                    val waiting = Store.downloads.value.any { it.state in QueueRules.runnableStates }
                    if (active.isEmpty() && (Store.queuePaused.value || !waiting)) break
                    if (active.isEmpty()) prepareForeground()
                    delay(400)
                }
            } finally {
                // Wait for every native child to stop before another scheduler
                // can own these files, including on WorkManager interruption.
                withContext(NonCancellable) {
                    active.values.forEach { it.cancel() }
                    active.values.forEach { it.join() }
                }
            }
        }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground()
    private suspend fun prepareForeground() { setForeground(foreground()) }
    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (!NetworkGate.allowed()) "Waiting for Wi-Fi · partial data kept" else "Up to ${Store.transfers.value.parallel} simultaneous downloads"
        val notice = NotificationCompat.Builder(applicationContext, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Flux download queue").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setProgress(0, 0, true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(710014, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(710014, notice)
    }
}

private class DownloadTask(
    private val applicationContext: Context,
    private val jobId: String,
    private val stopped: () -> Boolean,
    private val preparing: suspend () -> Unit
) {
    private val isStopped get() = stopped()
    private val manager get() = applicationContext.getSystemService(NotificationManager::class.java)
    private val notificationId get() = jobId.hashCode()
    private var lastUpdate = 0L
    @Volatile private var networkInterrupted = false

    private class UserStopped : Exception()

    private suspend fun checkControl() {
        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("Worker interrupted")
        if (!NetworkGate.allowed() && Store.downloads.value.find { it.id == jobId }?.state == "Downloading") {
            networkInterrupted = true
            throw UserStopped()
        }
        if (Store.downloads.value.find { it.id == jobId }?.state in setOf("Pausing", "Paused", "Cancelled")) throw UserStopped()
    }

    suspend fun run(job: Download) = withContext(Dispatchers.IO) {
        lastUpdate = 0L
        TransferProgress.clear(jobId)
        val work = Downloads.workDir(applicationContext, jobId)
        Store.workStarted(job)
        try {
            checkControl()
            check(work.isDirectory || work.mkdirs()) { "Unable to prepare download storage. Check free space." }
            Downloads.cleanupPending(applicationContext, Store.downloads.value.find { it.id == jobId } ?: job)
            preparing()
            Engine.initialize(applicationContext)
            checkControl()
            Store.change(jobId, durable = true) {
                if (it.state in setOf("Queued", "Retrying")) it.copy(state = "Downloading", nextAttemptAt = 0,
                    detail = if (work.listFiles().orEmpty().isNotEmpty()) "Checking saved data · resuming where supported" else "Connecting to ${MediaLinks.parse(job.url)?.platform?.label ?: "source"}") else it
            }
            checkControl()
            val request = Engine.secureOptions(YoutubeDLRequest(job.url)).apply {
                addOption("--no-playlist"); addOption("--no-mtime"); addOption("--newline")
                if(MediaLinks.isInstagram(job.url)) addOption("--playlist-items", "1")
                addOption("--progress"); addOption("--progress-template", TransferProgress.TEMPLATE)
                addOption("--continue"); addOption("--part"); addOption("--abort-on-unavailable-fragments")
                addOption("--socket-timeout", "30"); addOption("--retries", "2"); addOption("--fragment-retries", "2")
                addOption("--concurrent-fragments", "3"); addOption("--format", job.selector)
                if (!job.audioOnly) addOption("--merge-output-format", "mkv")
                if (job.replaceEmbeddedAudio) {
                    addOption("--audio-multistreams")
                    addOption("--postprocessor-args", "Merger+ffmpeg_o:-map -0:a? -disposition:a:0 default")
                }
                addOption("--output", File(work, "media.%(ext)s").absolutePath)
            }
            execute(request, job)
            checkControl()
            val result = work.listFiles()?.filter { it.name.matches(Regex("media\\.[a-zA-Z0-9]+")) }
                ?.singleOrNull() ?: error("No completed media file was produced. Try another format.")
            require(result.extension.lowercase() in setOf("mkv", "mp4", "webm", "m4a", "mp3", "opus", "ogg", "flac", "wav", "aac", "mov")) {
                "The source returned an unsupported file type. No file was saved."
            }
            TransferProgress.clear(jobId)
            update("Saving", 0f, "Saving to your download folder")
            val (uri, mime) = save(result, job)
            checkControl()
            val measuredAudio = inspectSavedAudio(uri)
            if (!Store.finishDownload(jobId, uri.toString(), mime, result.length(), measuredAudio)) throw UserStopped()
            post(notification(job.title, "Download complete", 100, false))
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { settleStopped() }
            throw cancel
        } catch (error: Exception) {
            val state = Store.downloads.value.find { it.id == jobId }?.state
            if (error is UserStopped || networkInterrupted || state in setOf("Pausing", "Paused", "Cancelled") || isStopped) {
                settleStopped()
            } else {
                val message = error.message.orEmpty()
                val current = Store.downloads.value.find { it.id == jobId } ?: job
                if (QueueRules.transient(message) && current.retryCount < QueueRules.MAX_RETRIES) {
                    val retry = current.retryCount + 1
                    Store.change(jobId, durable = true) {
                        if (it.state in setOf("Pausing", "Paused", "Cancelled")) it else it.copy(state = "Retrying", retryCount = retry,
                            nextAttemptAt = System.currentTimeMillis() + QueueRules.retryDelay(retry),
                            detail = "Connection interrupted · retry $retry/${QueueRules.MAX_RETRIES} in ${QueueRules.retryDelay(retry) / 1000}s · downloaded data kept")
                    }
                    post(notification(job.title, "Waiting to retry · downloaded data kept", current.progress.toInt(), false))
                } else {
                    val readable = when {
                        MediaLinks.isInstagram(job.url) && (message.contains("login",true) || message.contains("sign in",true) || message.contains("403") || message.contains("429")) -> "Instagram restricted this request. Try an accessible public single-video post or retry later. Login-required/private posts are not supported."
                        message.contains("Sign in", true) || message.contains("bot", true) -> "${MediaLinks.parse(job.url)?.platform?.label ?: "The source"} restricted this request. Try a public video or check for a Flux update."
                        message.contains("format is not available", true) -> "This format is no longer available. Search the video again."
                        message.contains("403") -> "YouTube denied this request. Check for a Flux update or try another video."
                        else -> message.takeLast(400).ifBlank { "Download failed. Check your connection and retry." }
                    }
                    Store.change(jobId, durable = true) {
                        if (it.state in setOf("Pausing", "Paused", "Cancelled")) it else it.copy(state = "Failed", nextAttemptAt = 0,
                            detail = if (it.retryCount >= QueueRules.MAX_RETRIES) "Automatic retries exhausted. $readable" else readable)
                    }
                    post(notification(job.title, "Download failed — open Flux to retry", 0, false))
                }
            }
        } finally {
            withContext(NonCancellable) {
                TransferProgress.clear(jobId)
                val current = Store.downloads.value.find { it.id == jobId }
                if (current != null && current.pendingUri.isNotBlank() && current.state != "Complete") {
                    runCatching { Downloads.cleanupPending(applicationContext, current) }.onFailure {
                        Store.change(jobId, durable = true) { it.copy(detail = "Restore folder access to clean up an unfinished save.") }
                    }
                }
                if (Store.downloads.value.find { it.id == jobId }?.state in setOf("Complete", "Cancelled")) work.deleteRecursively()
                Store.workFinished(jobId)
                val final = Store.downloads.value.find { it.id == jobId }
                when (final?.state) {
                    "Cancelled" -> manager.cancel(notificationId)
                    "Paused" -> post(notification(final.title, "Paused · downloaded data kept", final.progress.toInt(), false))
                    "Queued" -> post(notification(final.title, final.detail, final.progress.toInt(), false))
                }
            }
        }
    }

    private fun settleStopped() {
        Store.change(jobId, durable = true) {
            when (it.state) {
                "Cancelled" -> it
                "Pausing", "Paused" -> it.copy(state = "Paused", nextAttemptAt = 0, detail = "Paused · downloaded data kept")
                "Complete" -> it
                else -> it.copy(state = "Queued", detail = if (networkInterrupted) "Waiting for Wi-Fi · downloaded data kept" else "Interrupted · will resume when a connection is available")
            }
        }
        val job = Store.downloads.value.find { it.id == jobId }
        if (job?.state == "Paused") post(notification(job.title, "Paused · downloaded data kept", job.progress.toInt(), false))
    }

    private suspend fun execute(request: YoutubeDLRequest, job: Download) = coroutineScope {
        val process = async(Dispatchers.IO) {
            checkControl()
            YoutubeDL.getInstance().execute(request, jobId) { _, _, line ->
                val state = Store.downloads.value.find { it.id == jobId }?.state
                if (!NetworkGate.allowed() && state == "Downloading") networkInterrupted = true
                if (state in setOf("Pausing", "Paused", "Cancelled") || isStopped || networkInterrupted) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
                } else {
                    val now = System.currentTimeMillis()
                    val merging = line.startsWith("[Merger]", true) || line.startsWith("Merging formats", true)
                    val firstMerge = merging && state != "Merging"
                    val sample = TransferProgress.parse(line)
                    val previous = TransferProgress.live.value[jobId]
                    val changedStream = sample != null && (sample.format != previous?.format || sample.stream != previous?.stream)
                    if (firstMerge || (sample != null && state != "Merging" && (changedStream || now - lastUpdate > 650 || sample.percent == 100f))) {
                        lastUpdate = now
                        if (firstMerge) {
                            TransferProgress.clear(jobId)
                            update("Merging", 0f, "Combining video + selected audio · no quality loss")
                        } else if (sample != null) {
                            TransferProgress.record(jobId, sample)
                            update("Downloading", sample.percent ?: 0f, "${sample.stream} · current stream progress")
                        }
                        val current = Store.downloads.value.find { it.id == jobId }
                        post(notification(job.title, if (sample != null && !firstMerge) "${sample.stream} · ${sample.speedLabel} · ${sample.etaLabel}"
                            else current?.detail ?: "Downloading", current?.progress?.toInt()?.coerceIn(0, 100) ?: 0, true,
                            current?.state == "Merging" || sample?.percent == null))
                    }
                }
            }
        }
        try {
            var staleNotice = -1L
            while (!process.isCompleted) {
                currentCoroutineContext().ensureActive()
                val state = Store.downloads.value.find { it.id == jobId }?.state
                if (!NetworkGate.allowed() && state == "Downloading") networkInterrupted = true
                if (state in setOf("Pausing", "Paused", "Cancelled") || networkInterrupted) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
                }
                val sample = TransferProgress.live.value[jobId]
                if (state == "Downloading" && sample != null && !sample.fresh(android.os.SystemClock.elapsedRealtime()) && staleNotice != sample.updated) {
                    staleNotice = sample.updated
                    post(notification(job.title, "${sample.stream} · waiting for progress · ${sample.byteLabel}", 0, true, true))
                }
                delay(150)
            }
            process.await()
        } finally {
            // Cancellation must not release the queue lock while Python/FFmpeg
            // can still write the same partial files. Retry kill if pause raced
            // with registration of the native process.
            withContext(NonCancellable) {
                while (!process.isCompleted) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
                    delay(150)
                }
                process.join()
            }
        }
    }

    private fun update(state: String, progress: Float, detail: String) {
        Store.change(jobId) {
            if (it.state in setOf("Pausing", "Paused", "Cancelled", "Complete")) it else it.copy(state = state, progress = progress, detail = detail)
        }
        val current = Store.downloads.value.find { it.id == jobId }
        if (current?.state == "Saving") post(notification(current.title, "Saving · ${current.detail}",
            current.progress.toInt().coerceIn(0, 100), true))
    }

    private suspend fun save(file: File, job: Download): Pair<Uri, String> {
        val mime = when (file.extension.lowercase()) {
            "mkv" -> "video/x-matroska"; "mp4" -> "video/mp4"
            "webm" -> if (job.audioOnly) "audio/webm" else "video/webm"
            "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "opus", "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aac" -> "audio/aac"; "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        val title = job.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(110)
        val name = "$title [${job.label.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()}] ${job.id.take(6)}.${file.extension}"
        checkControl()
        var pending = false
        val target = when {
            job.folder.isNotEmpty() -> {
                val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(job.folder))
                    ?: error("Download folder is unavailable. Choose it again in Settings.")
                require(folder.canWrite()) { "Folder permission was revoked. Choose a folder in Settings." }
                folder.createFile(mime, name)?.uri ?: error("Unable to create a file in this folder.")
            }
            Build.VERSION.SDK_INT >= 29 -> {
                pending = true
                applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Flux")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("Unable to create download. Check free storage.")
            }
            else -> {
                val directory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Flux").apply { mkdirs() }
                FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.files", File(directory, name))
            }
        }
        try {
            Store.change(jobId, durable = true) { it.copy(pendingUri = target.toString()) }
            checkControl()
            file.inputStream().use { input ->
                applicationContext.contentResolver.openOutputStream(target, "w")?.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var last = 0L
                    while (true) {
                        checkControl()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied += count
                        if (System.currentTimeMillis() - last > 500) {
                            last = System.currentTimeMillis()
                            update("Saving", copied.toFloat() * 100 / file.length(), "${sizeLabel(copied)} / ${sizeLabel(file.length())}")
                        }
                    }
                } ?: error("Cannot write to the selected folder.")
            }
            checkControl()
            if (pending && Build.VERSION.SDK_INT >= 29) applicationContext.contentResolver.update(target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return target to mime
        } catch (error: Exception) {
            runCatching { Downloads.cleanupPending(applicationContext,
                Store.downloads.value.find { it.id == jobId } ?: job.copy(pendingUri = target.toString())) }
            throw error
        }
    }


    private fun inspectSavedAudio(uri: Uri): String = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(applicationContext, uri, null)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(AndroidMediaFormat.KEY_MIME).orEmpty().startsWith("audio/") } ?: return@runCatching ""
            buildList {
                add(audioCodecLabel(format.getString(AndroidMediaFormat.KEY_MIME).orEmpty()))
                // Android's Matroska extractor can report an estimated bitrate
                // unrelated to the copied stream. Show source bitrate in the
                // chooser; only report reliable codec/rate/channels here.
                if (format.containsKey(AndroidMediaFormat.KEY_SAMPLE_RATE)) add("${format.getInteger(AndroidMediaFormat.KEY_SAMPLE_RATE) / 1000.0} kHz")
                if (format.containsKey(AndroidMediaFormat.KEY_CHANNEL_COUNT)) {
                    val channels = format.getInteger(AndroidMediaFormat.KEY_CHANNEL_COUNT)
                    add(when (channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$channels channels" })
                }
            }.joinToString(" · ")
        } finally { extractor.release() }
    }.getOrDefault("")

    private fun post(notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(notificationId, notification)
        }
    }

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(applicationContext, 0,
        Intent(applicationContext, DownloadActionReceiver::class.java).setAction("com.flux.downloader.$action")
            .setData(Uri.parse("flux://download/$jobId/$action")).putExtra("job", jobId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(title: String, detail: String, percent: Int, ongoing: Boolean, indeterminate: Boolean = false): Notification {
        manager.createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(detail)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply {
                if (ongoing) {
                    setProgress(100, percent, indeterminate)
                    addAction(android.R.drawable.ic_media_pause, "Pause", action("PAUSE"))
                    addAction(android.R.drawable.ic_delete, "Cancel", action("CANCEL"))
                } else if (Store.downloads.value.find { it.id == jobId }?.state == "Paused") {
                    addAction(android.R.drawable.ic_media_play, "Resume", action("RESUME"))
                }
            }
            .build()
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "Size varies"
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
fun durationLabel(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
