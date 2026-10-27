package com.flux.downloader

import android.app.Instrumentation
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ServerDiagnosticsChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    private val token="diagnostic_"+"b".repeat(36)
    suspend fun run()=coroutineScope {
        ServerResolver.clear(app)
        check((runCatching {ServerResolver.testConnection(app)}.exceptionOrNull() as? ServerFailure)?.issue==ServerIssue.NotConfigured)
        check(ServerTransport.retryHealth(503,true,"example.com"))
        check(ServerTransport.retryHealth(200,true,"preview.onrender.com"))
        check(!ServerTransport.retryHealth(200,true,"onrender.com.evil.invalid"))
        check(!ServerTransport.retryHealth(200,true,"example.com"))
        Fixture().use {server ->
            server.healthFailures.set(2)
            ServerResolver.connect(app,server.origin,token)
            check(server.healthCalls.get()==3 && server.statusCalls.get()==1)
            check(!server.healthKeySeen && !server.cookieSeen)
            check(ServerResolver.status.value.phase=="Reachable")
            val packet=app.getSharedPreferences("flux_resolver",0).getString("encrypted",null)
            server.apiCode=401;server.apiHtml=true
            val rejected=runCatching {ServerResolver.testConnection(app)}.exceptionOrNull() as? ServerFailure
            check(rejected?.issue==ServerIssue.AccessDenied && ServerResolver.status.value.issue==ServerIssue.AccessDenied)
            check(app.getSharedPreferences("flux_resolver",0).getString("encrypted",null)==packet)
            server.apiCode=200;server.apiHtml=false
            ServerResolver.testConnection(app)
            val before=server.statusCalls.get()
            for(mode in listOf("wrong-service","html","redirect")) {
                server.healthMode=mode
                check(runCatching {ServerResolver.testConnection(app)}.isFailure)
                check(server.statusCalls.get()==before) {"Key was sent before a compatible health check"}
            }
            server.healthMode="sleep"
            val sleeping=launch {ServerResolver.testConnection(app)}
            withTimeout(5000) {while(ServerResolver.status.value.phase!="Waking")delay(20)}
            sleeping.cancelAndJoin()
            val after=server.healthCalls.get();delay(1200)
            check(server.healthCalls.get()==after && ServerResolver.status.value.phase=="Saved")
            server.healthMode="stall"
            val stalled=launch {ServerResolver.testConnection(app)}
            withTimeout(5000) {while(!server.stallEntered)delay(20)}
            withTimeout(3000) {stalled.cancelAndJoin();while(!server.stallDisconnected)delay(20)}
            server.healthMode="up"
            ServerResolver.testConnection(app)
            server.sourceCode=422
            val source="https://www.instagram.com/reel/DezDcSUR2Gg/"
            val unavailable=runCatching {ServerResolver.resolve(app,source)}.exceptionOrNull() as? ServerFailure
            check(unavailable?.issue==ServerIssue.SourceUnavailable)
            check(ServerResolver.status.value.label.contains("Instagram retrieval failed"))
            val safe=ServerResolver.diagnostics(app)
            check(safe.contains("SourceUnavailable") && !safe.contains(token) && !safe.contains(server.origin) && !safe.contains(source))
            val count=server.healthCalls.get()
            check(ServerResolver.resolve(app,"https://www.youtube.com/watch?v=fixture")==null)
            check(server.healthCalls.get()==count)
            check(!server.healthKeySeen && !server.cookieSeen && server.escapeCalls.get()==0)
        }
        ServerResolver.clear(app)
        check(!ServerResolver.enabled(app))
        report("PASS: missing configuration, unauthenticated health before key use, two sleeping-host retries, HTML 401 classification, incompatible/HTML/redirect rejection before key transmission, cancellation during wait and blocked socket, source rejection distinct from reachability, safe diagnostics and YouTube exclusion")
    }
    private inner class Fixture:AutoCloseable {
        private val socket=ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"))
        private val pool=Executors.newCachedThreadPool()
        @Volatile private var running=true
        @Volatile var healthMode="up"
        @Volatile var apiCode=200
        @Volatile var apiHtml=false
        @Volatile var sourceCode=200
        @Volatile var healthKeySeen=false
        @Volatile var cookieSeen=false
        @Volatile var stallEntered=false
        @Volatile var stallDisconnected=false
        val healthFailures=AtomicInteger(0)
        val healthCalls=AtomicInteger(0)
        val statusCalls=AtomicInteger(0)
        val escapeCalls=AtomicInteger(0)
        val origin="http://127.0.0.1:${socket.localPort}"
        init {pool.execute {
            while(running)try {val client=socket.accept();pool.execute {runCatching {client.use {connection ->
                connection.soTimeout=10000
                val reader=connection.getInputStream().bufferedReader()
                val first=reader.readLine().orEmpty().split(' ');val path=first.getOrElse(1){""}
                val headers=mutableMapOf<String,String>()
                while(true) {val line=reader.readLine().orEmpty();if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val length=headers["content-length"]?.toIntOrNull() ?: 0
                val body=CharArray(length);var offset=0
                while(offset<length) {val n=reader.read(body,offset,length-offset);if(n<0)break;offset+=n}
                if(headers.containsKey("cookie"))cookieSeen=true
                var code=200;var html=false;var extra=""
                val text=when(path) {
                    "/healthz" -> {
                        healthCalls.incrementAndGet()
                        if(headers.containsKey("authorization"))healthKeySeen=true
                        if(healthMode=="stall") {
                            stallEntered=true
                            while(connection.getInputStream().read()!=-1) { }
                            stallDisconnected=true
                            return@use
                        }
                        when {
                            healthMode=="redirect" -> {code=302;extra="Location: $origin/escape\r\n";""}
                            healthMode=="html" -> {html=true;"<html>Wrong service</html>"}
                            healthMode=="wrong-service" -> """{"service":"other","protocol":2,"status":"running"}"""
                            healthMode=="sleep" || healthFailures.getAndUpdate {if(it>0)it-1 else 0}>0 -> {code=503;html=true;"<html>Starting</html>"}
                            else -> """{"service":"flux-resolver","protocol":1,"status":"running"}"""
                        }
                    }
                    "/v1/status" -> {
                        statusCalls.incrementAndGet();check(headers["authorization"]=="Bearer $token")
                        code=apiCode;html=apiHtml
                        if(html)"<html>Access denied</html>" else """{"service":"flux-resolver","protocol":1,"authentication":"no-instagram-login"}"""
                    }
                    "/v1/resolve" -> {check(headers["authorization"]=="Bearer $token");code=sourceCode;JSONObject().put("error","source_unavailable").toString()}
                    else -> {escapeCalls.incrementAndGet();code=404;"{}"}
                }
                val data=text.toByteArray()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $code Reply\r\nContent-Type: ${if(html)"text/html" else "application/json"}\r\nContent-Length: ${data.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                    write(data);flush()
                }
            }}}}catch(_:Exception) { }
        }}
        override fun close() {running=false;socket.close();pool.shutdownNow()}
    }
}
