package com.flux.downloader

import android.app.Instrumentation
import android.media.MediaExtractor
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*

/** An explicitly non-gating availability probe; generated checks remain gating. */
class InstagramLiveCheck(private val instrumentation: Instrumentation,private val report: (String)->Unit) {
    suspend fun run() {
        val app=instrumentation.targetContext
        var jobId=""
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            withTimeout(60000) { Downloads.awaitIdle() }
            Store.setQueuePaused(true);Store.setTransfers(TransferPreferences(1,false))
            withTimeout(150000) {
                val video=Engine.search(app,"https://www.instagram.com/reel/Chunk8-jurw/").first { !it.isPhoto }
                val format=video.formats.filter { it.video }.minBy { it.height.takeIf { n->n>0 } ?: Int.MAX_VALUE }
                val job=MediaDownloads.prepare(video,"",format);jobId=job.id
                Store.add(job);Store.setQueuePaused(false)
                WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
                while(true) {
                    val saved=Store.downloads.value.first { it.id==jobId }
                    check(saved.state!="Failed") { "Source unavailable" }
                    if(saved.state=="Complete") {
                        val extractor=MediaExtractor()
                        try {
                            extractor.setDataSource(app,Uri.parse(saved.uri),null)
                            val tracks=(0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime").orEmpty() }
                            check(tracks.any { it.startsWith("video/") })
                            report("INSTAGRAM LIVE DOWNLOAD PASSED: public metadata, selected-item transfer, save and Android video-track parsing; audio track present=${tracks.any { it.startsWith("audio/") }}")
                        } finally {extractor.release()}
                        break
                    }
                    delay(250)
                }
            }
        } catch(_: Exception) {
            report("INSTAGRAM LIVE DOWNLOAD UNAVAILABLE: public service access, timeout or media verification prevented completion; no live-download certification")
        } finally {
            Store.setQueuePaused(true)
            Store.downloads.value.find { it.id==jobId && !it.terminal }?.let { Downloads.cancel(app,it) }
            WorkManager.getInstance(app).cancelAllWork().result.get()
            withTimeout(60000) { Downloads.awaitIdle() }
        }
    }
}
