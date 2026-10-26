package com.flux.downloader

import android.app.Instrumentation
import android.media.MediaExtractor
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Offline service -> native download/merge/save integration, no Instagram network. */
class ServerResolverChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    private val source="https://www.instagram.com/reel/ServerDemo01/"
    private val token="fixture_"+"a".repeat(36)
    suspend fun run() {
        Store.init(app);Store.setQueuePaused(true)
        WorkManager.getInstance(app).cancelAllWork().result.get()
        withTimeout(60000) {Downloads.awaitIdle()}
        Store.downloads.value.filterNot {it.terminal}.forEach {job -> Store.change(job.id,true) {it.copy(state="Cancelled")}}
        InstagramSession.clear(app);ServerResolver.clear(app)
        for(address in listOf("http://flux.example.com","https://flux.example.com/path","https://user:pass@flux.example.com","https://flux.example.com?key=secret","https://127.0.0.1","https://flux.local")) {
            check(runCatching {ServerResolver.validateOrigin(address)}.isFailure)
        }
        Fixture().use {server ->
            check(runCatching {ServerResolver.connect(app,server.origin,"x".repeat(40))}.isFailure)
            check(!ServerResolver.enabled(app))
            ServerResolver.connect(app,server.origin,token)
            val encrypted=app.getSharedPreferences("flux_resolver",0).getString("encrypted","").orEmpty()
            check(encrypted.isNotEmpty() && !encrypted.contains(token))
            check(java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("flux.resolver.v1",null).encoded==null)
            check(ServerResolver.resolve(app,"https://www.youtube.com/watch?v=fixture")==null)
            check(server.resolves.get()==0)
            val videos=Engine.search(app,source)
            check(videos.single().id=="ServerItem01" && videos.single().url==source && videos.single().formats.size==2)
            check(videos.single().thumbnail=="${server.origin}/thumb.jpg")
            val before=server.resolves.get()
            val lease=ServerResolver.downloadInfo(app,source,"ServerItem01")!!
            check(android.system.Os.stat(lease.file.absolutePath).st_mode and 63==0)
            check(!lease.file.readText().contains(token))
            check(runCatching {androidx.core.content.FileProvider.getUriForFile(app,"${app.packageName}.files",lease.file)}.isFailure)
            lease.close();check(!lease.file.exists())
            val video=videos.single()
            val job=MediaDownloads.prepare(video,"",video.formats.first(),video.formats.last())
            Store.add(job);Store.setQueuePaused(false)
            WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().build())
            val done=withTimeout(120000) {
                while(true) {
                    val current=Store.downloads.value.first {it.id==job.id}
                    check(current.state!="Failed") {current.detail}
                    if(current.state=="Complete")return@withTimeout current
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            check(server.resolves.get()>=before+2) // selected-item lease plus fresh metadata at download start
            check(done.url==source && done.mediaId=="ServerItem01" && !done.json().toString().contains(token))
            val media=MediaExtractor()
            try {
                media.setDataSource(app,Uri.parse(done.uri),null)
                val types=(0 until media.trackCount).map {media.getTrackFormat(it).getString("mime").orEmpty()}
                check(types.any {it.startsWith("video/")} && types.any {it.startsWith("audio/")})
            } finally {media.release()}
            check(!server.credentialLeaked && !server.instagramCookieSeen)
            check(java.io.File(app.noBackupFilesDir,"resolver-info").listFiles().orEmpty().isEmpty())
            server.mode="wrong-source";check(runCatching {Engine.search(app,source)}.isFailure)
            server.mode="unsafe-url";check(runCatching {Engine.search(app,source)}.isFailure)
            server.mode="denied";check(runCatching {Engine.search(app,source)}.exceptionOrNull()?.message?.contains("Flux server")==true)
            server.mode="redirect";check(runCatching {Engine.search(app,source)}.exceptionOrNull()?.message?.contains("redirected")==true)
            ServerResolver.clear(app)
            check(!ServerResolver.enabled(app))
            check(!java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias("flux.resolver.v1"))
            Store.setQueuePaused(true);withTimeout(60000) {Downloads.awaitIdle()}
        }
        report("PASS: encrypted backend key, HTTPS-origin validation, YouTube credential exclusion, source binding, server metadata refresh, selected-item lease protection, actual separate video/audio download and merge/save, token/cookie exclusion from CDN and history, redirect/unsafe URL/rejected source handling and cleanup")
    }
    private inner class Fixture:AutoCloseable {
        private val socket=ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"))
        private val pool=Executors.newCachedThreadPool()
        @Volatile private var running=true
        @Volatile var mode="normal"
        @Volatile var credentialLeaked=false
        @Volatile var instagramCookieSeen=false
        val resolves=AtomicInteger(0)
        val origin="http://127.0.0.1:${socket.localPort}"
        init {pool.execute {
            while(running)try {val client=socket.accept();pool.execute {runCatching {client.use {connection ->
                connection.soTimeout=10000
                val reader=connection.getInputStream().bufferedReader()
                val request=reader.readLine().split(' ');val path=request[1]
                val headers=mutableMapOf<String,String>()
                while(true) {val line=reader.readLine().orEmpty();if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val size=headers["content-length"]?.toIntOrNull() ?: 0
                val body=CharArray(size);var read=0
                while(read<size) {val n=reader.read(body,read,size-read);if(n<0)break;read+=n}
                if(headers.containsKey("cookie"))instagramCookieSeen=true
                var status="200 OK";var type="application/json";var extra=""
                val data=when {
                    path.startsWith("/v1/") && headers["authorization"]!="Bearer $token" -> {status="401 Unauthorized";"{}".toByteArray()}
                    path=="/v1/status" -> """{"service":"flux-resolver","protocol":1,"authentication":"no-instagram-login"}""".toByteArray()
                    path=="/v1/resolve" -> {
                        resolves.incrementAndGet();check(JSONObject(String(body)).getString("url")==source)
                        when(mode) {
                            "denied" -> {status="422 Unprocessable Entity";"{}".toByteArray()}
                            "redirect" -> {status="302 Found";extra="Location: $origin/forbidden\r\n";byteArrayOf()}
                            else -> JSONObject().put("protocol",1).put("expires_in",120).put("source_url",if(mode=="wrong-source")"https://www.instagram.com/reel/Different01/" else source)
                                .put("info",JSONObject().put("id","ServerItem01").put("thumbnail","$origin/thumb.jpg").put("title","Server integration video").put("duration",6)
                                    .put("formats",JSONArray().put(format("v","video.mp4","mp4","avc1","none")).put(format("a","audio.m4a","m4a","none","mp4a.40.2"))))
                                .toString().toByteArray()
                        }
                    }
                    path in setOf("/video.mp4","/audio.m4a") -> {
                        if(headers.containsKey("authorization"))credentialLeaked=true
                        type="application/octet-stream";instrumentation.context.assets.open(path.drop(1)).use {it.readBytes()}
                    }
                    else -> {status="404 Not Found";"{}".toByteArray()}
                }
                connection.getOutputStream().apply {write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray());if(request[0]!="HEAD")write(data);flush()}
            }}}} catch(_:Exception) { }
        }}
        private fun format(id:String,file:String,ext:String,vcodec:String,acodec:String)=JSONObject()
            .put("format_id",id).put("url",if(mode=="unsafe-url")"https://127.0.0.1/private" else "$origin/$file")
            .put("protocol","https").put("ext",ext).put("vcodec",vcodec).put("acodec",acodec).put("height",if(vcodec!="none")360 else 0).put("abr",if(acodec!="none")128 else 0)
        override fun close() {running=false;socket.close();pool.shutdownNow()}
    }
}
