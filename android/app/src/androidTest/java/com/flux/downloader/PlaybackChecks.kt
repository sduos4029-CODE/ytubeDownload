package com.flux.downloader

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.VideoView
import java.security.MessageDigest

class PlaybackChecks(private val test:Instrumentation,private val report:(String)->Unit) {
    fun run(click:(String)->Unit,visible:(String)->Boolean) {
        val app=test.targetContext
        check(PlaybackControls.seek(2500,10000,6000)==6000L && PlaybackControls.seek(1000,-10000,6000)==0L)
        check(PlaybackControls.level(.5f,-100f,100f)==1f && PlaybackControls.level(.5f,100f,100f)==0f)
        check(PlaybackControls.time(3661000)=="1:01:01")
        val bytes=test.context.assets.open("combined.mp4").use {it.readBytes()}
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"Flux player gestures QA.mp4");put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/FluxTests")
        })!!
        app.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        val source=Download(id="playback-ui-fixture",url="",title="Playback gestures QA",thumbnail="",selector="fixture",label="source",audioOnly=false,duration=6,folder="",state="Complete",uri=uri.toString(),mime="video/mp4",bytes=bytes.size.toLong())
        val prefs=Store.library.value;val audio=app.getSystemService(AudioManager::class.java);val originalVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var activity:PlaybackActivity?=null
        val monitor=test.addMonitor(PlaybackActivity::class.java.name,null,false)
        fun await(message:String,condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+15000
            while(SystemClock.uptimeMillis()<end){if(condition())return;Thread.sleep(100)}
            error(message)
        }
        try {
            Store.add(source);click("Library");click("Library options")
            for(label in listOf("Compact","Small","Medium","Large"))check(visible(label)){"Size not visible without horizontal scroll: $label"}
            click("Large");check(Store.library.value.size==LibrarySize.Large)
            click("Compact");click("Done");click("Open ${source.title}")
            activity=monitor.waitForActivityWithTimeout(15000) as? PlaybackActivity ?: error("Library did not launch the Flux player")
            val foreground=checkNotNull(activity)
            var view:VideoView?=null
            await("Saved player view unavailable") {
                test.runOnMainSync {view=foreground.window.decorView.findViewWithTag("flux-saved-player")}
                view!=null
            }
            val video=view?:error("Saved player view unavailable")
            fun playing():Boolean {var result=false;test.runOnMainSync{result=video.isPlaying};return result}
            fun position():Int {var result=0;test.runOnMainSync{result=video.currentPosition};return result}
            fun duration():Int {var result=0;test.runOnMainSync{result=video.duration};return result}
            await("Native video did not prepare and play"){playing() && duration()>0}
            click("Pause video");await("Bottom pause did not stop playback"){!playing()}
            val box=Rect()
            fun bounds(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                if(node==null)return false
                if(node.contentDescription?.toString()=="Video gestures"){node.getBoundsInScreen(box);return true}
                return (0 until node.childCount).any {bounds(node.getChild(it))}
            }
            check(bounds(test.uiAutomation.rootInActiveWindow) && box.height()>0)
            fun event(action:Int,x:Float,y:Float,down:Long){
                val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN
                check(test.uiAutomation.injectInputEvent(e,true));e.recycle()
            }
            fun doubleTap(right:Boolean){
                val x=box.left+box.width()*(if(right).8f else .2f);val y=box.top+box.height()*.35f
                repeat(2){val down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN,x,y,down);Thread.sleep(50);event(MotionEvent.ACTION_UP,x,y,down);Thread.sleep(80)}
            }
            doubleTap(true);await("Right double-tap did not seek to clamped end"){position()>=duration()-500}
            doubleTap(false);await("Left double-tap did not seek back"){position()<500}
            click("Forward 10 seconds");await("Bottom forward did not seek"){position()>=duration()-500}
            click("Back 10 seconds");await("Bottom back did not seek"){position()<500}
            fun swipe(right:Boolean,up:Boolean){
                val x=box.left+box.width()*(if(right).8f else .2f)
                val from=box.top+box.height()*(if(up).7f else .3f);val to=box.top+box.height()*(if(up).3f else .7f)
                val down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN,x,from,down)
                for(i in 1..12){Thread.sleep(20);event(MotionEvent.ACTION_MOVE,x,from+(to-from)*i/12,down)}
                event(MotionEvent.ACTION_UP,x,to,down);Thread.sleep(400)
            }
            val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);val middle=max/2
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,middle,0);Thread.sleep(400)
            val brightnessBefore=foreground.window.attributes.screenBrightness
            swipe(true,true);val louder=audio.getStreamVolume(AudioManager.STREAM_MUSIC);check(louder>middle){"Right swipe did not raise actual media volume"}
            check(foreground.window.attributes.screenBrightness==brightnessBefore)
            swipe(true,false);check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)<louder){"Right swipe did not lower volume"}
            val unchangedVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            swipe(false,true);val brighter=foreground.window.attributes.screenBrightness;check(brighter in .05f..1f)
            swipe(false,false);check(foreground.window.attributes.screenBrightness<brighter)
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==unchangedVolume)
            click("Playback settings");check(visible("Playback volume") && visible("Playback brightness"));click("Done with playback settings")
            click("Play video");await("Bottom play did not resume"){playing()}
            test.runOnMainSync{foreground.moveTaskToBack(true)}
            await("Playback continued in background"){!playing()}
            check(foreground.window.attributes.screenBrightness==brightnessBefore && !video.keepScreenOn)
            val after=app.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
            check(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(MessageDigest.getInstance("SHA-256").digest(after)))
            report("PASS: all four Library sizes are visible, Library opens the native saved video player, real right/left double taps and bottom controls seek/play/pause, right swipes change actual media volume, left swipes change only window brightness, background pauses/restores brightness and source bytes stay unchanged")
        } catch(error:Throwable) {
            val tree=StringBuilder()
            fun describe(node:android.view.accessibility.AccessibilityNodeInfo?,depth:Int=0) {
                if(node==null || depth>12 || tree.length>12000)return
                if(node.isVisibleToUser)tree.append(" ".repeat(depth)).append("${node.className} text=${node.text} desc=${node.contentDescription}\n")
                for(i in 0 until node.childCount)describe(node.getChild(i),depth+1)
            }
            describe(test.uiAutomation.rootInActiveWindow);report("Synthetic playback fixture tree:\n$tree")
            throw error
        } finally {
            activity?.let {test.runOnMainSync{it.finish()}}
            test.removeMonitor(monitor);audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0)
            Store.removeHistory(setOf(source.id));Store.setLibrary(prefs);app.contentResolver.delete(uri,null,null)
        }
        val invalid=test.startActivitySync(PlaybackActivity.intent(app,"not-a-saved-video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try{await("Missing file screen not shown"){visible("This saved video is no longer available.")}}
        finally{test.runOnMainSync{invalid.finish()}}
        check(!app.packageManager.getActivityInfo(android.content.ComponentName(app,PlaybackActivity::class.java),0).exported)
        report("PASS: saved player is non-exported, rejects unknown video identities and preserves existing saved files; volume QA restores the previous device setting")
    }
}
