package com.flux.downloader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional owner-hosted service. Its access key never enters jobs or media requests. */
object ServerResolver {
    private const val ALIAS="flux.resolver.v1"
    private const val PREFS="flux_resolver"
    private const val LIMIT=4*1024*1024
    private val changed=MutableStateFlow(0)
    val revision=changed.asStateFlow()
    data class Config(val origin:String,val token:String)
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun directory(c:Context)=File(c.noBackupFilesDir,"resolver-info")
    fun recover(c:Context) {directory(c).deleteRecursively()}
    fun origin(c:Context)=prefs(c).getString("origin","").orEmpty()
    fun enabled(c:Context)=origin(c).isNotEmpty()

    fun validateOrigin(value:String):String {
        val uri=runCatching {URI(value.trim())}.getOrElse {error("Use the HTTPS address of your Flux server.")}
        val fixture=BuildConfig.DEBUG && uri.scheme=="http" && uri.host=="127.0.0.1"
        require((uri.scheme=="https" || fixture) && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path in setOf("","/") && (uri.port in setOf(-1,443) || fixture)) {
            "Use the HTTPS origin of your Flux server, without a path, password or query."
        }
        require(fixture || (!uri.host.contains(':') && uri.host.contains('.') && !uri.host.matches(Regex("[0-9.]+")) && !uri.host.endsWith(".local",true))) {"Use a public HTTPS server hostname."}
        return value.trim().trimEnd('/')
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    /** Caller tests ownership/protocol first. Save only on successful authenticated status. */
    fun connect(c:Context,address:String,token:String) {
        val config=Config(validateOrigin(address),token)
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {"Enter the access key generated for your Flux server."}
        val response=call(config,"/v1/status",null)
        require(response.optInt("protocol")==1 && response.optString("service")=="flux-resolver" && response.optString("authentication")=="no-instagram-login") {"This address is not a compatible Flux server."}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val plain=JSONObject().put("origin",config.origin).put("token",token).toString().toByteArray()
        val bytes=try {cipher.doFinal(plain)} finally {plain.fill(0)}
        val packet=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))
        check(prefs(c).edit().putString("origin",config.origin).putString("encrypted",packet.toString()).commit()) {"Could not save the Flux server."}
        changed.value++
    }
    fun clear(c:Context) {
        check(prefs(c).edit().clear().commit())
        // Existing requests have immutable snapshots; do not delete active info files.
        KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(ALIAS)
        changed.value++
    }
    private fun config(c:Context):Config? {
        if(!enabled(c))return null
        try {
            val packet=JSONObject(prefs(c).getString("encrypted",null) ?: error("Missing key"))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(packet.getString("iv"),Base64.NO_WRAP)))}
            val bytes=cipher.doFinal(Base64.decode(packet.getString("data"),Base64.NO_WRAP))
            val data=try {JSONObject(String(bytes,Charsets.UTF_8))} finally {bytes.fill(0)}
            return Config(validateOrigin(data.getString("origin")),data.getString("token"))
        } catch(_:Exception) {error("Flux server access key could not be read. Reconnect the server in Settings.")}
    }
    private fun call(config:Config,path:String,body:JSONObject?):JSONObject {
        val connection=(URL(config.origin+path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false;connectTimeout=10000;readTimeout=55000
            requestMethod=if(body==null)"GET" else "POST"
            setRequestProperty("Authorization","Bearer ${config.token}")
            setRequestProperty("Accept","application/json")
        }
        try {
            if(body!=null) {
                connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}
            }
            val code=connection.responseCode
            require(code !in 300..399) {"Flux server redirected the request. Enter its final HTTPS origin in Settings."}
            val stream=if(code in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream?.use {it.readBytesBounded(LIMIT)} ?: byteArrayOf()
            val json=runCatching {JSONObject(String(bytes,Charsets.UTF_8))}.getOrElse {error("Flux server returned an invalid response.")}
            if(code !in 200..299)error(when(code) {
                401,403 -> "Flux server access was rejected. Check its access key in Settings."
                422 -> "The Flux server could not retrieve this public Instagram post. Instagram may be limiting this server; no Instagram login is required by Flux."
                429 -> "Flux server request limit reached. Wait one minute before retrying."
                else -> "Flux server is unavailable (HTTP $code). Retry later."
            })
            return json
        }catch(e:java.io.IOException) {throw java.io.IOException("Could not reach the Flux server. Check its HTTPS address and hosting status.",e)}
        finally {connection.disconnect()}
    }
    private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
        while(true) {val count=read(buffer);if(count<0)break;require(out.size()+count<=limit) {"Flux server response is too large."};out.write(buffer,0,count)}
        return out.toByteArray()
    }
    fun resolve(c:Context,url:String):JSONObject? {
        if(!MediaLinks.isInstagram(url))return null // scope BEFORE reading credentials
        val config=config(c) ?: return null
        val canonical=MediaLinks.parse(url)!!.url
        val response=call(config,"/v1/resolve",JSONObject().put("url",canonical))
        require(response.optInt("protocol")==1 && response.optString("source_url")==canonical && response.optInt("expires_in") in 1..120) {"Flux server returned details for a different post or an unsupported protocol."}
        return sanitize(response.getJSONObject("info"),canonical)
    }
    private fun mediaUrl(value:String):String {
        val u=URI(value)
        val fixture=BuildConfig.DEBUG && u.scheme=="http" && u.host=="127.0.0.1"
        require(value.length<=16384 && value.none {it.isISOControl()} && u.userInfo==null && u.fragment==null &&
            (fixture || (u.scheme=="https" && u.port in setOf(-1,443) && listOf("cdninstagram.com","fbcdn.net","instagram.com").any {u.host==it || u.host?.endsWith(".$it")==true}))) {"Flux server returned an unexpected media address."}
        return value
    }
    /** Rebuild approved data, excluding arbitrary commands, paths, cookies and headers. */
    fun sanitize(raw:JSONObject,url:String):JSONObject {
        require(MediaLinks.isInstagram(url))
        val nodes=raw.optJSONArray("entries") ?: JSONArray().put(raw)
        require(nodes.length() in 1..20)
        val clean=JSONArray()
        for(i in 0 until nodes.length()) {
            val node=nodes.getJSONObject(i);val id=node.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val kind=node.optString("flux_media_kind","video");require(kind in setOf("photo","video"))
            val formats=node.getJSONArray("formats");require(formats.length() in 1..100)
            val approved=JSONArray()
            val formatIds=mutableSetOf<String>()
            for(j in 0 until formats.length()) {
                val f=formats.getJSONObject(j);val formatId=f.getString("format_id")
                require(formatIds.add(formatId) && formatId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && f.optString("protocol","https")=="https" && !f.has("fragments"))
                val ext=f.getString("ext")
                require(ext in if(kind=="photo")setOf("jpg","jpeg","png","webp") else setOf("mp4","webm","m4a","mov"))
                val entry=JSONObject().put("format_id",formatId).put("ext",ext).put("url",mediaUrl(f.getString("url")))
                    .put("protocol",if(BuildConfig.DEBUG && f.getString("url").startsWith("http://127.0.0.1:"))"http" else "https")
                    .put("vcodec",f.optString("vcodec","none").take(100)).put("acodec",f.optString("acodec","none").take(100))
                    .put("http_headers",JSONObject().put("Referer","https://www.instagram.com/"))
                listOf("width","height","fps","abr","tbr","asr","audio_channels","filesize","filesize_approx").forEach {key ->
                    val n=f.optDouble(key,0.0);require(n.isFinite() && n in 0.0..1e15);entry.put(key,n)
                }
                approved.put(entry)
            }
            val thumbnail=node.optString("thumbnail").let {if(it.isBlank())"" else mediaUrl(it)}
            clean.put(JSONObject().put("id",id).put("title",node.optString("title","Instagram post").take(300))
                .put("uploader",node.optString("uploader").take(100)).put("thumbnail",thumbnail).put("duration",node.optDouble("duration",0.0).takeIf {it.isFinite() && it>=0} ?: 0.0)
                .put("formats",approved).put("flux_media_kind",kind).put("webpage_url",url).put("extractor_key","Instagram").put("extractor","instagram"))
        }
        val result=if(clean.length()==1)clean.getJSONObject(0) else JSONObject().put("_type","playlist").put("entries",clean)
        InstagramPost.parse(result,url)
        return result
    }
    fun downloadInfo(c:Context,url:String,itemId:String):Lease? {
        val info=resolve(c,url) ?: return null
        val nodes=info.optJSONArray("entries") ?: JSONArray().put(info)
        val selected=(0 until nodes.length()).map {nodes.getJSONObject(it)}.firstOrNull {itemId.isBlank() || it.getString("id")==itemId}
            ?: error("The selected Instagram item changed. Load the post again.")
        val dir=directory(c).apply {check(isDirectory || mkdirs())}
        val file=File(dir,"${UUID.randomUUID()}.json")
        try {check(file.createNewFile());Os.chmod(file.absolutePath,384);file.writeText(selected.toString());return Lease(file)}
        catch(e:Exception) {file.delete();throw e}
    }
    class Lease(val file:File):AutoCloseable {override fun close() {file.delete()}}
}
