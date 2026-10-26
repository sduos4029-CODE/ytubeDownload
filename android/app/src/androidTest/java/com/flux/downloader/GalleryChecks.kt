package com.flux.downloader

import android.app.Instrumentation
import android.graphics.Bitmap
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

class GalleryChecks(private val instrumentation: Instrumentation, private val report: (String)->Unit) {
    private val app get() = instrumentation.targetContext
    companion object {
        const val POST="https://www.instagram.com/p/Gallery01/"
        fun items(): List<Video> = InstagramPost.parse(JSONObject("""{"_type":"playlist","entries":[
            {"id":"PhotoOne","title":"Gallery photo one","flux_media_kind":"photo","formats":[{"format_id":"photo-small","ext":"jpg","width":160,"height":90,"vcodec":"mjpeg","acodec":"none"},{"format_id":"photo-large","ext":"jpg","width":640,"height":360,"vcodec":"mjpeg","acodec":"none"}]},
            {"id":"VideoTwo","title":"Gallery video two","formats":[{"format_id":"video","ext":"mp4","width":720,"height":1280,"vcodec":"avc1","acodec":"mp4a.40.2"}]},
            {"id":"PhotoThree","title":"Gallery photo three","flux_media_kind":"photo","formats":[{"format_id":"photo","ext":"webp","width":480,"height":640,"vcodec":"mjpeg","acodec":"none"}]}]}"""),POST)
    }
    suspend fun run(restart: Boolean=false) {
        Store.init(app)
        if(restart) {
            val saved=Store.downloads.value.first { it.id=="gallery-saved" }
            check(saved.mediaId=="PhotoOne" && saved.isPhoto && saved.url==POST && saved.favourite)
            check(LibraryDetails.inspect(app,saved).available)
            check(app.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { android.graphics.BitmapFactory.decodeStream(it) }!=null)
            report("PASS: saved photo bytes, MIME, selected item identity, favourite and post source persist after force-stop")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) { Downloads.awaitIdle() }
        Store.downloads.value.filterNot { it.terminal }.forEach { old -> Store.change(old.id,true) { it.copy(state="Cancelled") } }
        Store.setQueuePaused(true);Store.setTransfers(TransferPreferences(1,false))
        val items=items();check(items.map { it.itemIndex }==listOf(1,2,3) && items.all { it.itemCount==3 })
        check(items[0].isPhoto && !items[1].isPhoto && items[2].isPhoto)
        val photo=MediaDownloads.prepare(items[0],"")
        check(photo.selector=="photo-large" && photo.mediaId=="PhotoOne" && !photo.audioOnly && !photo.replaceEmbeddedAudio)
        val persisted=Download.from(photo.json());check(persisted==photo)
        val legacy=photo.json().apply { remove("mediaKind");remove("mediaId") }
        check(Download.from(legacy).mediaKind=="video" && Download.from(legacy).mediaId.isEmpty())
        val other=MediaDownloads.prepare(items[2],"")
        check(RepeatDownloads.matching(listOf(other),photo)==null)
        check(runCatching { InstagramPost.parse(JSONObject("""{"entries":[]} """),POST) }.isFailure)
        val duplicate=JSONObject("""{"entries":[{"id":"same","formats":[{"format_id":"v","ext":"mp4","vcodec":"avc1"}]},{"id":"same","formats":[{"format_id":"v","ext":"mp4","vcodec":"avc1"}]}]}""")
        check(runCatching { InstagramPost.parse(duplicate,POST) }.isFailure)
        val unavailable=SourceFailures.message(POST,"Instagram sent an empty media response. Try login or cookies for authentication.")!!
        check(unavailable.startsWith("Flux could not retrieve") && !unavailable.contains("requires login"))
        check(SourceFailures.message(POST,"HTTP Error 429")!!.contains("limiting"))
        check(SourceFailures.message(POST,"login required")!!.contains("sign-in"))
        Fixture().use { server ->
            // Only transport and selector use the generated loopback fixture;
            // source parsing and stable-item selection are checked separately.
            val transfer=photo.copy(url=server.url("photo.jpg"),selector="best",mediaId="")
            Store.add(transfer);Store.setQueuePaused(false)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
            val done=await(transfer.id,"Complete")
            val bytes=app.contentResolver.openInputStream(Uri.parse(done.uri))!!.use { it.readBytes() }
            check(bytes.contentEquals(server.jpeg) && done.mime=="image/jpeg" && done.isPhoto)
            val saved=done.copy(id="gallery-saved",url=POST,title="Saved gallery photo",mediaId="PhotoOne",favourite=true)
            Store.add(saved)
            check(libraryJobs(listOf(saved),"","Photos",LibrarySort.Newest)==listOf(saved))
            check(libraryJobs(listOf(saved),"","Video",LibrarySort.Newest).isEmpty())
            val share=LibraryActions.shareIntent(app,listOf(saved));check(share.type=="image/jpeg")
            check(share.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION==0)
            val repeat=RepeatDownloads.prepare(saved,"");check(repeat.mediaId==saved.mediaId && repeat.isPhoto)
            val broken=transfer.copy(id=java.util.UUID.randomUUID().toString(),url=server.url("broken.jpg"),state="Queued")
            Store.add(broken)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
            val failed=await(broken.id,"Failed");check(failed.uri.isBlank() && failed.retryCount==0)
            Store.setQueuePaused(true)
            withTimeout(60000) { Downloads.awaitIdle() }
        }
        report("PASS: mixed carousel order, best original photo choice, stable item dedup/repeat, legacy ledger migration, byte-exact JPEG transfer/save/share, photo filters and malformed-image rejection")
    }
    private suspend fun await(id: String,state: String): Download=withTimeout(90000) {
        while(true) {
            val job=Store.downloads.value.first { it.id==id }
            if(job.state==state)return@withTimeout job
            check(job.state!="Failed") { job.detail }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private class Fixture: AutoCloseable {
        val jpeg=ByteArrayOutputStream().also { out ->
            val bitmap=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.MAGENTA)
            bitmap.compress(Bitmap.CompressFormat.JPEG,94,out);bitmap.recycle()
        }.toByteArray()
        private val socket=ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"))
        private val pool=Executors.newCachedThreadPool()
        @Volatile private var running=true
        init { pool.execute { while(running) try { val client=socket.accept();pool.execute {
            client.use { connection ->
                val reader=connection.getInputStream().bufferedReader();val first=reader.readLine().orEmpty()
                while(!reader.readLine().isNullOrEmpty()) { }
                val bytes=if(first.contains("broken.jpg")) "invalid photo".toByteArray() else jpeg
                // Generic extractor fixture uses a media MIME to accept this
                // direct URL. JPEG bytes and extension remain real and checked.
                val header="HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                connection.getOutputStream().apply { write(header.toByteArray());if(!first.startsWith("HEAD"))write(bytes);flush() }
            }
        }} catch(_: Exception) { } } }
        fun url(name: String)="http://127.0.0.1:${socket.localPort}/$name"
        override fun close() {running=false;socket.close();pool.shutdownNow()}
    }
}
