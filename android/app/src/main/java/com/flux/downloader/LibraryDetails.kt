package com.flux.downloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedFileDetails(val available: Boolean, val name: String? = null, val bytes: Long? = null)

object LibraryDetails {
    suspend fun inspect(context: Context, job: Download): SavedFileDetails = withContext(Dispatchers.IO) {
        if (job.state != "Complete" || job.uri.isBlank()) return@withContext SavedFileDetails(false)
        val uri = Uri.parse(job.uri)
        if (uri.scheme != "content") return@withContext SavedFileDetails(false)
        var name: String? = null
        var bytes: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val b = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                    if (b >= 0 && !cursor.isNull(b)) bytes = cursor.getLong(b).takeIf { it >= 0 }
                }
            }
        }
        val available = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (bytes == null) bytes = descriptor.length.takeIf { it >= 0 }
                true
            } ?: false
        }.getOrDefault(false)
        SavedFileDetails(available, name, bytes)
    }
    fun source(url: String): String? = MediaLinks.parse(url)?.url
}

@Composable
fun LibraryDetailsDialog(job: Download, onDownloadAgain: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var details by remember(job.id, job.uri) { mutableStateOf<SavedFileDetails?>(null) }
    var checking by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(job.id, job.uri, refresh) {
        checking = true
        try { details = LibraryDetails.inspect(context, job) } finally { checking = false }
    }
    val source = LibraryDetails.source(job.url)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("File details") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(job.title, fontWeight = FontWeight.Bold)
            if (job.favourite) Text("★ Favourite", color = MaterialTheme.colorScheme.primary)
            Text("Status: ${job.state}")
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                Text(if (job.uri.isBlank()) "No saved file" else if (details?.available == true) "File available" else "File unavailable", color = if (details?.available == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (job.uri.isNotBlank() && details?.available != true) Text("The file may have been moved or deleted, be offline, or need folder access again.")
                if (job.state == "Complete" && details?.available != true && RepeatDownloads.sourceKey(job.url) != null) {
                    TextButton(onClick = onDownloadAgain) { Text("Download replacement") }
                }
            }
            Text("File name: ${details?.name ?: "Not reported"}")
            Text("Reported size: ${details?.bytes?.let(::sizeLabel) ?: "Unknown"}")
            if (job.bytes > 0) Text("Recorded size: ${sizeLabel(job.bytes)}")
            Text("Saved format: ${job.mime.ifBlank { "Not reported" }}")
            Text("Quality: ${job.label}")
            if(job.isLocalEdit)Text("Created locally: ${job.editOperation} · original file kept")
            Text("Verification: ${job.verification.ifBlank {"Not checked by this version"}}")
            Text("Track checks do not guarantee playback on every decoder.")
            if(job.collection.isNotBlank())Text("Collection: ${job.collection}")
            if(job.tags.isNotBlank())Text("Tags: ${job.tags}")
            if(job.subtitleLanguage.isNotBlank())Text("Requested embedded subtitles: ${job.subtitleLanguage}")
            Text("Duration: ${if(job.isPhoto) "Not applicable" else if (job.duration > 0) durationLabel(job.duration) else "Not reported"}")
            Text("Recorded audio: ${if(job.isPhoto) "Not applicable" else job.audioDescription.ifBlank { "Not reported" }}")
            Text("Added: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(job.created))}")
            Text("Destination: ${if (job.folder.isBlank()) "Default download folder" else "Selected download folder"}")
            if (source != null) {
                Text("Source: $source")
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Video source", source))
                    Toast.makeText(context, "Source link copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy source link") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close details") } },
        dismissButton = { TextButton(enabled = !checking, onClick = { refresh++ }) { Text("Check file again") } })
}
