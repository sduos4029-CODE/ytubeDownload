package com.flux.downloader

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PhotoSheet(video: Video, onDownload: () -> Unit) {
    val format=MediaDownloads.best(video)
    var zoom by remember(video.key) { mutableFloatStateOf(1f) }
    Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.85f).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Photo preview",style=MaterialTheme.typography.headlineSmall)
        Text(video.title,maxLines=2)
        Box(Modifier.fillMaxWidth().height(320.dp).clipToBounds().background(Color.Black)
            .pointerInput(video.key) { detectTransformGestures { _,_,scale,_ -> zoom=(zoom*scale).coerceIn(1f,4f) } }) {
            AsyncImage(video.thumbnail,"Photo preview for ${video.title}",Modifier.fillMaxSize().graphicsLayer(scaleX=zoom,scaleY=zoom),contentScale=ContentScale.Fit)
        }
        TextButton(onClick={zoom=1f}) {Text("Reset zoom")}
        Text("${format.label} · source ${format.extension.uppercase()} · no conversion. Pinch to zoom the preview.")
        Text("Saves the best image returned by the source. Original camera quality may not be available.",style=MaterialTheme.typography.bodySmall)
        Button(onClick=onDownload,modifier=Modifier.fillMaxWidth()) {Text("Download photo")}
    }
}
