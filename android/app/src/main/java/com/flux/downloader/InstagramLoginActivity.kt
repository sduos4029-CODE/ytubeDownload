package com.flux.downloader

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstagramLoginActivity: ComponentActivity() {
    private var browser:WebView?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        InstagramBrowser.opened(applicationContext)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFBAACFF),surface=Color(0xFF171822),background=Color(0xFF0C0D13))) {
                val scope=rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf<String?>(null) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(enabled=!busy,onClick={finish()}) {Text("Close Instagram")}
                            TextButton(enabled=!busy,onClick={browser?.loadUrl(LOGIN)}) {Text("Reload login")}
                        }
                        Text("Connect Instagram",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(horizontal=16.dp))
                        Text("Sign in on Instagram's own page, then tap Use this connection. Flux stores the session encrypted on this device and sends it only to Instagram.",
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(16.dp))
                        AndroidView(modifier=Modifier.weight(1f).fillMaxWidth(),factory={ context ->
                            WebView(context).also { view ->
                                browser=view
                                view.settings.apply {
                                    javaScriptEnabled=true;domStorageEnabled=true
                                    allowFileAccess=false;allowContentAccess=false
                                    mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
                                    safeBrowsingEnabled=true
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(view,false)
                                view.webViewClient=object:WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?):Boolean {
                                        val uri=request?.url ?: return true
                                        if(uri.scheme!="https")return true
                                        if(request.isForMainFrame && uri.host !in setOf("instagram.com","www.instagram.com","m.instagram.com")) {
                                            Toast.makeText(this@InstagramLoginActivity,"Use Instagram's sign-in page in this window.",Toast.LENGTH_SHORT).show()
                                            return true
                                        }
                                        return false
                                    }
                                }
                                // No JavaScript bridge, password/DOM interception, SSL
                                // exception handler, external provider or cookie upload.
                                view.loadUrl(LOGIN)
                            }
                        })
                        message?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
                        Button(enabled=!busy,modifier=Modifier.fillMaxWidth().padding(16.dp),onClick={
                            val userAgent=browser?.settings?.userAgentString
                            val header=CookieManager.getInstance().getCookie("https://www.instagram.com/").orEmpty()
                            busy=true;message=null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {InstagramSession.save(applicationContext,header,userAgent)}
                                    Toast.makeText(this@InstagramLoginActivity,"Instagram connection saved. Try your link again.",Toast.LENGTH_LONG).show()
                                    finish()
                                }catch(_: Exception) {message="Finish signing in on Instagram first. If saving still fails, pause Instagram requests and try again."}
                                finally {busy=false}
                            }
                        }) {Text(if(busy) "Saving connection…" else "Use this connection")}
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        browser?.let {view -> view.stopLoading();view.clearCache(true);(view.parent as? android.view.ViewGroup)?.removeView(view);view.destroy()}
        browser=null;InstagramBrowser.clear(applicationContext);super.onDestroy()
    }
    companion object {private const val LOGIN="https://www.instagram.com/accounts/login/"}
}
