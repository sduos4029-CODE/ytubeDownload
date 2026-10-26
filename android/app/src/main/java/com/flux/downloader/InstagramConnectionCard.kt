package com.flux.downloader

import android.content.Intent
import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun InstagramConnectionCard() {
    val context=LocalContext.current
    val connected by InstagramSession.connected.collectAsState()
    val requests by InstagramSession.active.collectAsState()
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Instagram connection",style=MaterialTheme.typography.titleMedium)
            Text(if(connected) "Connection saved on this device" else "Not connected",color=MaterialTheme.colorScheme.primary)
            Text("If a public post fails, an optional connection lets Flux try Instagram's signed-in retrieval method.",style=MaterialTheme.typography.bodySmall)
            Text("Your session stays on this phone. It is not uploaded to a Flux server. Instagram can still limit or reject requests.",style=MaterialTheme.typography.bodySmall)
            if(requests>0)Text("Wait for the current Instagram request to finish or pause it before changing the connection.",style=MaterialTheme.typography.bodySmall)
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(modifier=Modifier.fillMaxWidth(),enabled=!busy && requests==0,onClick={context.startActivity(Intent(context,InstagramLoginActivity::class.java))}) {
                    Text(if(connected) "Reconnect Instagram" else "Connect Instagram")
                }
                if(connected) TextButton(enabled=!busy && requests==0,onClick={
                    busy=true;error=null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {InstagramSession.clear(context)}
                            val cleared=kotlinx.coroutines.CompletableDeferred<Unit>()
                            CookieManager.getInstance().removeAllCookies {CookieManager.getInstance().flush();cleared.complete(Unit)}
                            kotlinx.coroutines.withTimeout(10000) {cleared.await()}
                            android.webkit.WebStorage.getInstance().deleteAllData()
                        }catch(_:Exception) {error="Could not disconnect. Pause Instagram requests and try again."}
                        finally {busy=false}
                    }
                }) {Text("Disconnect")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
