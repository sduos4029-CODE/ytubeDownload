package com.flux.downloader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidFormat
import android.net.Uri
import android.os.Bundle
import androidx.work.*
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors

/** Real-device integration checks. Media is generated locally; no third-party video is downloaded. */
class RuntimeChecks : Instrumentation() {
    private var favouritesOnly = false
    private var favouritesRestartOnly = false
    private var favouritesUiOnly = false
    private var progressOnly = false
    private var progressUiOnly = false
    private var libraryOnly = false
    private var libraryRestartOnly = false
    private var libraryUiOnly = false
    private var transferUiOnly = false
    private var transferOnly = false
    private var transferPrefsOnly = false
    private var folderOnly = false
    private var uiOnly = false
    private var prefsOnly = false
    private var queueOnly = false
    private var queueUiOnly = false
    private var restartOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        favouritesOnly = arguments?.getString("favouritesOnly") == "true"
        favouritesRestartOnly = arguments?.getString("favouritesRestartOnly") == "true"
        favouritesUiOnly = arguments?.getString("favouritesUiOnly") == "true"
        progressOnly = arguments?.getString("progressOnly") == "true"
        progressUiOnly = arguments?.getString("progressUiOnly") == "true"
        libraryOnly = arguments?.getString("libraryOnly") == "true"
        libraryRestartOnly = arguments?.getString("libraryRestartOnly") == "true"
        libraryUiOnly = arguments?.getString("libraryUiOnly") == "true"
        folderOnly = arguments?.getString("folderOnly") == "true"
        uiOnly = arguments?.getString("uiOnly") == "true"
        prefsOnly = arguments?.getString("prefsOnly") == "true"
        queueOnly = arguments?.getString("queueOnly") == "true"
        queueUiOnly = arguments?.getString("queueUiOnly") == "true"
        restartOnly = arguments?.getString("restartOnly") == "true"
        transferUiOnly = arguments?.getString("transferUiOnly") == "true"
        transferOnly = arguments?.getString("transferOnly") == "true"
        transferPrefsOnly = arguments?.getString("transferPrefsOnly") == "true"
        start()
    }
    private fun report(message: String) { sendStatus(0, Bundle().apply { putString("stream", "$message\n") }) }
    override fun onStart() {
        val result = Bundle()
        try {
            if (favouritesOnly || favouritesRestartOnly) {
                runBlocking { FavouritesChecks(this@RuntimeChecks,::report).run(favouritesRestartOnly) }
                result.putString("stream",if(favouritesRestartOnly) "FAVOURITES RESTART CHECKS PASSED\n" else "FAVOURITES CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if (progressOnly) {
                runBlocking { ProgressChecks(this@RuntimeChecks,::report).run() }
                result.putString("stream","PROGRESS CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
            }
            if (libraryOnly || libraryRestartOnly) {
                val checks = LibraryChecks(this, ::report)
                if (libraryRestartOnly) checks.restart() else runBlocking { checks.run() }
                result.putString("stream", if (libraryRestartOnly) "LIBRARY RESTART CHECKS PASSED\n" else "LIBRARY ACTION CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result); return
            }
            if (transferOnly) {
                runBlocking { TransferChecks(this@RuntimeChecks, ::report).run() }
                result.putString("stream", "TRANSFER CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
            }
            if (transferPrefsOnly) {
                Store.init(targetContext)
                check(Store.transfers.value == TransferPreferences(2,true))
                report("PASS: transfer preferences survive app process restart")
                result.putString("stream", "TRANSFER PREFERENCE CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
            }
            if (queueOnly || restartOnly) {
                runBlocking { QueueChecks(this@RuntimeChecks, ::report).run(restartOnly) }
                result.putString("stream", if (restartOnly) "RESTART CHECKS PASSED\n" else "QUEUE CHECKS PASSED\n")
                finish(Activity.RESULT_OK, result); return
            }
            if (prefsOnly) {
                Store.init(targetContext)
                check(Store.library.value.layout == LibraryLayout.Grid && Store.library.value.size == LibrarySize.Medium)
                report("PASS: Library Grid/Medium preference persists across process restart")
                result.putString("stream", "PREFERENCE CHECKS PASSED\n"); finish(Activity.RESULT_OK, result); return
            }
            if (folderOnly) {
                Store.init(targetContext)
                val folder = Uri.parse(Store.folder.value.takeIf { it.isNotBlank() } ?: error("No folder selected"))
                check(targetContext.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isReadPermission && it.isWritePermission })
                val tree = DocumentFile.fromTreeUri(targetContext, folder) ?: error("Folder unavailable")
                check(tree.canWrite())
                val probe = tree.createFile("text/plain", "flux-runtime-check-${System.currentTimeMillis()}.txt") ?: error("Cannot create file")
                try {
                    val bytes = "Flux folder persistence test".toByteArray()
                    targetContext.contentResolver.openOutputStream(probe.uri, "w")!!.use { it.write(bytes) }
                    check(targetContext.contentResolver.openInputStream(probe.uri)!!.use { it.readBytes() }.contentEquals(bytes))
                } finally { check(probe.delete()) }
                report("PASS: selected folder grant persists across process restart; create/write/read/delete succeeds")
                result.putString("stream", "FOLDER CHECKS PASSED\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            check(!activity.isFinishing) { "Activity did not launch" }
            report("PASS: APK installs and MainActivity launches")
            if (uiOnly || queueUiOnly || transferUiOnly || libraryUiOnly || progressUiOnly || favouritesUiOnly) {
                fun click(label: String) {
                    var forward = true
                    val deadline = System.currentTimeMillis() + 30000
                    while (System.currentTimeMillis() < deadline) {
                        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                            if (node == null) return null
                            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
                            for (i in 0 until node.childCount) { val match = find(node.getChild(i)); if (match != null) return match }
                            return null
                        }
                        var node = find(uiAutomation.rootInActiveWindow)
                        while (node != null) {
                            if (node.isClickable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(700); return }
                            node = node.parent
                        }
                        fun scrollable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                            if (node == null) return null
                            if (node.isScrollable) return node
                            for (i in 0 until node.childCount) { val value = scrollable(node.getChild(i)); if (value != null) return value }
                            return null
                        }
                        val area = scrollable(uiAutomation.rootInActiveWindow)
                        if (area != null && !area.performAction(if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) forward = !forward
                        Thread.sleep(300)
                    }
                    error("UI control not found: $label")
                }
                if (favouritesUiOnly) {
                    Store.setQueuePaused(true)
                    val item=LibraryChecks(this,::report).fixture("favourite-ui").copy(title="Favourite UI fixture",audioDescription="AAC · 44.1 kHz · Stereo")
                    Store.add(item)
                    click("Library");click("Compact")
                    click("More actions for ${item.title}");click("Add to favourites")
                    check(Store.downloads.value.first { it.id==item.id }.favourite)
                    click("Favourites")
                    click("More actions for ${item.title}");click("File details")
                    click("Check file again")
                    click("Copy source link")
                    val copied=targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                    check(copied==item.url)
                    click("Close details")
                    click("More actions for ${item.title}");click("Remove from favourites")
                    check(!Store.downloads.value.first { it.id==item.id }.favourite)
                    click("Favourites")
                    report("PASS: release Library menu marks/unmarks favourites; filter, details refresh and source-copy controls work through scrolling UI")
                    result.putString("stream","FAVOURITES UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if (progressUiOnly) {
                    Store.setQueuePaused(true)
                    val fixture=Download(id="progress-ui",url="https://www.youtube.com/watch?v=fixture",title="Progress UI fixture",thumbnail="",selector="video+audio",label="2160p",audioOnly=false,duration=6,folder="",state="Downloading")
                    Store.add(fixture)
                    val sample=TransferSnapshot(1048576,4194304,false,null,null,"video","Video stream")
                    TransferProgress.record(fixture.id,sample)
                    click("Downloads")
                    fun visible(label:String):Boolean {
                        fun find(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                            if(node==null)return false
                            if(node.isVisibleToUser && node.text?.toString()?.contains(label)==true)return true
                            return (0 until node.childCount).any { find(node.getChild(it)) }
                        }
                        return find(uiAutomation.rootInActiveWindow)
                    }
                    fun requireVisible(label:String) {
                        var forward=true
                        repeat(20) {
                            if (visible(label)) return
                            fun scrollable(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                                if(node==null)return null
                                if(node.isScrollable)return node
                                for(i in 0 until node.childCount) { val found=scrollable(node.getChild(i));if(found!=null)return found }
                                return null
                            }
                            val area=scrollable(uiAutomation.rootInActiveWindow)
                            val action=if(forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            if(area?.performAction(action)!=true)forward=!forward
                            Thread.sleep(300)
                        }
                        error("Progress UI label not visible after scrolling: $label")
                    }
                    listOf("Video stream","1 · Download","2 · Merge","3 · Save","Speed unavailable","Time unavailable").forEach(::requireVisible)
                    Store.change(fixture.id,true) { it.copy(state="Merging",detail="Combining video + selected audio · no quality loss") }
                    Thread.sleep(1000); requireVisible("Combining video + selected audio")
                    Store.change(fixture.id,true) { it.copy(state="Saving",progress=42f,detail="Saving to your download folder") }
                    Thread.sleep(1000); requireVisible("42%")
                    TransferProgress.clear(fixture.id)
                    Store.change(fixture.id,true) { it.copy(state="Cancelled") }
                    report("PASS: release progress UI shows stream/stage labels, honest unknown estimates, indeterminate merge and saving percentage")
                    result.putString("stream","PROGRESS UI CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
                }
                if (libraryUiOnly) {
                    Store.setQueuePaused(true)
                    val helper=LibraryChecks(this,::report)
                    val one=helper.fixture("library-ui-one"); val two=helper.fixture("library-ui-two")
                    Store.add(one); Store.add(two)
                    click("Library"); click("Compact"); click("Select downloads")
                    click("Select ${one.title}"); click("Select ${two.title}")
                    click("Remove history"); click("Keep entries")
                    check(Store.downloads.value.count { it.id in setOf(one.id,two.id) }==2)
                    click("Remove history"); click("Remove history")
                    check(Store.downloads.value.none { it.id in setOf(one.id,two.id) })
                    listOf(one,two).forEach { saved -> check(targetContext.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { it.readBytes().isNotEmpty() }) }
                    click("Select downloads"); click("Select results"); click("Done")
                    report("PASS: release Library multi-select, dismiss/confirm removal and selection exit preserve saved files")
                    result.putString("stream","LIBRARY UI CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
                }
                if (transferUiOnly) {
                    Store.setTransfers(TransferPreferences())
                    click("Settings"); click("3")
                    check(Store.transfers.value.parallel == 3)
                    click("1"); check(Store.transfers.value.parallel == 1)
                    click("Use Wi-Fi only"); check(Store.transfers.value.wifiOnly)
                    click("Use Wi-Fi only"); check(!Store.transfers.value.wifiOnly)
                    report("PASS: release Settings controls change download limits and Wi-Fi-only preference")
                    result.putString("stream", "TRANSFER UI CHECKS PASSED\n"); finish(Activity.RESULT_OK,result); return
                }
                if (queueUiOnly) {
                    Store.setQueuePaused(true)
                    Store.downloads.value.filter { it.title.startsWith("Flux queue UI fixture") && !it.terminal }.forEach { old ->
                        Store.change(old.id, durable = true) { it.copy(state = "Cancelled") }
                    }
                    val completed = Store.downloads.value.count { it.state == "Complete" }
                    val fixtures = (1..3).map { n -> Download(url="http://127.0.0.1:9/manifest.mpd", title="Flux queue UI fixture $n",
                        thumbnail="", selector="bestaudio", label="Source audio", audioOnly=true, duration=6, folder="", queueOrder=n.toLong()) }
                    fixtures.forEach(Store::add)
                    click("Downloads")
                    click("Download next")
                    check(Store.downloads.value.filter { it.id in fixtures.map { f -> f.id } }.any { it.queueOrder < 1 })
                    click("Pause")
                    check(Store.downloads.value.filter { it.id in fixtures.map { f -> f.id } }.any { it.state == "Paused" })
                    click("Cancel all")
                    click("Keep downloads")
                    check(Store.downloads.value.count { it.id in fixtures.map { f -> f.id } && !it.terminal } == 3)
                    click("Cancel all"); click("Cancel downloads")
                    check(Store.downloads.value.filter { it.id in fixtures.map { f -> f.id } }.all { it.state == "Cancelled" })
                    check(Store.downloads.value.count { it.state == "Complete" } == completed)
                    Store.setQueuePaused(true)
                    fixtures.forEach { Store.add(it.copy(state="Paused", detail="Generated UI fixture · downloaded data kept")) }
                    report("PASS: exact release queue UI reorders Download next, pauses an item, confirms/cancels cancel-all, and preserves completed history")
                    result.putString("stream", "QUEUE UI CHECKS PASSED\n"); finish(Activity.RESULT_OK, result); return
                }
                click("Library")
                click("Grid"); check(Store.library.value.layout == LibraryLayout.Grid)
                click("Small"); check(Store.library.value.size == LibrarySize.Small)
                click("Large"); check(Store.library.value.size == LibrarySize.Large)
                click("Medium"); check(Store.library.value.size == LibrarySize.Medium)
                click("Compact"); check(Store.library.value.layout == LibraryLayout.Compact)
                click("List"); check(Store.library.value.layout == LibraryLayout.List)
                click("Grid"); check(Store.library.value.layout == LibraryLayout.Grid)
                report("PASS: Library UI switches List/Grid/Compact and Small/Medium/Large using actual on-screen controls")
                result.putString("stream", "LIBRARY UI CHECKS PASSED\n"); finish(Activity.RESULT_OK, result); return
            }
            runBlocking {
                val base = Download(url="test",title="Zebra",thumbnail="",selector="18",label="360p",audioOnly=false,duration=1,folder="",state="Complete",bytes=12,created=2)
                val second = base.copy(id="second",title="Alpha",audioOnly=true,bytes=50,created=1)
                check(libraryJobs(listOf(base,second), "", "All", LibrarySort.Largest).first().id == "second")
                check(libraryJobs(listOf(base,second), "", "All", LibrarySort.Newest).first().id == base.id)
                check(libraryJobs(listOf(base,second), "alp", "Audio", LibrarySort.Title).single().id == "second")
                check(libraryJobs(listOf(base,second), "alp", "Video", LibrarySort.Title).isEmpty())
                check(Download.from(second.json()).bytes == 50L)
                report("PASS: Library search, type filters, size/date sorting and saved file-size migration")
                WorkManager.getInstance(targetContext).cancelAllWork().result.get()
                Store.downloads.value.filterNot { it.terminal }.forEach { old -> Store.change(old.id) { it.copy(state = "Cancelled") } }
                Store.clearHistory()
                Engine.initialize(targetContext)
                val version = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(YoutubeDLRequest("https://www.youtube.com/").apply { addOption("--version") }).out.trim()
                }
                check(version.isNotBlank())
                report("PASS: bundled native Python + yt-dlp initialize; version $version")
                var model: FluxModel? = null
                runOnMainSync {
                    model = ViewModelProvider(activity)[FluxModel::class.java]
                    model!!.query = "https://example.org/not-youtube"
                    model!!.search()
                }
                withTimeout(30_000) { while (model!!.busy) delay(100) }
                check(model!!.error?.contains("YouTube link") == true) { "Invalid URL did not show validation error: ${model!!.error}" }
                report("PASS: unsupported URL is rejected with a readable error")
                val source = Video.from(org.json.JSONObject("""{"id":"fixture","formats":[
                    {"format_id":"2160","ext":"mp4","vcodec":"avc1","acodec":"none","height":2160},
                    {"format_id":"muxed","ext":"mp4","vcodec":"avc1","acodec":"mp4a.40.2","height":720},
                    {"format_id":"audio","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":128,"asr":44100,"audio_channels":2},
                    {"format_id":"audio-drc","ext":"webm","vcodec":"none","acodec":"opus","abr":160,"format_note":"DRC"}]}"""))
                check(AudioSelection.plan(source, source.formats[0]).selector == "2160+(bestaudio[format_id!*=drc]/bestaudio)")
                val explicit = AudioSelection.plan(source, source.formats[1], source.formats[2])
                check(explicit.selector == "muxed+audio" && explicit.replaceEmbeddedAudio)
                check(source.formats[2].audioLabel.contains("AAC · 128 kbps · 44.1 kHz · Stereo"))
                check(source.formats[3].isDrc)
                val legacy = Download(url="test", title="test", thumbnail="", selector="18", label="360p", audioOnly=false, duration=1, folder="").json()
                legacy.remove("replaceEmbeddedAudio"); legacy.remove("audioDescription")
                check(!Download.from(legacy).replaceEmbeddedAudio)
                report("PASS: audio metadata parsing, explicit selection, grouped best-audio fallback and legacy history compatibility")
                FixtureServer().use { server ->
                    val video = Download(url = server.url, title = "Flux generated merge test", thumbnail = "", selector = "bestvideo+bestaudio",
                        label = "180p", audioOnly = false, duration = 6, folder = "")
                    val states = Collections.synchronizedSet(mutableSetOf<String>())
                    val watcher = launch(Dispatchers.Default) { Store.downloads.collect { jobs -> jobs.find { it.id == video.id }?.let { states.add(it.state) } } }
                    enqueueLocal(video)
                    val completed = waitFor(video.id)
                    watcher.cancelAndJoin()
                    check(completed.state == "Complete") { "Merge job failed: ${completed.detail}" }
                    check("Downloading" in states) { "Download progress not emitted: $states" }
                    check("Merging" in states) { "Merge progress not emitted: $states" }
                    verifyTracks(completed, true)
                    report("PASS: separate video/audio download + FFmpeg merge + MediaStore save; states=$states")
                    val combined = video.copy(id = java.util.UUID.randomUUID().toString(),
                        title = "Flux 4K audio replacement test", url = server.url.replace("manifest.mpd", "combined.mpd"),
                        selector = "combined+audio", replaceEmbeddedAudio = true, label = "2160p")
                    enqueueLocal(combined)
                    val upgraded = waitFor(combined.id)
                    check(upgraded.state == "Complete") { "4K replacement failed: ${upgraded.detail}" }
                    verifyTracks(upgraded, true)
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(targetContext, Uri.parse(upgraded.uri), null)
                        val v = (0 until extractor.trackCount).map(extractor::getTrackFormat).first { it.getString(AndroidFormat.KEY_MIME)!!.startsWith("video/") }
                        check(v.getInteger(AndroidFormat.KEY_WIDTH) == 3840 && v.getInteger(AndroidFormat.KEY_HEIGHT) == 2160)
                    } finally { extractor.release() }
                    check(audioPacketHash(upgraded.uri) == audioPacketHash(completed.uri)) { "Replacement audio differs from the selected source audio" }
                    check(upgraded.audioDescription.contains("AAC") && upgraded.audioDescription.contains("44.1 kHz")) { upgraded.audioDescription }
                    report("PASS: real 3840x2160 combined video replaces embedded 32kbps tone with selected source audio; exactly one audio track and identical audio sample hash; ${upgraded.audioDescription}")
                    val audio = video.copy(id = java.util.UUID.randomUUID().toString(), title = "Flux generated audio test", selector = "bestaudio", label = "128 kbps", audioOnly = true)
                    enqueueLocal(audio)
                    val audioResult = waitFor(audio.id)
                    check(audioResult.state == "Complete") { "Audio job failed: ${audioResult.detail}" }
                    verifyTracks(audioResult, false)
                    report("PASS: audio-only download, saved file is readable and contains one audio track")
                    val cancel = video.copy(id = java.util.UUID.randomUUID().toString(), title = "Flux cancellation test", url = server.slowUrl)
                    enqueueLocal(cancel)
                    withTimeout(60_000) { while (Store.downloads.value.find { it.id == cancel.id }?.state != "Downloading") delay(100) }
                    delay(1000)
                    Downloads.cancel(targetContext, cancel)
                    delay(2000)
                    check(Store.downloads.value.find { it.id == cancel.id }?.state == "Cancelled")
                    check(Store.downloads.value.find { it.id == cancel.id }?.uri.isNullOrBlank())
                    report("PASS: active download cancellation retains cancelled history without a saved file")
                }
                val persisted = targetContext.getSharedPreferences("flux", 0).getString("downloads", "").orEmpty()
                check(persisted.contains("Flux generated merge test") && persisted.contains("Complete"))
                report("PASS: completed download history persisted")
                runOnMainSync { model!!.query = ""; model!!.error = null }
            }
            result.putString("stream", "ALL RUNTIME CHECKS PASSED\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            report("FAIL: ${error.stackTraceToString()}")
            result.putString("stream", "RUNTIME CHECKS FAILED: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    // The proxy-connected emulator has no validated internet. Local fixtures need
    // no internet constraint; production still uses Downloads.enqueue unchanged.
    private fun enqueueLocal(job: Download) {
        Store.add(job)
        WorkManager.getInstance(targetContext).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("job" to job.id)).addTag(job.id).build())
    }
    private suspend fun waitFor(id: String): Download = withTimeout(240_000) {
        while (true) {
            val value = Store.downloads.value.find { it.id == id }
            if (value?.terminal == true) return@withTimeout value
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun audioPacketHash(uri: String): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(targetContext, Uri.parse(uri), null)
            val audio = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(AndroidFormat.KEY_MIME)!!.startsWith("audio/") }
            extractor.selectTrack(audio)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes); digest.update(bytes)
                extractor.advance()
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { extractor.release() }
    }
    private fun verifyTracks(job: Download, video: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(targetContext, Uri.parse(job.uri), null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            check(formats.count { it.getString(AndroidFormat.KEY_MIME).orEmpty().startsWith("audio/") } == 1)
            check(formats.count { it.getString(AndroidFormat.KEY_MIME).orEmpty().startsWith("video/") } == if (video) 1 else 0)
            check(formats.all { it.getLong(AndroidFormat.KEY_DURATION) > 5_000_000 }) { "Truncated output: $formats" }
            check(targetContext.contentResolver.openInputStream(Uri.parse(job.uri))!!.use { it.read() } >= 0)
        } finally { extractor.release() }
    }
    private inner class FixtureServer : AutoCloseable {
        private val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var open = true
        val url = "http://127.0.0.1:${server.localPort}/manifest.mpd"
        val slowUrl = "http://127.0.0.1:${server.localPort}/slow/manifest.mpd"
        init {
            executor.execute {
                while (open) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    executor.execute {
                        runCatching { socket.use { client ->
                            client.soTimeout = 10_000
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine().split(' ')
                            val path = request[1].substringBefore('?')
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val name = path.substringAfterLast('/')
                            require(name in setOf("manifest.mpd", "combined.mpd", "combined.mp4", "video.mp4", "audio.m4a"))
                            val data = context.assets.open(name).use { it.readBytes() }
                            val type = if (name.endsWith("mpd")) "application/dash+xml" else "application/octet-stream"
                            val output = client.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nContent-Type: $type\r\nConnection: close\r\n\r\n".toByteArray())
                            if (request[0] != "HEAD") {
                                if (path.startsWith("/slow/") && !name.endsWith("mpd")) {
                                    var offset = 0
                                    while (offset < data.size) {
                                        val count = minOf(2048, data.size - offset)
                                        output.write(data, offset, count); output.flush(); offset += count
                                        Thread.sleep(150)
                                    }
                                } else output.write(data)
                            }
                            output.flush()
                        } }
                    }
                }
            }
        }
        override fun close() { open = false; server.close(); executor.shutdownNow() }
    }
}
