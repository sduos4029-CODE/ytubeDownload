package com.flux.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class ServerIssue {
    NotConfigured, AccessDenied, SourceUnavailable, RateLimited, Busy, Redirect,
    Incompatible, InvalidResponse, Network, Tls, TooLarge, StartupTimeout
}

class ServerFailure(val issue:ServerIssue,message:String):Exception(message)

/** These are observations from a check, never a promise of current source availability. */
data class ServerStatus(val origin:String="",val phase:String="Saved",val issue:ServerIssue?=null,val checkedAt:Long=0) {
    val label:String get()=when(phase) {
        "Checking" -> "Checking server"
        "Waking" -> "Waiting for server response"
        "Reachable" -> "Last check: server reachable"
        "Failed" -> when(issue) {
            ServerIssue.AccessDenied -> "Last check: access key rejected"
            ServerIssue.SourceUnavailable -> "Last lookup: Instagram retrieval failed"
            ServerIssue.RateLimited -> "Last request: server limit reached"
            ServerIssue.Incompatible,ServerIssue.InvalidResponse -> "Last check: incompatible response"
            ServerIssue.Tls -> "Last check: HTTPS certificate failed"
            ServerIssue.StartupTimeout -> "Last check: server startup timed out"
            else -> "Last check: server unavailable"
        }
        else -> "Saved server · not checked this session"
    }
}

object ServerTransport {
    data class Reply(val code:Int,val json:JSONObject?,val html:Boolean)
    fun retryHealth(code:Int,html:Boolean,host:String):Boolean = code in setOf(502,503,504) ||
        (code==200 && html && host.endsWith(".onrender.com",true))

    /** Cancellation closes the active socket. No redirects, cookie jar or custom TLS trust. */
    suspend fun request(origin:String,path:String,token:String?=null,body:JSONObject?=null,
        timeout:Int=55000,limit:Int=4*1024*1024):Reply = suspendCancellableCoroutine { continuation ->
        val connection=(URL(origin+path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects=false
            connectTimeout=minOf(5000,timeout);readTimeout=timeout
            requestMethod=if(body==null)"GET" else "POST"
            token?.let {setRequestProperty("Authorization","Bearer $it")}
            setRequestProperty("Accept","application/json")
            useCaches=false
        }
        continuation.invokeOnCancellation {
            // TLS/socket shutdown must not block the UI thread that cancels us.
            Dispatchers.IO.dispatch(continuation.context,Runnable {runCatching {connection.disconnect()}})
        }
        Dispatchers.IO.dispatch(continuation.context,Runnable {
            val outcome:Result<Reply> = try {
                if(!continuation.isActive)return@Runnable
                if(body!=null) {
                    connection.doOutput=true
                    connection.setRequestProperty("Content-Type","application/json")
                    connection.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}
                }
                val code=connection.responseCode
                if(code in 300..399)throw ServerFailure(ServerIssue.Redirect,"Flux server redirected the request. Enter its final HTTPS origin in Settings.")
                val stream=if(code in 200..299)connection.inputStream else connection.errorStream
                val bytes=stream?.use {input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
                    while(true) {
                        val count=input.read(buffer);if(count<0)break
                        if(output.size()+count>limit)throw ServerFailure(ServerIssue.TooLarge,"Flux server response is too large.")
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                } ?: byteArrayOf()
                val text=String(bytes,Charsets.UTF_8)
                val json=runCatching {JSONObject(text)}.getOrNull()
                val reply=Reply(code,json,connection.contentType.orEmpty().contains("text/html",true) || text.trimStart().startsWith("<"))
                Result.success(reply)
            }catch(error:Exception) {
                val safe=when(error) {
                    is ServerFailure -> error
                    is javax.net.ssl.SSLException -> ServerFailure(ServerIssue.Tls,"Flux server HTTPS certificate validation failed. Check the hosting certificate.")
                    else -> ServerFailure(ServerIssue.Network,"Flux server network connection failed. Check its HTTPS address and hosting status.")
                }
                Result.failure(safe)
            }finally {runCatching {connection.disconnect()}}
            // Fully release this request before a caller starts the next one.
            if(continuation.isActive)continuation.resumeWith(outcome)
        })
    }
}
