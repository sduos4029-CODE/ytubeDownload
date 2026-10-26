package com.flux.downloader

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

data class TransferSnapshot(val downloaded: Long, val total: Long?, val estimated: Boolean,
    val speed: Double?, val eta: Long?, val format: String, val stream: String,
    val updated: Long = SystemClock.elapsedRealtime()) {
    val percent: Float? get() = total?.takeIf { it > 0 && downloaded <= it }?.let { downloaded.toFloat() / it * 100 }
    fun fresh(now: Long) = now >= updated && now - updated <= 5000
    val byteLabel get() = "${sizeLabel(downloaded)} / ${if (estimated) "~" else ""}${total?.let(::sizeLabel) ?: "unknown size"}"
    val speedLabel get() = speed?.takeIf { it > 0 }?.let { "${sizeLabel(it.toLong())}/s" } ?: "Speed unavailable"
    val etaLabel get() = eta?.let { "~${remainingTime(it)} remaining" } ?: "Time unavailable"
}

fun remainingTime(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

object TransferProgress {
    const val PREFIX = "FLUX_PROGRESS "
    // Explicit null defaults keep unavailable fields valid JSON, instead of bare NA or invented zeros.
    const val TEMPLATE = "download:FLUX_PROGRESS {\"downloaded\":%(progress.downloaded_bytes|null)j,\"total\":%(progress.total_bytes|null)j,\"estimate\":%(progress.total_bytes_estimate|null)j,\"speed\":%(progress.speed|null)j,\"eta\":%(progress.eta|null)j,\"format\":%(info.format_id|null)j,\"video\":%(info.vcodec|null)j,\"audio\":%(info.acodec|null)j}"
    private val mutable = MutableStateFlow<Map<String, TransferSnapshot>>(emptyMap())
    val live = mutable.asStateFlow()
    @Synchronized fun record(id: String, value: TransferSnapshot) { mutable.value = mutable.value + (id to value) }
    @Synchronized fun clear(id: String) { mutable.value = mutable.value - id }

    fun parse(line: String): TransferSnapshot? {
        if (!line.startsWith(PREFIX) || line.length > 8192) return null
        return runCatching {
            val data = JSONObject(line.removePrefix(PREFIX))
            fun number(key: String): Double? = (data.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }
            val downloaded = number("downloaded")?.toLong() ?: return null
            val total = number("total")?.toLong()?.takeIf { it > 0 }
            val estimate = number("estimate")?.toLong()?.takeIf { it > 0 }
            fun codec(key: String) = data.optString(key).lowercase(Locale.ROOT) !in setOf("", "none", "null", "na")
            val stream = when {
                codec("video") && codec("audio") -> "Video + audio stream"
                codec("video") -> "Video stream"
                codec("audio") -> "Audio stream"
                else -> "Media stream"
            }
            TransferSnapshot(downloaded, total ?: estimate, total == null && estimate != null,
                number("speed")?.takeIf { it > 0 }, number("eta")?.toLong(), data.optString("format").take(80), stream)
        }.getOrNull()
    }
}
