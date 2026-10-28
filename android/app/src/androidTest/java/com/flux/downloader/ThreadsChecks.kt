package com.flux.downloader

import android.app.Instrumentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the bundled extractor in Android's actual Python runtime, with generated public-page data. */
class ThreadsChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    companion object {
        const val POST="https://www.threads.com/@creator/post/Fixture01"
        fun video()=InstagramPost.parse(JSONObject("""{"id":"Fixture01_200","title":"Threads fixture","thumbnail":"https://scontent.cdninstagram.com/cover.jpg","formats":[{"format_id":"video-0","ext":"mp4","vcodec":"unknown","acodec":"unknown"}]}"""),POST).single()
    }
    suspend fun run()=withContext(Dispatchers.IO) {
        val app=instrumentation.targetContext
        Engine.initialize(app)
        val script=File(app.cacheDir,"threads-runtime-check.py")
        val output=File(app.cacheDir,"threads-runtime-check.log")
        script.writeText("""
            import sys,json
            sys.path.insert(0,sys.argv[1])
            from flux_threads import FluxThreadsIE,install
            from yt_dlp import YoutubeDL
            install()
            url='https://www.threads.com/@creator/post/Fixture01'
            node={'code':'Fixture01','pk':'200','user':{'username':'creator'},'media_type':2,'video_versions':[{'url':'https://video.fbcdn.net/source.mp4'}],'image_versions2':{'candidates':[{'url':'https://scontent.cdninstagram.com/cover.jpg','width':1080,'height':1920}]}}
            html='<script type="application/json">'+json.dumps({'post':node})+'</script>'
            with YoutubeDL({'quiet':True}) as ydl:
                assert isinstance(ydl.get_info_extractor('FluxThreads'),FluxThreadsIE)
                ie=FluxThreadsIE(ydl)
                result=ie.parse_page(html,url)
                assert result['id']=='Fixture01_200' and result['thumbnails']
                assert result['formats'][0]['height'] is None
                result.update(extractor='FluxThreads',extractor_key='FluxThreads',webpage_url=url)
                result=ydl.process_ie_result(result,download=False)
                print(json.dumps(result))
        """.trimIndent())
        val packages=File(app.noBackupFilesDir,"youtubedl-android/packages")
        val builder=ProcessBuilder(File(app.applicationInfo.nativeLibraryDir,"libpython.so").absolutePath,
            script.absolutePath,File(app.noBackupFilesDir,"youtubedl-android/yt-dlp/yt-dlp").absolutePath)
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PYTHONHOME"]=File(packages,"python/usr").absolutePath
        builder.environment()["LD_LIBRARY_PATH"]="${File(packages,"python/usr/lib")}:${File(packages,"ffmpeg/usr/lib")}:${app.applicationInfo.nativeLibraryDir}"
        val process=builder.start()
        try {
            check(process.waitFor(45,TimeUnit.SECONDS)) { "Threads native runtime check timed out" }
            check(process.exitValue()==0) { output.readText().takeLast(2000) }
            val json=JSONObject(output.readLines().last {it.startsWith("{")})
            val video=InstagramPost.parse(json,POST).single()
            check(video.platform==MediaPlatform.Threads && video.thumbnail.startsWith("https://"))
            check(video.formats.single().label=="Source video" && video.formats.single().audio)
            val job=MediaDownloads.prepare(video,"")
            check(job.url==POST && job.mediaId=="Fixture01_200" && job.selector=="video-0")
            check(Download.from(job.json())==job)
            check(RepeatDownloads.sourceKey(job.url)=="threads:Fixture01")
            check(SourceFailures.message(POST,"HTTP Error 403")!!.contains("Threads"))
            report("PASS: Android native Python registers Threads extractor, parses exact public post and thumbnail, keeps unknown dimensions, and prepares durable selected-item download with source audio")
        } finally { if(process.isAlive)process.destroy();script.delete();output.delete() }
    }
}
