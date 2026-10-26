package com.flux.downloader

import android.app.Instrumentation
import android.media.MediaExtractor
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*

/** An explicitly non-gating availability probe; generated checks remain gating. */
class InstagramLiveCheck(private val instrumentation: Instrumentation,private val report: (String)->Unit,
    private val sourceUrl: String="https://www.instagram.com/reel/Chunk8-jurw/",private val checkThumbnail: Boolean=false,private val preview: Video?=null) {
    suspend fun run() {
        val app=instrumentation.targetContext
        var jobId=""
        var stage="METADATA"
        try {
            WorkManager.getInstance(app).cancelAllWork().result.get()
            withTimeout(60000) { Downloads.awaitIdle() }
            Store.setQueuePaused(true);Store.setTransfers(TransferPreferences(1,false))
            withTimeout(150000) {
                val video=preview ?: Engine.search(app,sourceUrl).first { !it.isPhoto }
                check(video.id==MediaLinks.parse(sourceUrl)?.postId) { "Source identity mismatch" }
                report("LIVE SOURCE: id=${video.id}; formats=${video.formats.size}; reportedAudio=${video.formats.any { it.audio }}")
                if(checkThumbnail) {
                    stage="THUMBNAIL"
                    check(video.thumbnail.startsWith("https://")) { "No thumbnail URL" }
                    val loader=coil.ImageLoader(app)
                    try {
                        val image=loader.execute(coil.request.ImageRequest.Builder(app).data(video.thumbnail).size(320).allowHardware(false).build())
                        check(image is coil.request.SuccessResult) { "Thumbnail did not decode" }
                        report("LIVE THUMBNAIL PASSED: source image fetched and decoded by the app image loader")
                    } finally {loader.shutdown()}
                }
                stage="TRANSFER"
                val format=video.formats.filter { it.video }.minBy { it.height.takeIf { n->n>0 } ?: Int.MAX_VALUE }
                val job=MediaDownloads.prepare(video,"",format);jobId=job.id
                Store.add(job);Store.setQueuePaused(false)
                WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
                while(true) {
                    val saved=Store.downloads.value.first { it.id==jobId }
                    check(saved.state!="Failed") { "Source unavailable" }
                    if(saved.state=="Complete") {
                        stage="SAVED_TRACKS"
                        val extractor=MediaExtractor()
                        try {
                            extractor.setDataSource(app,Uri.parse(saved.uri),null)
                            val tracks=(0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString("mime").orEmpty() }
                            check(tracks.any { it.startsWith("video/") })
                            check(!video.formats.any { it.audio } || tracks.any { it.startsWith("audio/") }) { "Reported source audio missing from output" }
                            report("INSTAGRAM LIVE DOWNLOAD PASSED: public metadata, selected-item transfer, save and Android video-track parsing; audio track present=${tracks.any { it.startsWith("audio/") }}")
                        } finally {extractor.release()}
                        break
                    }
                    delay(250)
                }
            }
        } catch(error: Exception) {
            report("INSTAGRAM LIVE DOWNLOAD UNAVAILABLE: stage=$stage; category=${error.javaClass.simpleName}; no live-download certification")
        } finally {
            Store.setQueuePaused(true)
            Store.downloads.value.find { it.id==jobId && !it.terminal }?.let { Downloads.cancel(app,it) }
            WorkManager.getInstance(app).cancelAllWork().result.get()
            withTimeout(60000) { Downloads.awaitIdle() }
        }
    }
}
