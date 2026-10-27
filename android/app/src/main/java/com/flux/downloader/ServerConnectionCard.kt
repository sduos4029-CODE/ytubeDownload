package com.flux.downloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun ServerConnectionCard() {
    val context=LocalContext.current
    val revision by ServerResolver.revision.collectAsState()
    val status by ServerResolver.status.collectAsState()
    var address by remember(revision) {mutableStateOf(ServerResolver.origin(context))}
    var token by remember {mutableStateOf("")}
    var editing by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var action by remember {mutableStateOf<Job?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    val saved=ServerResolver.enabled(context)
    val scope=rememberCoroutineScope()
    fun open(url:String) {
        runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
            .onFailure {message="Open the Flux hosting guide from the download page in your browser."}
    }
    fun check(save:Boolean) {
        busy=true;message=null
        action=scope.launch {
            try {
                if(save) {
                    withContext(Dispatchers.IO) {ServerResolver.connect(context,address,token)}
                    token="";message="Server reachable and access key saved. Search a public post to test Instagram retrieval."
                }else {
                    withContext(Dispatchers.IO) {ServerResolver.testConnection(context)}
                    message="Server reachable and access key accepted. This does not verify Instagram downloads."
                }
            }catch(cancel:CancellationException) {message="Server check cancelled.";throw cancel}
            catch(error:Exception) {message=error.message ?: "Unable to check the Flux server."}
            finally {busy=false;action=null}
        }
    }
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Public Instagram server",style=MaterialTheme.typography.titleMedium)
            Text(if(!saved)"No server configured" else if(status.origin==ServerResolver.origin(context))status.label else "Saved server · not checked this session",
                color=MaterialTheme.colorScheme.primary)
            Text(if(!saved)"Direct Instagram access is in use. A public post can still fail on this connection."
                else "The address and access key are saved on this device. A connection check tests the service, not Instagram availability.",style=MaterialTheme.typography.bodySmall)
            Text("Hosting is not included. Use Google Cloud Run first, or Render as a fallback. Deploy your backend, then enter its HTTPS address and server access key. No Instagram login is requested.",style=MaterialTheme.typography.bodySmall)
            Text("The server sees pasted post links and your IP address. Connect only to a host you trust.",style=MaterialTheme.typography.bodySmall)
            if(busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if(status.phase=="Waking")"Server may be starting · waiting up to 90 seconds" else "Checking service and access key…",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={action?.cancel()}) {Text("Cancel server check")}
            }
            FilledTonalButton(enabled=saved && !busy,onClick={check(false)}) {Text("Test saved server")}
            TextButton(enabled=!busy,onClick={open("https://github.com/sduos4029-CODE/ytubeDownload/blob/flux/2.2.2-google-first/HOSTING.md")}) {Text("Open setup guide")}
            TextButton(enabled=!busy,onClick={open("https://github.com/sduos4029-CODE/ytubeDownload/blob/flux/2.2.2-google-first/server/GOOGLE_CLOUD.md")}) {Text("1. Google Cloud Run setup")}
            Text("Google Cloud requires a billing-enabled project. Usage charges can apply.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy,onClick={open("https://render.com/deploy?repo=https%3A%2F%2Fgithub.com%2Fsduos4029-CODE%2FytubeDownload%2Ftree%2Fflux%2F2.2-server-preview")}) {Text("2. Render fallback setup")}
            TextButton(enabled=!busy,onClick={editing=!editing}) {Text(if(editing)"Hide server setup" else "Set up server")}
            TextButton(onClick={
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Flux server status",ServerResolver.diagnostics(context)))
                Toast.makeText(context,"Connection status copied · no keys or links",Toast.LENGTH_SHORT).show()
            }) {Text("Copy connection status")}
            if(editing) {
                OutlinedTextField(value=address,onValueChange={address=it},label={Text("Server HTTPS address")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=token,onValueChange={token=it},label={Text("Server access key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!busy,modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy && address.isNotBlank() && token.isNotBlank(),onClick={check(true)}) {Text("Verify and connect")}
            }
            if(saved)TextButton(enabled=!busy,onClick={ServerResolver.clear(context);token="";message="Server disconnected. New requests use direct retrieval."}) {Text("Disconnect server")}
            message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
