package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun RepeatDownloadDialog(job: Download, onDismiss: () -> Unit, onConfirm: () -> Unit, onShowExisting: () -> Unit) {
    val context = LocalContext.current
    val all by Store.downloads.collectAsState()
    val matching = RepeatDownloads.matching(all, job)
    var checking by remember { mutableStateOf(true) }
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(job.id, job.uri) {
        try { available = LibraryDetails.inspect(context, job).available } finally { checking = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Download again?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(job.title)
            if (matching != null) Text("A matching download is already active or paused. Open it instead of creating a duplicate.")
            else {
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(if (available) "The saved file is still available. This will create an additional copy." else "The saved file could not be opened. It may be missing, offline, or need folder access again.")
                Text("Use the same quality selection and your current download folder. Existing files, history and favourites stay unchanged.")
                Text("If this format is no longer available, choose a current format in Discover. A paused queue stays paused.")
            }
        }
    }, confirmButton = { TextButton(enabled = RepeatDownloads.sourceKey(job.url) != null && job.selector.isNotBlank(), onClick = {
        if (matching != null) onShowExisting() else onConfirm()
    }) {
        Text(if (matching != null) "Show existing download" else "Queue new download")
    } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Keep current file") } })
}
