package com.flux.downloader

import android.app.Application
import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class FluxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        InstagramSession.init(this)
        InstagramBrowser.recover(this)
        Store.init(this)
    }
}

object Engine {
    private val lock = Mutex()
    private var ready = false
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                installPinnedExtractor(context.applicationContext)
                ready = true
            }
        }
    }

    private fun installPinnedExtractor(context: Context) {
        val target = java.io.File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
        val expected = context.getString(R.string.extractor_sha256)
        fun digest(file: java.io.File): String = file.inputStream().use { input ->
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (target.isFile && digest(target) == expected) return
        target.parentFile!!.mkdirs()
        val temporary = java.io.File(target.parentFile, "yt-dlp.verified")
        try {
            context.resources.openRawResource(R.raw.ytdlp).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(digest(temporary) == expected) { "Extractor integrity check failed. Reinstall Flux from the download page." }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun secureOptions(request: YoutubeDLRequest): YoutubeDLRequest = request.apply {
        addOption("--ignore-config")
        addOption("--no-plugin-dirs")
        // Native HLS/DASH downloader uses the HTTPS policy above. FFmpeg's
        // subprocess is restricted to local inputs if ever chosen as a fallback.
        addOption("--downloader", "dash,m3u8:native")
        addOption("--downloader-args", "ffmpeg_i:-protocol_whitelist file,pipe,crypto,data")
        addOption("--postprocessor-args", "ffmpeg_i:-protocol_whitelist file,pipe,crypto,data")
    }

    /** Plain words search YouTube; supported direct links are validated centrally. */
    fun target(input: String): String = MediaLinks.target(input)

    suspend fun search(context: Context, input: String): List<Video> = withContext(Dispatchers.IO) {
        val target = target(input)
        initialize(context)
        val request = secureOptions(YoutubeDLRequest(target)).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
            addOption("--socket-timeout", "25"); addOption("--retries", "1"); addOption("--extractor-retries", "1")
            if (target.startsWith("ytsearch")) addOption("--flat-playlist")
        }
        val lease=InstagramSession.lease(context,target)
        try {
            lease?.let {request.addOption("--cookies",it.file.absolutePath);it.userAgent?.let {agent -> request.addOption("--user-agent",agent)}}
            val result = YoutubeDL.getInstance().execute(request)
            val json = JSONObject(result.out)
            if (MediaLinks.isInstagram(target)) return@withContext InstagramPost.parse(json,target)
            val entries = json.optJSONArray("entries")
            if (entries == null) listOf(Video.from(json, target)) else buildList {
                for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { add(Video.from(it)) }
            }
        } finally {lease?.close()}
    }

    suspend fun inspect(context: Context, video: Video): Video = withContext(Dispatchers.IO) {
        initialize(context)
        val request = secureOptions(YoutubeDLRequest(video.url)).apply {
            addOption("--dump-single-json"); addOption("--skip-download")
            addOption("--no-playlist"); addOption("--socket-timeout", "25"); addOption("--retries", "1"); addOption("--extractor-retries", "1")
        }
        val lease=InstagramSession.lease(context,video.url)
        try {
            lease?.let {request.addOption("--cookies",it.file.absolutePath);it.userAgent?.let {agent -> request.addOption("--user-agent",agent)}}
            val json=JSONObject(YoutubeDL.getInstance().execute(request).out)
            if (MediaLinks.isInstagram(video.url)) InstagramPost.parse(json,video.url).firstOrNull { it.id==video.id }
                ?: error("This Instagram item is no longer available. Load the post again.")
            else Video.from(json,video.url)
        } finally {lease?.close()}
    }
}

data class MediaFormat(
    val id: String, val extension: String, val height: Int, val fps: Int,
    val audioBitrate: Int, val size: Long, val video: Boolean, val audio: Boolean,
    val codec: String, val audioCodec: String = "", val sampleRate: Int = 0,
    val channels: Int = 0, val language: String = "", val note: String = "", val width: Int = 0
) {
    val label: String get() = if (photo) if(width>0 && height>0) "${width}x$height" else "Source photo" else if (video) (if(height>0) "${if(width>0 && height>width) "${width}x$height" else "${height}p"}${if (fps > 30) " · ${fps}fps" else ""}" else "Source video") else
        if (audioBitrate > 0) "$audioBitrate kbps" else "Source audio"
    val photo get() = extension in setOf("jpg","jpeg","png","webp")
    val isDrc get() = id.contains("drc", true) || note.contains("DRC", true)
    val audioLabel get() = buildList {
        add(audioCodecLabel(audioCodec))
        if (audioBitrate > 0) add("$audioBitrate kbps")
        if (sampleRate > 0) add("${sampleRate / 1000.0} kHz")
        if (channels > 0) add(when (channels) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$channels channels" })
        if (language.isNotBlank()) add(language)
        if (isDrc) add("DRC")
    }.joinToString(" · ")
    companion object {
        private fun JSONObject.hasCodec(key: String) = optString(key, "none") !in listOf("none", "null", "")
        fun from(o: JSONObject, instagramVideo: Boolean = false): MediaFormat = MediaFormat(
            o.optString("format_id"), o.optString("ext", "mp4"), o.optInt("height"),
            o.optDouble("fps", 0.0).toInt(),
            (o.optDouble("abr", 0.0).takeIf { it > 0 } ?: if (!o.hasCodec("vcodec")) o.optDouble("tbr", 0.0) else 0.0).toInt(),
            o.optLong("filesize", 0).takeIf { it > 0 } ?: o.optLong("filesize_approx", 0),
            o.hasCodec("vcodec") || (instagramVideo && o.optString("vcodec") in setOf("","null") && o.optString("ext")=="mp4"), o.hasCodec("acodec"), o.optString("vcodec", ""),
            o.optString("acodec", ""), o.optInt("asr", 0), o.optInt("audio_channels", 0),
            o.optString("language", "").takeUnless { it == "null" }.orEmpty(), o.optString("format_note", ""), o.optInt("width",0)
        )
    }
}

data class Video(val id: String, val title: String, val author: String, val thumbnail: String,
    val seconds: Long, val formats: List<MediaFormat> = emptyList(), val sourceUrl: String = "",
    val platform: MediaPlatform = MediaPlatform.YouTube, val mediaKind: String = "video",
    val itemIndex: Int = 1, val itemCount: Int = 1) {
    val isPhoto get() = mediaKind == "photo"
    val url get() = sourceUrl.ifBlank { "https://www.youtube.com/watch?v=$id" }
    val key get() = "${platform.name}:$id"
    companion object {
        fun from(o: JSONObject, requestedUrl: String = ""): Video {
            val id = o.getString("id")
            val formats = o.optJSONArray("formats") ?: JSONArray()
            val link=listOf(requestedUrl,o.optString("webpage_url"),o.optString("original_url"),o.optString("url")).firstNotNullOfOrNull { MediaLinks.parse(it) }
            val platform=link?.platform ?: MediaPlatform.YouTube
            require(!o.optString("extractor_key").startsWith("Instagram",true) || platform==MediaPlatform.Instagram) { "The Instagram source link is missing. Load the post again." }
            fun text(key: String) = o.optString(key).takeUnless { it.isBlank() || it=="null" }
            return Video(id, text("title") ?: "${platform.label} video",
                text("uploader") ?: text("channel") ?: text("uploader_id") ?: platform.label,
                o.optString("thumbnail").takeIf { it.startsWith("https://") }
                    ?: if(platform==MediaPlatform.YouTube) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else "", o.optDouble("duration", 0.0).toLong(),
                (0 until formats.length()).mapNotNull { formats.optJSONObject(it)?.let { format -> MediaFormat.from(format,platform==MediaPlatform.Instagram) } }
                    .filter { it.id.isNotBlank() && (it.video || it.audio) && it.extension !in listOf("mhtml", "none") },link?.url.orEmpty(),platform,o.optString("flux_media_kind","video"))
        }
    }
}

data class Download(
    val id: String = UUID.randomUUID().toString(), val url: String, val title: String,
    val thumbnail: String, val selector: String, val label: String, val audioOnly: Boolean,
    val duration: Long, val folder: String, val state: String = "Queued", val progress: Float = 0f,
    val detail: String = "Waiting to download", val uri: String = "", val mime: String = "",
    val created: Long = System.currentTimeMillis(), val replaceEmbeddedAudio: Boolean = false,
    val audioDescription: String = "", val bytes: Long = 0,
    val queueOrder: Long = created, val retryCount: Int = 0, val nextAttemptAt: Long = 0,
    val pendingUri: String = "", val favourite: Boolean = false,
    val mediaKind: String = "video", val mediaId: String = ""
) {
    val isPhoto get() = mediaKind == "photo" || mime.startsWith("image/")
    val terminal get() = state in listOf("Complete", "Failed", "Cancelled")
    fun json() = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumbnail", thumbnail)
        put("selector", selector); put("label", label); put("audioOnly", audioOnly)
        put("duration", duration); put("folder", folder); put("state", state)
        put("progress", progress.toDouble()); put("detail", detail); put("uri", uri)
        put("mime", mime); put("created", created)
        put("replaceEmbeddedAudio", replaceEmbeddedAudio); put("audioDescription", audioDescription); put("bytes", bytes)
        put("queueOrder", queueOrder); put("retryCount", retryCount); put("nextAttemptAt", nextAttemptAt); put("pendingUri", pendingUri)
        put("favourite", favourite); put("mediaKind",mediaKind); put("mediaId",mediaId)
    }
    companion object {
        fun from(o: JSONObject) = Download(o.getString("id"), o.getString("url"), o.getString("title"),
            o.optString("thumbnail"), o.getString("selector"), o.getString("label"), o.getBoolean("audioOnly"),
            o.optLong("duration"), o.optString("folder"), o.getString("state"), o.optDouble("progress").toFloat(),
            o.optString("detail"), o.optString("uri"), o.optString("mime"), o.optLong("created"),
            o.optBoolean("replaceEmbeddedAudio", false), o.optString("audioDescription", ""), o.optLong("bytes", 0),
            o.optLong("queueOrder", o.optLong("created")), o.optInt("retryCount", 0), o.optLong("nextAttemptAt", 0), o.optString("pendingUri", ""), o.optBoolean("favourite", false), o.optString("mediaKind","video"), o.optString("mediaId",""))
    }
}

/** Small durable job ledger; all edits are serialized and progress is throttled by the worker. */
object Store {
    private lateinit var appContext: Context
    private lateinit var prefs: android.content.SharedPreferences
    private val mutable = MutableStateFlow<List<Download>>(emptyList())
    val downloads = mutable.asStateFlow()
    val folder = MutableStateFlow("")
    val library = MutableStateFlow(LibraryPreferences())
    val queuePaused = MutableStateFlow(false)
    val transfers = MutableStateFlow(TransferPreferences())
    private val workingFolders = mutableMapOf<String, String>()
    @Synchronized fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("flux", Context.MODE_PRIVATE)
        folder.value = prefs.getString("folder", "").orEmpty()
        queuePaused.value = prefs.getBoolean("queue_paused", false)
        transfers.value = TransferPreferences(prefs.getInt("parallel_downloads", 1), prefs.getBoolean("wifi_only", false)).normalized()
        NetworkGate.start(context)
        library.value = LibraryPreferences(
            runCatching { LibraryLayout.valueOf(prefs.getString("library_layout", "List")!!) }.getOrDefault(LibraryLayout.List),
            runCatching { LibrarySize.valueOf(prefs.getString("library_size", "Medium")!!) }.getOrDefault(LibrarySize.Medium),
            runCatching { LibrarySort.valueOf(prefs.getString("library_sort", "Newest")!!) }.getOrDefault(LibrarySort.Newest))
        val array = runCatching { JSONArray(prefs.getString("downloads", "[]")) }.getOrDefault(JSONArray())
        mutable.value = (0 until array.length()).mapNotNull { runCatching { Download.from(array.getJSONObject(it)) }.getOrNull() }
        releaseUnusedFolders()
    }
    @Synchronized fun add(job: Download) { mutable.value = listOf(job) + mutable.value.filterNot { it.id == job.id }; save(true) }
    @Synchronized fun change(id: String, durable: Boolean = false, transform: (Download) -> Download) {
        mutable.value = mutable.value.map { if (it.id == id) transform(it) else it }; save(durable); releaseUnusedFolders()
    }
    @Synchronized fun clearHistory() { mutable.value = mutable.value.filter { !it.terminal || it.pendingUri.isNotBlank() }; save(true); releaseUnusedFolders() }
    @Synchronized fun toggleFavourite(id: String) {
        mutable.value = mutable.value.map { if (it.id == id && it.state == "Complete" && it.uri.isNotBlank()) it.copy(favourite = !it.favourite) else it }
        save(true)
    }
    @Synchronized fun setFavourites(ids: Set<String>, value: Boolean): Int {
        require(ids.size <= LibraryActions.MAX_SELECTION) { "Select up to 100 items at a time." }
        var changed = 0
        mutable.value = mutable.value.map {
            if (it.id in ids && it.state == "Complete" && it.uri.isNotBlank() && it.favourite != value) {
                changed++; it.copy(favourite = value)
            } else it
        }
        if (changed > 0) save(true)
        return changed
    }
    @Synchronized fun repeatCompleted(id: String): RepeatOutcome {
        val original = mutable.value.find { it.id == id } ?: error("This history entry is no longer available.")
        val fresh = RepeatDownloads.prepare(original, folder.value)
        RepeatDownloads.matching(mutable.value, original)?.let { return RepeatOutcome(it, false) }
        val next = fresh.copy(state = if (queuePaused.value) "Paused" else "Queued", queueOrder = nextQueueOrder(),
            detail = if (queuePaused.value) "Queue paused · ready when you resume" else "Waiting to download again")
        mutable.value = listOf(next) + mutable.value
        save(true)
        return RepeatOutcome(next, true)
    }
    @Synchronized fun removeHistory(ids: Set<String>): List<Download> {
        val removed = mutable.value.filter { it.id in ids && it.terminal && it.pendingUri.isBlank() && !isWorking(it.id) }
        val removedIds = removed.map { it.id }.toSet()
        mutable.value = mutable.value.filterNot { it.id in removedIds }
        save(true)
        releaseUnusedFolders()
        return removed
    }
    @Synchronized fun setFolder(uri: String) { folder.value = uri; prefs.edit().putString("folder", uri).apply() }
    @Synchronized fun chooseFolder(context: Context, uri: Uri?) {
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        setFolder(uri?.toString().orEmpty())
        releaseUnusedFolders()
    }
    @Synchronized fun setTransfers(value: TransferPreferences) {
        val settings = value.normalized()
        transfers.value = settings
        prefs.edit().putInt("parallel_downloads", settings.parallel).putBoolean("wifi_only", settings.wifiOnly).commit()
        Downloads.recover(appContext)
    }
    @Synchronized fun nextQueueOrder(): Long = maxOf(System.currentTimeMillis(), (mutable.value.maxOfOrNull { it.queueOrder } ?: 0L) + 1)
    @Synchronized fun firstQueueOrder(): Long = mutable.value.filter { it.state in QueueRules.runnableStates }.minOfOrNull { it.queueOrder } ?: System.currentTimeMillis()
    @Synchronized fun setQueuePaused(value: Boolean) { queuePaused.value = value; prefs.edit().putBoolean("queue_paused", value).commit() }
    @Synchronized fun isWorking(id: String): Boolean = workingFolders.containsKey(id)
    @Synchronized fun finishDownload(id: String, uri: String, mime: String, bytes: Long, audio: String): Boolean {
        val job = mutable.value.find { it.id == id } ?: return false
        if (job.state in setOf("Pausing", "Paused", "Cancelled")) return false
        mutable.value = mutable.value.map { if (it.id == id) it.copy(state = "Complete", progress = 100f,
            detail = "Saved · ${sizeLabel(bytes)}", uri = uri, mime = mime, bytes = bytes,
            audioDescription = audio.ifBlank { it.audioDescription }, pendingUri = "", nextAttemptAt = 0) else it }
        save(true)
        return true
    }
    @Synchronized fun workStarted(job: Download) { workingFolders[job.id] = job.folder }
    @Synchronized fun workFinished(id: String) {
        workingFolders.remove(id)
        val pausing = mutable.value.any { it.id == id && it.state == "Pausing" }
        if (pausing) {
            mutable.value = mutable.value.map { if (it.id == id) it.copy(state = "Paused", nextAttemptAt = 0, detail = "Paused · downloaded data kept") else it }
            save(true)
        }
        releaseUnusedFolders()
    }
    @Synchronized fun releaseUnusedFolders() {
        val required = mutable.value.filter { !it.terminal || it.pendingUri.isNotBlank() || (it.state == "Complete" && it.uri.isNotBlank()) }.map { it.folder }.toSet() + folder.value + workingFolders.values
        appContext.contentResolver.persistedUriPermissions.filter { it.uri.toString() !in required }.forEach { permission ->
            val flags = (if (permission.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { appContext.contentResolver.releasePersistableUriPermission(permission.uri, flags) }
        }
    }
    @Synchronized fun setLibrary(value: LibraryPreferences) {
        library.value = value
        prefs.edit().putString("library_layout", value.layout.name).putString("library_size", value.size.name)
            .putString("library_sort", value.sort.name).apply()
    }
    private fun save(durable: Boolean = false) {
        val editor = prefs.edit().putString("downloads", JSONArray().apply { mutable.value.forEach { put(it.json()) } }.toString())
        if (durable) editor.commit() else editor.apply()
    }
}
