package com.flux.downloader

import android.app.Instrumentation
import android.content.ComponentName
import androidx.core.content.FileProvider
import android.system.Os
import java.io.File
import java.security.KeyStore

class InstagramSessionChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    companion object { const val FIXTURE="sessionid=fixture-local-session; csrftoken=fixture-csrf; ds_user_id=42; unrelated=ignored" }
    fun run(restart:Boolean=false) {
        InstagramSession.init(app)
        val source="https://www.instagram.com/reel/DezDcSUR2Gg/"
        if(restart) {
            check(InstagramSession.connected.value)
            val dir=File(app.noBackupFilesDir,"instagram-request-cookies")
            check(!File(dir,"orphan-fixture.txt").exists())
            InstagramSession.lease(app,source)!!.use { check(it.file.readText().contains("fixture-local-session")) }
            InstagramSession.clear(app)
            check(!InstagramSession.connected.value)
            report("PASS: encrypted Instagram connection survives force-stop; orphan request credentials removed before recovery; disconnect clears saved state")
            return
        }
        InstagramSession.clear(app)
        // Exercise Android's HTTP-only browser-cookie bridge locally. These
        // generated cookies are cleared before any real network probe.
        val set=java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie("https://www.instagram.com/","sessionid=fixture-browser-session; Domain=.instagram.com; Path=/; Secure; HttpOnly") {set.countDown()}
            }
        }
        check(set.await(10,java.util.concurrent.TimeUnit.SECONDS))
        val browserHeader=android.webkit.CookieManager.getInstance().getCookie("https://www.instagram.com/").orEmpty()
        check(InstagramSession.parse(browserHeader)["sessionid"]=="fixture-browser-session")
        val cleared=java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.webkit.CookieManager.getInstance().removeAllCookies {android.webkit.CookieManager.getInstance().flush();cleared.countDown()}
        }
        check(cleared.await(10,java.util.concurrent.TimeUnit.SECONDS))
        check(runCatching {InstagramSession.parse("csrftoken=alone")}.isFailure)
        check(runCatching {InstagramSession.parse("sessionid=bad\nvalue")}.isFailure)
        check(runCatching {InstagramSession.parse("sessionid="+"x".repeat(40000))}.isFailure)
        check(InstagramSession.parse(FIXTURE).keys==setOf("sessionid","csrftoken","ds_user_id"))
        InstagramSession.save(app,FIXTURE,"Flux fixture browser")
        check(InstagramSession.connected.value)
        val encrypted=app.getSharedPreferences("instagram_connection",0).getString("encrypted","").orEmpty()
        check(encrypted.isNotEmpty() && !encrypted.contains("fixture-local-session") && !encrypted.contains("fixture-csrf"))
        val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey("flux.instagram.session.v1",null)
        check(key.encoded==null)
        for(url in listOf("https://www.youtube.com/watch?v=fixture","ytsearch5:music","https://www.instagram.com.evil.invalid/reel/DezDcSUR2Gg/","http://www.instagram.com/reel/DezDcSUR2Gg/","http://127.0.0.1/fixture.mpd","https://cdninstagram.com/video.mp4")) {
            check(InstagramSession.lease(app,url)==null)
        }
        val one=InstagramSession.lease(app,source)!!;val two=InstagramSession.lease(app,source)!!
        try {
            check(one.userAgent=="Flux fixture browser")
            check(one.file!=two.file && InstagramSession.active.value==2)
            check(one.file.canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath+"/"))
            check(Os.stat(one.file.absolutePath).st_mode and 63==0)
            check(one.file.readLines().drop(1).all { val parts=it.split('\t');parts.size==7 && parts[0]==".instagram.com" && parts[3]=="TRUE" })
            check(runCatching {FileProvider.getUriForFile(app,"${app.packageName}.files",one.file)}.isFailure)
            check(runCatching {InstagramSession.clear(app)}.isFailure)
            check(runCatching {InstagramSession.save(app,FIXTURE,"Flux fixture browser")}.isFailure)
        }finally {one.close();two.close()}
        check(!one.file.exists() && !two.file.exists() && InstagramSession.active.value==0)
        one.close();check(InstagramSession.active.value==0)
        val packet=org.json.JSONObject(encrypted)
        val ciphertext=android.util.Base64.decode(packet.getString("data"),android.util.Base64.NO_WRAP)
        ciphertext[0]=(ciphertext[0].toInt() xor 1).toByte()
        packet.put("data",android.util.Base64.encodeToString(ciphertext,android.util.Base64.NO_WRAP))
        app.getSharedPreferences("instagram_connection",0).edit().putString("encrypted",packet.toString()).commit()
        check(runCatching {InstagramSession.lease(app,source)}.isFailure && !InstagramSession.connected.value)
        val info=app.packageManager.getActivityInfo(ComponentName(app,InstagramLoginActivity::class.java),0)
        check(!info.exported)
        InstagramSession.clear(app)
        check(!KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias("flux.instagram.session.v1"))
        check(InstagramSession.lease(app,source)==null)
        // The next process must clean this simulated interrupted credential file.
        InstagramSession.save(app,FIXTURE,"Flux fixture browser")
        File(app.noBackupFilesDir,"instagram-request-cookies").apply {mkdirs()}.resolve("orphan-fixture.txt").writeText("fixture only")
        report("PASS: local encrypted/non-exportable storage, bounded cookie parsing, Instagram-only HTTPS credential scope, separate 0600 request files, active-request protection, cleanup and non-exported login activity")
    }
}
