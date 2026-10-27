package com.flux.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object QueueRules {
    val busyStates = setOf("Downloading", "Merging", "Saving", "Verifying", "Pausing")
    val runnableStates = setOf("Queued", "Retrying")
    const val MAX_RETRIES = 3
    fun retryDelay(retry: Int): Long = 30_000L * (1L shl (retry - 1).coerceIn(0, 2))
    fun transient(message: String): Boolean {
        val text = message.lowercase()
        if (listOf("http error 400", "http error 401", "403", "404", "http error 410", "sign in", "bot", "format is not available", "requested format", "requires https", "permission", "no space", "disk full", "unsupported file").any { it in text }) return false
        return listOf("timed out", "timeout", "connection reset", "connection refused", "connection aborted", "connection closed", "remote end closed", "unable to download", "network is unreachable", "network unreachable", "temporary failure", "name or service not known", "failed to resolve", "http error 429", "http error 500", "http error 502", "http error 503", "http error 504").any { it in text }
    }
    fun next(jobs: List<Download>, now: Long): Download? = jobs.filter {
        it.state in runnableStates && it.scheduledAt<=now && (it.state != "Retrying" || it.nextAttemptAt <= now)
    }.minWithOrNull(compareBy<Download> { it.queueOrder }.thenBy { it.created }.thenBy { it.id })
}

object Downloads {
    // Also serializes older per-item WorkRequests left by 1.2 during upgrade.
    internal val execution = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val QUEUE = "flux-download-queue"
    private const val TAG = "flux-queue"

    /** Wait until the current scheduler has released its native children. */
    suspend fun awaitIdle() { execution.withLock { } }

    fun workDir(context: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Invalid download identifier" }
        return File(context.noBackupFilesDir, "download-parts/$id")
    }

    fun enqueue(context: Context, job: Download) {
        Store.init(context)
        val paused = Store.queuePaused.value
        Store.add(job.copy(state = if (paused) "Paused" else "Queued", queueOrder = Store.nextQueueOrder(),
            detail = if (paused) "Queue paused · ready when you resume" else if(job.scheduledAt>System.currentTimeMillis()) "Scheduled for ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(job.scheduledAt))}" else "Waiting in download queue"))
        if (!paused) kick(context)
    }
    fun downloadAgain(context: Context, sourceId: String): RepeatOutcome {
        Store.init(context)
        val result = Store.repeatCompleted(sourceId)
        if (result.created && !Store.queuePaused.value) kick(context)
        return result
    }

