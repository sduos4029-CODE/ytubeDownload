package com.flux.downloader

import android.net.Uri
import java.util.UUID

data class RepeatOutcome(val job: Download, val created: Boolean)

object RepeatDownloads {
    fun sourceKey(url: String): String? {
        val link=MediaLinks.parse(url) ?: return null
        if(link.platform==MediaPlatform.Instagram)return "instagram:${link.postId}"
        val safe = LibraryDetails.source(url) ?: return null
        val uri = Uri.parse(safe)
        val parts = uri.pathSegments
        val id = when {
            uri.host.equals("youtu.be", true) -> parts.firstOrNull()
            uri.path == "/watch" -> uri.getQueryParameter("v")
            parts.firstOrNull() in setOf("shorts", "embed", "live") -> parts.getOrNull(1)
            else -> null
        }
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,80}")) }
    }
    fun matching(jobs: List<Download>, original: Download): Download? {
        val source = sourceKey(original.url) ?: return null
        return jobs.firstOrNull { !it.terminal && sourceKey(it.url) == source && it.selector == original.selector &&
            it.audioOnly == original.audioOnly && it.replaceEmbeddedAudio == original.replaceEmbeddedAudio }
    }
    fun prepare(original: Download, folder: String, now: Long = System.currentTimeMillis()): Download {
        require(original.state == "Complete") { "Download again is available for completed history entries." }
        require(sourceKey(original.url) != null) { "This source link cannot be reused. Find the video in Discover." }
        require(original.selector.isNotBlank()) { "Choose an available quality in Discover." }
        return original.copy(id = UUID.randomUUID().toString(), state = "Queued", progress = 0f,
            detail = "Waiting to download again", uri = "", mime = "", pendingUri = "", bytes = 0,
            retryCount = 0, nextAttemptAt = 0, folder = folder, created = now, queueOrder = now, favourite = false)
    }
}
