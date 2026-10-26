package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ServerConnectionCard() {
    val context=LocalContext.current
    val revision by ServerResolver.revision.collectAsState()
    var address by remember(revision) {mutableStateOf(ServerResolver.origin(context))}
    var token by remember {mutableStateOf("")}
    var editing by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Public Instagram server",style=MaterialTheme.typography.titleMedium)
            Text(if(ServerResolver.enabled(context))"Server connected · no Instagram login" else "Preview · hosting not connected",color=MaterialTheme.colorScheme.primary)
            Text("Flux can ask your hosted server for public post details, thumbnails and download formats. Instagram credentials are never sent to this service.",style=MaterialTheme.typography.bodySmall)
            Text("A running Flux server is required. This preview does not include hosting. The server sees pasted Instagram links and your IP address; connect only to a host you trust.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy,onClick={editing=!editing}) {Text(if(editing)"Hide server setup" else "Set up server")}
            if(editing) {
            OutlinedTextField(value=address,onValueChange={address=it},label={Text("Server HTTPS address")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=token,onValueChange={token=it},label={Text("Server access key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
            Button(enabled=!busy && address.isNotBlank() && token.isNotBlank(),onClick={
                busy=true;message=null
                scope.launch {
                    try {withContext(Dispatchers.IO) {ServerResolver.connect(context,address,token)};token="";message="Server verified and saved. Search the public post again."}
                    catch(e:Exception) {message=e.message ?: "Unable to connect to the Flux server."}
                    finally {busy=false}
                }
            }) {Text(if(busy)"Checking server…" else "Verify and connect")}
            }
            if(ServerResolver.enabled(context))TextButton(enabled=!busy,onClick={ServerResolver.clear(context);token="";message="Server disconnected. New requests use direct retrieval."}) {Text("Disconnect server")}
            message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        }
    }
}
