package com.flux.downloader

import android.app.Instrumentation
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic only: no accounts, cookie export, third-party provider or release bypass. */
class InstagramDiagnostic(private val instrumentation: Instrumentation,private val report: (String)->Unit) {
    private val url="https://www.instagram.com/reel/DezDcSUR2Gg/"
    suspend fun run() {
        val raw="Instagram sent an empty media response. Check if this post is accessible without login; use cookies for authentication."
        val readable=SourceFailures.message(url,raw)!!
        check(readable.startsWith("Flux could not retrieve") && !readable.contains("requires login"))
        check(!SourceFailures.message(url,"HTTP Error 404")!!.startsWith("This Instagram post is unavailable"))
        check(SourceFailures.message(url,"HTTP Error 429")!!.contains("limiting"))
        check(SourceFailures.message(url,"login required")!!.contains("requires login"))
        report("PASS: empty metadata including boilerplate login advice is an extraction failure, not a private/unavailable-post diagnosis")
        var preview:Video?=null
        try {
            val items=withTimeout(90000) { Engine.search(instrumentation.targetContext,url) }
            preview=items.firstOrNull { !it.isPhoto }
            report("EXTRACTOR: metadata returned; ids=${items.map { it.id }}; items=${items.size}; formats=${items.sumOf { it.formats.size }}")
        } catch(error: Exception) {
            val category=if(error.message.orEmpty().contains("empty media response",true)) "EMPTY_MEDIA_RESPONSE" else error.javaClass.simpleName
            report("EXTRACTOR: no usable metadata for the reported public Reel; category=$category")
        }
        val activity=instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var browser: WebView?=null
        val finished=AtomicInteger(0)
        try {
            InstagramLiveCheck(instrumentation,report,sourceUrl=url,checkThumbnail=true,preview=preview).run()
            try {
                val control=withTimeout(90000) { Engine.search(instrumentation.targetContext,"https://www.instagram.com/reel/Chunk8-jurw/") }
                report("CONTROL: metadata returned; items=${control.size}; formats=${control.sumOf { it.formats.size }}")
            } catch(error: Exception) { report("CONTROL: metadata unavailable; category=${error.javaClass.simpleName}") }

            instrumentation.runOnMainSync {
                browser=WebView(activity).apply {
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=true
                    settings.allowFileAccess=false
                    settings.allowContentAccess=false
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient=object: WebViewClient() {
                        override fun onPageFinished(view: WebView?, location: String?) { finished.incrementAndGet() }
                        override fun shouldOverrideUrlLoading(view: WebView?,request: WebResourceRequest?): Boolean {
                            val uri=request?.url ?: return true
                            return uri.scheme!="https" || uri.host !in setOf("www.instagram.com","instagram.com")
                        }
                    }
                    activity.setContentView(this)
                    loadUrl(this@InstagramDiagnostic.url)
                }
                report("WEBVIEW: package=${WebView.getCurrentWebViewPackage()?.versionName.orEmpty()}")
            }
            for(index in 0 until 12) {
                delay(5000)
                val snapshot=CompletableDeferred<String>()
                instrumentation.runOnMainSync { browser!!.evaluateJavascript("""
                    JSON.stringify((()=>{const v=document.querySelector('video');const t=document.body?.innerText||'';
                    return {host:location.hostname,path:location.pathname,title:document.title,
                      videos:document.querySelectorAll('video').length,readyState:v?.readyState||0,videoWidth:v?.videoWidth||0,videoHeight:v?.videoHeight||0,
                      sourceKind:v?.currentSrc?.startsWith('https:')?'https':v?.currentSrc?.startsWith('blob:')?'blob':'none',
                      poster:!!v?.poster,ogImage:!!document.querySelector('meta[property="og:image"]'),
                      loginText:/log in|sign in/i.test(t),errorText:/something went wrong|page isn't available|page is not available/i.test(t)};})())
                """.trimIndent()) { snapshot.complete(it) } }
                val encoded=withTimeout(10000) { snapshot.await() }
                val decoded=JSONTokener(encoded).nextValue() as? String ?: "{}"
                val json=JSONObject(decoded)
                report("WEBVIEW ${index+1}: $json; pageFinishes=${finished.get()}")
                if(json.optInt("videoWidth")>0 && json.optInt("readyState")>=2)break
            }
            instrumentation.runOnMainSync { browser?.stopLoading();browser?.destroy();browser=null
                activity.setContentView(android.widget.TextView(activity).apply { text="Testing reported Reel download" }) }
        } finally {
            instrumentation.runOnMainSync { browser?.stopLoading();browser?.destroy();activity.finish() }
        }
    }
}