    fun kick(context: Context,delayMillis:Long=0) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(TAG).setInitialDelay(delayMillis.coerceAtLeast(0),java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(UpgradeSettings.preferences.value.chargingOnly).build()).build()
        // Appending a harmless wake-up avoids KEEP's enqueue/worker-exit race.
        // A worker drains the durable queue; later wake-ups simply find it empty.
        if(delayMillis>0) {
            // A future wake must never block an immediate download behind it.
            val slot=(System.currentTimeMillis()+delayMillis)/1000
            WorkManager.getInstance(context).enqueueUniqueWork("flux-scheduled-wake-$slot",ExistingWorkPolicy.KEEP,request)
        }else WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun refreshConstraints(context:Context) {
        io.launch {
            val manager=WorkManager.getInstance(context)
            manager.getWorkInfosByTag(TAG).get().filter {it.state==WorkInfo.State.ENQUEUED}.forEach {info ->
                val update=OneTimeWorkRequestBuilder<DownloadWorker>().setId(info.id).addTag(TAG)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(UpgradeSettings.preferences.value.chargingOnly).build()).build()
                manager.updateWork(update).get()
            }
        }
    }
    fun recover(context: Context) {
        Store.init(context)
        if (!Store.queuePaused.value && Store.downloads.value.any {
                it.state in QueueRules.runnableStates || it.state in QueueRules.busyStates
            }) kick(context)
    }

    fun pause(context: Context, job: Download) {
        Store.init(context)
        Store.change(job.id, durable = true) {
            if (it.terminal || it.state == "Paused") it
            else it.copy(state = if (Store.isWorking(it.id)) "Pausing" else "Paused", nextAttemptAt = 0,
                detail = if (Store.isWorking(it.id)) "Pausing · keeping downloaded data" else "Paused · downloaded data kept")
        }
    }

    fun resume(context: Context, job: Download) {
        Store.init(context)
        val current = Store.downloads.value.find { it.id == job.id } ?: return
        if (current.state !in setOf("Paused", "Failed")) return
        Store.setQueuePaused(false)
        Store.change(job.id, durable = true) {
            it.copy(state = "Queued", detail = "Waiting to resume · source support required", nextAttemptAt = 0,
                retryCount = 0, uri = "", bytes = 0)
        }
        kick(context)
    }

    fun retryNow(context: Context, job: Download) {
        if (job.state == "Failed" || job.state == "Paused") return resume(context, job)
        Store.change(job.id, durable = true) {
            if (it.state == "Retrying") it.copy(state = "Queued", nextAttemptAt = 0, detail = "Retry requested") else it
        }
        kick(context)
    }

    fun startNow(context:Context,job:Download) {
        Store.change(job.id,true) {if(it.state in QueueRules.runnableStates)it.copy(scheduledAt=0,detail="Ready now · network and power requirements still apply") else it}
        kick(context)
    }
    fun next(context: Context, job: Download) {
        Store.change(job.id, durable = true) {
            if (it.state in QueueRules.runnableStates) it.copy(queueOrder = Store.firstQueueOrder() - 1) else it
        }
        kick(context)
    }

    fun cancel(context: Context, job: Download) {
        Store.init(context)
        Store.change(job.id, durable = true) {
            if (it.state == "Complete") it else it.copy(state = "Cancelled", detail = "Download cancelled", nextAttemptAt = 0)
        }
        // The active worker observes this state and stops its subprocess, then
        // acknowledges cleanup before letting the next queue item start.
        // Do not cancel a WorkRequest chain: that would cancel following items.
        if (!Store.isWorking(job.id)) io.launch {
            val current = Store.downloads.value.find { it.id == job.id }
            if (current?.state == "Cancelled") {
                runCatching { cleanupPending(context, current) }.onFailure {
                    Store.change(job.id, durable = true) { it.copy(detail = "Cancelled · restore folder access to clean up an unfinished save") }
                }
                // Cancelled IDs cannot be resumed. A later retry gets a new ID,
                // so this cleanup need not wait behind unrelated active work.
                runCatching { workDir(context, job.id).deleteRecursively() }
            }
        }
    }

    fun pauseAll(context: Context) {
        Store.setQueuePaused(true)
        Store.downloads.value.filterNot { it.terminal }.forEach { pause(context, it) }
    }

    fun resumeAll(context: Context) {
        Store.setQueuePaused(false)
        Store.downloads.value.filter { it.state == "Paused" }.forEach { job ->
            Store.change(job.id, durable = true) { it.copy(state = "Queued", nextAttemptAt = 0, detail = "Waiting in download queue") }
        }
        kick(context)
    }

    fun cancelAll(context: Context) {
        Store.downloads.value.filterNot { it.terminal }.forEach { cancel(context, it) }
        Store.setQueuePaused(false)
    }

    fun clearHistory(context: Context) {
        val old = Store.downloads.value.filter { it.terminal }
        Store.clearHistory()
        io.launch {
            // A failed cleanup can be retried after the user restores access.
            old.filter { it.pendingUri.isNotBlank() && !Store.isWorking(it.id) }.forEach { job ->
                runCatching { cleanupPending(context, job) }
            }
            Store.clearHistory()
            old.forEach { job ->
                if (Store.downloads.value.none { it.id == job.id }) runCatching { workDir(context, job.id).deleteRecursively() }
            }
        }
    }

    internal fun cleanupPending(context: Context, job: Download) {
        if (job.pendingUri.isBlank()) return
        runCatching {
            context.contentResolver.delete(Uri.parse(job.pendingUri), null, null)
            Store.change(job.id, durable = true) { if (it.pendingUri == job.pendingUri) it.copy(pendingUri = "") else it }
        }.getOrElse { throw IllegalStateException("Cannot clean up an unfinished save. Restore access to the download folder.", it) }
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        val job = Store.downloads.value.find { it.id == intent.getStringExtra("job") } ?: return
        when (intent.action) {
            "com.flux.downloader.PAUSE" -> Downloads.pause(context, job)
            "com.flux.downloader.RESUME" -> Downloads.resume(context, job)
            "com.flux.downloader.CANCEL" -> Downloads.cancel(context, job)
        }
    }
}
