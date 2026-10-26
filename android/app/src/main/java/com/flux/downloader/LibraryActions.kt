package com.flux.downloader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LibraryActions {
    const val MAX_SELECTION = 100

    /** Grants only the selected content URIs, read-only, to the chosen target. */
    suspend fun shareIntent(context: Context, jobs: List<Download>): Intent = withContext(Dispatchers.IO) {
        require(jobs.isNotEmpty() && jobs.size <= MAX_SELECTION) { "Select between 1 and 100 saved files." }
        require(jobs.all { it.state == "Complete" && it.uri.isNotBlank() }) { "Only completed downloads can be shared." }
        val uris = jobs.map { Uri.parse(it.uri) }.distinct()
        require(uris.all { it.scheme == "content" }) { "This file cannot be shared securely. Download it again." }
        uris.forEach { uri ->
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                    ?: error("File unavailable")
            } catch (_: Exception) {
                throw IllegalStateException("A selected file is missing or access was removed. Check your download folder.")
            }
        }
        val types = jobs.map { it.mime }.distinct()
        val mime = when {
            types.size == 1 && types.single().contains('/') -> types.single()
            types.all { it.startsWith("audio/") } -> "audio/*"
            types.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, "Flux downloads", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun removeHistory(context: Context, ids: Set<String>): Int = withContext(Dispatchers.IO) {
        val removed = Store.removeHistory(ids)
        removed.forEach { job -> runCatching { Downloads.workDir(context, job.id).deleteRecursively() } }
        removed.size
    }
}
