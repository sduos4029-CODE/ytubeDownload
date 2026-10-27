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
    private var mediaToolsOnly=false
    private var mediaToolsRestartOnly=false
    private var mediaToolsUiOnly=false
    private var upgradeUiOnly=false
    private var upgradeOnly=false
    private var upgradeRestartOnly=false
    private var serverDiagnosticsOnly = false
    private var serverUiOnly = false
    private var serverOnly = false
    private var sessionOnly = false
    private var sessionRestartOnly = false
    private var sessionUiOnly = false
    private var reportedInstagramOnly = false
    private var instagramDiagnosticOnly = false
    private var galleryOnly = false
    private var galleryRestartOnly = false
    private var galleryUiOnly = false
    private var instagramLiveOnly = false
    private var platformOnly = false
    private var platformRestartOnly = false
    private var platformUiOnly = false
    private var libraryToolsOnly = false
    private var libraryToolsRestartOnly = false
    private var libraryToolsUiOnly = false
    private var repeatOnly = false
    private var repeatRestartOnly = false
    private var repeatUiOnly = false
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
        mediaToolsOnly=arguments?.getString("mediaToolsOnly")=="true"
        mediaToolsRestartOnly=arguments?.getString("mediaToolsRestartOnly")=="true"
        mediaToolsUiOnly=arguments?.getString("mediaToolsUiOnly")=="true"
        upgradeUiOnly=arguments?.getString("upgradeUiOnly")=="true"
        upgradeOnly=arguments?.getString("upgradeOnly")=="true"
        upgradeRestartOnly=arguments?.getString("upgradeRestartOnly")=="true"
        serverDiagnosticsOnly = arguments?.getString("serverDiagnosticsOnly") == "true"
        serverUiOnly = arguments?.getString("serverUiOnly") == "true"
        serverOnly = arguments?.getString("serverOnly") == "true"
        sessionOnly = arguments?.getString("sessionOnly") == "true"
        sessionRestartOnly = arguments?.getString("sessionRestartOnly") == "true"
        sessionUiOnly = arguments?.getString("sessionUiOnly") == "true"
        reportedInstagramOnly = arguments?.getString("reportedInstagramOnly") == "true"
        instagramDiagnosticOnly = arguments?.getString("instagramDiagnosticOnly") == "true"
        galleryOnly = arguments?.getString("galleryOnly") == "true"
        galleryRestartOnly = arguments?.getString("galleryRestartOnly") == "true"
        galleryUiOnly = arguments?.getString("galleryUiOnly") == "true"
        instagramLiveOnly = arguments?.getString("instagramLiveOnly") == "true"
        platformOnly = arguments?.getString("platformOnly") == "true"
        platformRestartOnly = arguments?.getString("platformRestartOnly") == "true"
        platformUiOnly = arguments?.getString("platformUiOnly") == "true"
        libraryToolsOnly = arguments?.getString("libraryToolsOnly") == "true"
        libraryToolsRestartOnly = arguments?.getString("libraryToolsRestartOnly") == "true"
        libraryToolsUiOnly = arguments?.getString("libraryToolsUiOnly") == "true"
        repeatOnly = arguments?.getString("repeatOnly") == "true"
        repeatRestartOnly = arguments?.getString("repeatRestartOnly") == "true"
        repeatUiOnly = arguments?.getString("repeatUiOnly") == "true"
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
            if(mediaToolsOnly || mediaToolsRestartOnly) {
                runBlocking {MediaToolsChecks(this@RuntimeChecks,::report).run(mediaToolsRestartOnly)}
                result.putString("stream",if(mediaToolsRestartOnly)"MEDIA TOOLS RESTART CHECKS PASSED\n" else "MEDIA TOOLS CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(upgradeOnly || upgradeRestartOnly) {
                runBlocking {UpgradeChecks(this@RuntimeChecks,::report).run(upgradeRestartOnly)}
                result.putString("stream",if(upgradeRestartOnly)"UPGRADE RESTART CHECKS PASSED\n" else "UPGRADE CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(serverDiagnosticsOnly) {
                runBlocking {ServerDiagnosticsChecks(this@RuntimeChecks,::report).run()}
                result.putString("stream","SERVER DIAGNOSTICS CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(serverOnly) {
                runBlocking {ServerResolverChecks(this@RuntimeChecks,::report).run()}
                result.putString("stream","SERVER RESOLVER CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(sessionOnly || sessionRestartOnly) {
                InstagramSessionChecks(this@RuntimeChecks,::report).run(sessionRestartOnly)
                result.putString("stream",if(sessionRestartOnly) "SESSION RESTART CHECKS PASSED\n" else "SESSION CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(reportedInstagramOnly) {
                val foreground=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                runBlocking { InstagramLiveCheck(this@RuntimeChecks,::report,"https://www.instagram.com/reel/DezDcSUR2Gg/",true).run() }
                result.putString("stream","REPORTED REEL ATTEMPT FINISHED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(instagramDiagnosticOnly) {
                runBlocking { InstagramDiagnostic(this@RuntimeChecks,::report).run() }
                result.putString("stream","INSTAGRAM DIAGNOSTIC FINISHED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(galleryOnly || galleryRestartOnly) {
                runBlocking { GalleryChecks(this@RuntimeChecks,::report).run(galleryRestartOnly) }
                result.putString("stream",if(galleryRestartOnly) "GALLERY RESTART CHECKS PASSED\n" else "GALLERY CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(instagramLiveOnly) {
                runBlocking { InstagramLiveCheck(this@RuntimeChecks,::report).run() }
                result.putString("stream","INSTAGRAM LIVE ATTEMPT FINISHED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(platformOnly || platformRestartOnly) {
                runBlocking { PlatformChecks(this@RuntimeChecks,::report).run(platformRestartOnly) }
                result.putString("stream",if(platformRestartOnly) "PLATFORM RESTART CHECKS PASSED\n" else "PLATFORM CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(libraryToolsOnly || libraryToolsRestartOnly) {
                runBlocking { LibraryToolsChecks(this@RuntimeChecks,::report).run(libraryToolsRestartOnly) }
                result.putString("stream",if(libraryToolsRestartOnly) "LIBRARY TOOLS RESTART CHECKS PASSED\n" else "LIBRARY TOOLS CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
            if(repeatOnly || repeatRestartOnly) {
                runBlocking { RepeatChecks(this@RuntimeChecks,::report).run(repeatRestartOnly) }
                result.putString("stream",if(repeatRestartOnly) "REPEAT RESTART CHECKS PASSED\n" else "REPEAT CHECKS PASSED\n")
                finish(Activity.RESULT_OK,result);return
            }
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
            if (mediaToolsUiOnly || upgradeUiOnly || serverUiOnly || uiOnly || queueUiOnly || transferUiOnly || libraryUiOnly || progressUiOnly || favouritesUiOnly || repeatUiOnly || libraryToolsUiOnly || platformUiOnly || galleryUiOnly || sessionUiOnly) {
                // UI scenarios own synthetic job states. Drain WorkManager,
                // including requests restored after a force-stop, first.
                Store.setQueuePaused(true)
                WorkManager.getInstance(targetContext).cancelAllWork().result.get()
                runBlocking { withTimeout(60000) { Downloads.awaitIdle() } }
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
                if(mediaToolsUiOnly) {
                    Store.setLibrary(LibraryPreferences(LibraryLayout.List,LibrarySize.Small,LibrarySort.Title))
                    click("Library");click("More actions for Flux media tools source");click("Media tools")
                    val previewRanges=mutableSetOf<String>()
                    fun trimEnd(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null)return null
                        node.rangeInfo?.let {previewRanges.add("${node.className}: ${it.min}..${it.max}, value=${it.current}, visible=${node.isVisibleToUser}, actions=${node.actionList.map{action->action.id}}")}
                        if(node.rangeInfo?.current?.let{it>2f}==true && node.actionList.any{it.id==android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id})return node
                        for(i in 0 until node.childCount){val found=trimEnd(node.getChild(i));if(found!=null)return found}
                        return null
                    }
                    var endHandle=trimEnd(uiAutomation.rootInActiveWindow)
                    val handleDeadline=System.currentTimeMillis()+15000
                    while(endHandle==null && System.currentTimeMillis()<handleDeadline) {
                        fun area(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                            if(node==null)return null
                            if(node.isScrollable && node.rangeInfo==null)return node
                            for(i in 0 until node.childCount){val found=area(node.getChild(i));if(found!=null)return found}
                            return null
                        }
                        area(uiAutomation.rootInActiveWindow)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(200);endHandle=trimEnd(uiAutomation.rootInActiveWindow)
                    }
                    report("Trim slider inspection: "+previewRanges.joinToString("; "))
                    if(endHandle==null) {
                        val labels=StringBuilder()
                        fun describe(node:android.view.accessibility.AccessibilityNodeInfo?) {
                            if(node==null || labels.length>4000)return
                            labels.append("[${node.text}|${node.contentDescription}|${node.className}|scroll=${node.isScrollable}]")
                            for(i in 0 until node.childCount)describe(node.getChild(i))
                        }
                        describe(uiAutomation.rootInActiveWindow);report("Trim dialog inspection: $labels")
                    }
                    check(endHandle!=null){"Trim end slider was unavailable; observed ranges: $previewRanges"}
                    check(endHandle!!.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {
                        putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,2f)
                    })){"Trim end slider did not accept a new selection"}
                    Thread.sleep(300)
                    val editorBounds=android.graphics.Rect()
                    uiAutomation.rootInActiveWindow.getBoundsInScreen(editorBounds)
                    check(editorBounds.width()>=targetContext.resources.displayMetrics.widthPixels*0.9){"Editor did not open full-width"}
                    click("Play selected clip")
                    var preview:android.widget.VideoView?=null
                    runOnMainSync {
                        preview=android.view.inspector.WindowInspector.getGlobalWindowViews()
                            .firstNotNullOfOrNull {it.findViewWithTag<android.widget.VideoView>("flux-trim-preview")}
                        check(preview?.isPlaying==true && preview!!.currentPosition>0){"Selected preview did not play"}
                    }
                    click("Pause preview")
                    runOnMainSync {check(preview?.isPlaying==false){"Preview did not pause"}}
                    click("Play selected clip")
                    Thread.sleep(2700)
                    runOnMainSync {check(preview?.isPlaying==false && preview!!.currentPosition<=2400){"Preview did not stop at the selected end"}}
                    fun pointer(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null)return null
                        if(node.contentDescription?.toString()=="Playback pointer" && node.rangeInfo!=null)return node
                        for(i in 0 until node.childCount){val found=pointer(node.getChild(i));if(found!=null)return found}
                        return null
                    }
                    var cursor=pointer(uiAutomation.rootInActiveWindow)
                    val cursorDeadline=System.currentTimeMillis()+15000
                    while(cursor==null && System.currentTimeMillis()<cursorDeadline) {
                        fun area(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                            if(node==null)return null
                            if(node.isScrollable && node.rangeInfo==null)return node
                            for(i in 0 until node.childCount){val found=area(node.getChild(i));if(found!=null)return found}
                            return null
                        }
                        area(uiAutomation.rootInActiveWindow)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        Thread.sleep(200);cursor=pointer(uiAutomation.rootInActiveWindow)
                    }
                    check(cursor!=null){"Playback pointer slider unavailable"}
                    check(cursor!!.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,Bundle().apply {
                        putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,0.5f)
                    }))
                    Thread.sleep(700)
                    runOnMainSync {check(preview?.isPlaying==false && preview!!.currentPosition in 300..900){"Playback pointer failed to seek without playing"}}
                    report("PASS: full-window editor playback pointer seeks the source without starting playback")
                    report("PASS: release trim preview decodes the saved video, plays and pauses on request, and stops at the selection end")
                    val screenshot=uiAutomation.takeScreenshot()
                    if(screenshot!=null) {
                        java.io.File(targetContext.getExternalFilesDir(null),"media-tools-ui.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                    }
                    click("Add clip");click("New clip")
                    click("Add clip")
                    val before=Store.downloads.value.map{it.id}.toSet()
                    click("Review export");click("Create new file")
                    val queued=Store.downloads.value.single {it.id !in before}
                    check(queued.isLocalEdit && queued.state=="Paused" && queued.editOperation==EditOperation.Trim.name && queued.editEnd==2.0 && queued.editSourceUri.startsWith("content://"))
                    val selectedClips=ClipEdits.decode(queued.editSegments)
                    check(selectedClips.size==2 && selectedClips[0]==TrimClip(0.0,2.0) && selectedClips[1].start==0.0 && selectedClips[1].end in 6.0..6.1)
                    check(queued.duration==9L && queued.editPreset==EditPreset.Mp4H264.name)
                    Downloads.cancel(targetContext,queued)
                    report("PASS: full-window editor adds multiple trim selections and queues one combined MP4 in clip-list order")
                    report("PASS: release Library media-tools dialog, trim/output controls and explicit new-file confirmation queue a local job without changing the source")
                    result.putString("stream","MEDIA TOOLS UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(upgradeUiOnly) {
                    click("Settings");click("Save audio preference")
                    val documents=mutableListOf<Intent>()
                    val monitor=object:ActivityMonitor() {
                        override fun onStartActivity(intent:Intent):ActivityResult? {
                            if(intent.action in setOf(Intent.ACTION_CREATE_DOCUMENT,Intent.ACTION_OPEN_DOCUMENT)) {
                                documents.add(intent);return ActivityResult(Activity.RESULT_CANCELED,null)
                            }
                            return null
                        }
                    }
                    addMonitor(monitor)
                    try {
                        click("Export preferences");click("Import preferences")
                        check(documents.size==2 && documents[0].type=="application/json" && documents[0].getStringExtra(Intent.EXTRA_TITLE)=="Flux-preferences.json")
                        check(documents[1].action==Intent.ACTION_OPEN_DOCUMENT)
                    }finally {removeMonitor(monitor)}
                    click("Library");click("Collections & file tools");click("Show all collections")
                    click("Collections & file tools");click("Close tools")
                    report("PASS: release audio/schedule/backup settings and Library tools are reachable; export/import use Android document pickers without credential sharing")
                    result.putString("stream","UPGRADE UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(serverUiOnly) {
                    check(!ServerResolver.enabled(targetContext))
                    check(runCatching {ServerResolver.validateOrigin("http://127.0.0.1:8080")}.isFailure)
                    click("Settings")
                    val hostingLinks=mutableListOf<String>()
                    val hostingMonitor=object:ActivityMonitor() {
                        override fun onStartActivity(intent:android.content.Intent):ActivityResult? {
                            if(intent.action==android.content.Intent.ACTION_VIEW) {
                                hostingLinks.add(intent.dataString.orEmpty())
                                return ActivityResult(Activity.RESULT_CANCELED,null)
                            }
                            return null
                        }
                    }
                    addMonitor(hostingMonitor)
                    try {
                        click("1. Google Cloud Run setup")
                        check(hostingLinks.size==1)
                        val google=android.net.Uri.parse(hostingLinks[0])
                        check(google.scheme=="https" && google.host=="github.com" && google.path=="/sduos4029-CODE/ytubeDownload/blob/flux/2.2.2-google-first/server/GOOGLE_CLOUD.md")
                        click("2. Render fallback setup")
                        check(hostingLinks.size==2)
                        val render=android.net.Uri.parse(hostingLinks[1])
                        check(render.scheme=="https" && render.host=="render.com" && render.path=="/deploy" && render.getQueryParameter("repo")=="https://github.com/sduos4029-CODE/ytubeDownload/tree/flux/2.2-server-preview")
                    } finally {removeMonitor(hostingMonitor)}
                    report("PASS: release Google-first and Render-fallback setup buttons dispatch the correct HTTPS guide and deployment links without credentials")
                    click("Set up server");click("Copy connection status")
                    val copied=targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(targetContext)?.toString().orEmpty()
                    check(copied.contains("Server saved: false") && copied.contains("Flux ${BuildConfig.VERSION_NAME}") && !copied.contains("https://"))
                    fun find(node:android.view.accessibility.AccessibilityNodeInfo?,label:String):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null)return null
                        if(node.text?.toString()==label)return node
                        for(i in 0 until node.childCount) {find(node.getChild(i),label)?.let {return it}}
                        return null
                    }
                    fun scrollArea(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null)return null
                        if(node.isScrollable)return node
                        for(i in 0 until node.childCount) {scrollArea(node.getChild(i))?.let {return it}}
                        return null
                    }
                    var button:android.view.accessibility.AccessibilityNodeInfo?=null
                    repeat(12) {
                        if(button==null) {
                            button=find(uiAutomation.rootInActiveWindow,"Verify and connect")
                            if(button==null) {
                                scrollArea(uiAutomation.rootInActiveWindow)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                Thread.sleep(300)
                            }
                        }
                    }
                    check(button!=null) {"Server setup control missing after scrolling"}
                    var parent:android.view.accessibility.AccessibilityNodeInfo?=button
                    while(parent!=null && !parent.isClickable)parent=parent.parent
                    check(parent!=null && !parent.isEnabled)
                    report("PASS: release setup/copy-status controls are reachable, copied diagnostics exclude links, empty credentials cannot connect and HTTP loopback is rejected")
                    result.putString("stream","SERVER UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(sessionUiOnly) {
                    InstagramSession.clear(targetContext)
                    val loginMonitor=addMonitor(InstagramLoginActivity::class.java.name,null,false)
                    click("Settings");click("Connect Instagram")
                    val login=loginMonitor.waitForActivityWithTimeout(10000) ?: error("Instagram login did not open")
                    check(login.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                    removeMonitor(loginMonitor)
                    click("Use this connection")
                    check(!InstagramSession.connected.value)
                    click("Close Instagram")
                    InstagramSession.save(targetContext,InstagramSessionChecks.FIXTURE)
                    click("Disconnect")
                    check(!InstagramSession.connected.value && InstagramSession.active.value==0)
                    report("PASS: release Instagram connection opens official login UI, rejects incomplete login and disconnects a generated saved session; no real account used")
                    result.putString("stream","SESSION UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(galleryUiOnly) {
                    var model:FluxModel?=null
                    runOnMainSync {
                        model=ViewModelProvider(activity)[FluxModel::class.java]
                        model!!.query=GalleryChecks.POST;model!!.results=GalleryChecks.items()
                    }
                    click("View photo");click("Reset zoom");click("Download photo")
                    check(Store.downloads.value.any {it.url==GalleryChecks.POST && it.mediaId=="PhotoOne" && it.isPhoto && it.state=="Paused"})
                    click("Discover");click("Select item 3");click("Download selected");click("Queue items")
                    check(Store.downloads.value.any {it.url==GalleryChecks.POST && it.mediaId=="PhotoThree" && it.state=="Paused"})
                    check(Store.downloads.value.none {it.url==GalleryChecks.POST && it.mediaId=="VideoTwo"})
                    click("Discover");click("Download all");click("Keep browsing")
                    check(Store.downloads.value.none {it.url==GalleryChecks.POST && it.mediaId=="VideoTwo"})
                    click("Download all");click("Queue items")
                    check(Store.downloads.value.any {it.url==GalleryChecks.POST && it.mediaId=="VideoTwo" && it.state=="Paused"})
                    check(Store.downloads.value.count {it.url==GalleryChecks.POST && it.state=="Paused"}==5)
                    click("Library");click("Photos");click("Compact")
                    click("More actions for Saved gallery photo");click("File details");click("Copy source link");click("Close details")
                    check(targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()==GalleryChecks.POST)
                    report("PASS: release photo preview/zoom reset/download, individual carousel selection, batch confirmation cancellation, Photos filter and saved photo source copying")
                    result.putString("stream","GALLERY UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(platformUiOnly) {
                    var model:FluxModel?=null
                    runOnMainSync {
                        model=ViewModelProvider(activity)[FluxModel::class.java]
                        model!!.query=PlatformChecks.POST
                        model!!.results=listOf(PlatformChecks.video())
                    }
                    // MainActivity opens on Discover; do not click an already-selected tab.
                    click("Choose quality");click("Download 1080p")
                    check(Store.downloads.value.any { it.url==PlatformChecks.POST && it.state=="Paused" && it.selector.startsWith("video+") })
                    click("Library");click("Compact");click("More actions for Instagram saved fixture");click("File details");click("Copy source link")
                    check(targetContext.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()==PlatformChecks.POST)
                    click("Close details")
                    runOnMainSync { model!!.query="https://www.instagram.com/creator/";model!!.search() }
                    runBlocking { withTimeout(10000) { while(model!!.busy)delay(100) } }
                    check(model!!.error?.contains("profiles")==true && model!!.results.isEmpty())
                    report("PASS: release Instagram result/quality selection queues the Instagram URL, source copying works, and unsupported profiles clear stale results with an error")
                    result.putString("stream","PLATFORM UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(libraryToolsUiOnly) {
                    val helper=LibraryChecks(this,::report)
                    val one=helper.fixture("tools-ui-one").copy(title="Batch tools QA one")
                    val two=helper.fixture("tools-ui-two").copy(title="Batch tools QA two")
                    val failed=one.copy(id="tools-ui-failed",title="Batch tools QA failed",state="Failed",uri="")
                    val cancelled=one.copy(id="tools-ui-cancelled",title="Batch tools QA cancelled",state="Cancelled",uri="")
                    listOf(one,two,failed,cancelled).forEach(Store::add)
                    val before=targetContext.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }
                    click("Library")
                    fun editable(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null)return null
                        if(node.isEditable && node.isVisibleToUser)return node
                        for(i in 0 until node.childCount) { val child=editable(node.getChild(i));if(child!=null)return child }
                        return null
                    }
                    val input=editable(uiAutomation.rootInActiveWindow) ?: error("Library search field unavailable")
                    check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                        putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Batch tools QA")
                    }))
                    Thread.sleep(700)
                    runOnMainSync { targetContext.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken,0) }
                    click("Status: All states");click("Completed");click("Compact")
                    click("Select downloads");click("Select results")
                    fun summaryVisible(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                        if(node==null)return false
                        if(node.isVisibleToUser && node.text?.toString()?.contains(selectionSummary(listOf(one,two)).label)==true)return true
                        return (0 until node.childCount).any { summaryVisible(node.getChild(it)) }
                    }
                    check(summaryVisible(uiAutomation.rootInActiveWindow)) { "Recorded selection summary not visible" }
                    click("Favourite")
                    check(Store.downloads.value.filter { it.id in setOf(one.id,two.id) }.all { it.favourite })
                    click("Favourites");click("Unfavourite")
                    check(Store.downloads.value.filter { it.id in setOf(one.id,two.id) }.none { it.favourite })
                    click("Favourite");click("Done");click("Favourites")
                    click("Status: Completed");click("Failed")
                    click("More actions for ${failed.title}");click("File details");click("Close details")
                    click("Status: Failed");click("Cancelled")
                    click("More actions for ${cancelled.title}");click("File details");click("Close details")
                    check(before.contentEquals(targetContext.contentResolver.openInputStream(Uri.parse(one.uri))!!.use { it.readBytes() }))
                    report("PASS: release search and completed/failed/cancelled filters, filtered selection, bulk favourite/unfavourite and selection retained across filters; file bytes unchanged")
                    result.putString("stream","LIBRARY TOOLS UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
                }
                if(repeatUiOnly) {
                    Store.setQueuePaused(true)
                    val item=LibraryChecks(this,::report).fixture("repeat-ui").copy(title="Repeat UI fixture",favourite=true)
                    Store.add(item)
                    val count=Store.downloads.value.size
                    val original=targetContext.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes() }
                    click("Library");click("Compact");click("More actions for ${item.title}");click("Download again");click("Keep current file")
                    check(Store.downloads.value.size==count)
                    click("More actions for ${item.title}");click("Download again");click("Queue new download")
                    val first=RepeatDownloads.matching(Store.downloads.value,item)!!
                    check(first.id!=item.id && first.state=="Paused" && Store.downloads.value.size==count+1)
                    check(original.contentEquals(targetContext.contentResolver.openInputStream(Uri.parse(item.uri))!!.use { it.readBytes() }))
                    click("Library");click("More actions for ${item.title}");click("Download again");click("Show existing download")
                    check(Store.downloads.value.size==count+1)
                    Store.change(first.id,true) { it.copy(state="Cancelled") }
                    Store.removeHistory(setOf(first.id))
                    targetContext.contentResolver.delete(Uri.parse(item.uri),null,null)
                    click("Library");click("More actions for ${item.title}");click("File details");click("Download replacement");click("Queue new download")
                    val replacement=RepeatDownloads.matching(Store.downloads.value,item)!!
                    check(replacement.id!=first.id && replacement.state=="Paused")
                    check(Store.downloads.value.first { it.id==item.id }==item)
                    report("PASS: release confirmation dismiss/queue, duplicate show-existing and missing-file recovery controls preserve the original history/favourite and respect paused queue")
                    result.putString("stream","REPEAT UI CHECKS PASSED\n");finish(Activity.RESULT_OK,result);return
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
                        val snapshot=Store.downloads.value.find { it.id==fixture.id }
                        error("Progress UI label not visible after scrolling: $label; state=${snapshot?.state}; progress=${snapshot?.progress}")
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
                Store.setQueuePaused(false)
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
