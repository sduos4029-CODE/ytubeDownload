package com.flux.downloader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local, optional Instagram authentication. Never part of job/history JSON. */
object InstagramSession {
    private const val ALIAS="flux.instagram.session.v1"
    private const val PREFS="instagram_connection"
    private val names=setOf("sessionid","csrftoken","ds_user_id","mid","ig_did","ig_nrcb","rur","ig_direct_region_hint")
    private var ready=false
    private val connectedState=MutableStateFlow(false)
    val connected=connectedState.asStateFlow()
    private val activeState=MutableStateFlow(0)
    val active=activeState.asStateFlow()
    private val leases=mutableSetOf<File>()
    private fun prefs(context: Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun directory(context: Context)=File(context.noBackupFilesDir,"instagram-request-cookies")

    @Synchronized fun init(context: Context) {
        if(ready)return
        // No native commands exist at Application startup. Clean credentials
        // left by a killed process before recovered workers can start.
        directory(context).deleteRecursively()
        ready=true
        connectedState.value=prefs(context).contains("encrypted")
    }
    fun parse(header: String): Map<String,String> {
        require(header.length<=32768) { "Instagram returned an oversized session." }
        val cookies=header.split(';').mapNotNull { part ->
            val split=part.indexOf('=');if(split<=0)return@mapNotNull null
            val name=part.substring(0,split).trim();val value=part.substring(split+1).trim()
            if(name !in names)return@mapNotNull null
            require(value.length<=4096 && value.none { it.isISOControl() }) { "Instagram returned an invalid session." }
            if(value.isEmpty())null else name to value
        }.toMap()
        require(!cookies["sessionid"].isNullOrEmpty()) { "Finish signing in on Instagram, then tap Use this connection." }
        return cookies
    }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun save(context: Context,header: String,userAgent:String?=null) {
        init(context)
        check(leases.isEmpty()) { "Wait for the current Instagram request to finish or pause it first." }
        require(userAgent==null || (userAgent.length<=1024 && userAgent.none {it.isISOControl()}))
        val payload=JSONObject(parse(header))
        if(!userAgent.isNullOrBlank())payload.put("_userAgent",userAgent)
        val plain=payload.toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=try {cipher.doFinal(plain)} finally {plain.fill(0)}
        val packet=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).toString()
        check(prefs(context).edit().putString("encrypted",packet).commit()) { "Could not save the Instagram connection." }
        connectedState.value=true
    }
    @Synchronized fun clear(context: Context) {
        init(context)
        check(leases.isEmpty()) { "Pause Instagram requests before disconnecting." }
        check(prefs(context).edit().remove("encrypted").commit())
        connectedState.value=false
        KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(ALIAS)
        directory(context).deleteRecursively()
    }
    @Synchronized fun lease(context: Context,url: String): Lease? {
        // Scope before reading any credential, including for YouTube, CDN URLs
        // and debug loopback fixtures. CookieJar then enforces domain + HTTPS.
        if(!MediaLinks.isInstagram(url))return null
        init(context)
        val encrypted=prefs(context).getString("encrypted",null) ?: return null
        val cookies=try {
            val packet=JSONObject(encrypted)
            val iv=Base64.decode(packet.getString("iv"),Base64.NO_WRAP)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv)) }
            val plain=cipher.doFinal(Base64.decode(packet.getString("data"),Base64.NO_WRAP))
            try {JSONObject(String(plain,Charsets.UTF_8))} finally {plain.fill(0)}
        }catch(_: Exception) {
            prefs(context).edit().remove("encrypted").commit();connectedState.value=false
            error("Instagram connection could not be read. Reconnect Instagram in Settings.")
        }
        val dir=directory(context).apply { check(isDirectory || mkdirs()) }
        val file=File(dir,"${UUID.randomUUID()}.txt")
        try {
            check(file.createNewFile());Os.chmod(file.absolutePath,384) // 0600
            file.bufferedWriter().use { out ->
                out.appendLine("# Netscape HTTP Cookie File")
                names.forEach { name ->
                    val value=cookies.optString(name)
                    if(value.isNotEmpty()) {
                        require(value.none { it.isISOControl() })
                        out.appendLine(".instagram.com\tTRUE\t/\tTRUE\t0\t$name\t$value")
                    }
                }
            }
            leases.add(file);activeState.value=leases.size
            return Lease(file,cookies.optString("_userAgent").takeIf {it.isNotBlank()})
        }catch(error: Exception) {file.delete();throw error}
    }
    class Lease internal constructor(val file: File,val userAgent:String?): AutoCloseable {
        override fun close() = synchronized(InstagramSession) {
            file.delete();leases.remove(file);activeState.value=leases.size
        }
    }
}
