package com.flux.downloader

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.security.MessageDigest

object LibraryOrganisation {
    fun clean(value:String,max:Int=60):String=value.trim().replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"),"_").take(max)
    fun tags(value:String)=value.split(',').map {clean(it,30)}.filter {it.isNotBlank()}.distinct().take(12).joinToString(", ")
    fun set(ids:Set<String>,collection:String,tags:String) {
        require(ids.isNotEmpty() && ids.size<=100)
        ids.forEach {id ->Store.change(id,true) {it.copy(collection=clean(collection),tags=LibraryOrganisation.tags(tags))}}
    }
    suspend fun duplicates(context:Context,jobs:List<Download>,progress:(Int,Int)->Unit):List<List<String>> = withContext(Dispatchers.IO) {
        val selected=jobs.filter {it.state=="Complete" && it.uri.isNotBlank()}.distinctBy {it.uri}
        require(selected.size<=100) {"Scan up to 100 saved files at once."}
        val hashes=linkedMapOf<String,MutableList<String>>()
        selected.forEachIndexed {index,job ->
            currentCoroutineContext().ensureActive()
            val hash=try {
                context.contentResolver.openInputStream(Uri.parse(job.uri))?.use {input ->
                    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(256*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)
                    }
                    digest.digest().joinToString("") {"%02x".format(it)}
                }
            } catch(e:CancellationException) {throw e} catch(_:Exception) {null}
            if(hash!=null)hashes.getOrPut(hash) {mutableListOf()}.add(job.id)
            progress(index+1,selected.size)
        }
        hashes.values.filter {it.size>1}.map {it.toList()}
    }
    data class Rename(val id:String,val previous:String,val next:String)
    suspend fun preview(context:Context,jobs:List<Download>,prefix:String):List<Rename> = withContext(Dispatchers.IO) {
        val clean=clean(prefix,70);require(clean.isNotBlank()) {"Enter a file name prefix."}
        require(jobs.size in 1..100 && jobs.all {it.state=="Complete" && it.uri.startsWith("content://")}) {"Select 1–100 completed files."}
        require(jobs.map {it.uri}.distinct().size==jobs.size) {"Two entries point to the same file. Select each file only once."}
        jobs.sortedWith(compareBy<Download> {it.created}.thenBy {it.id}).mapIndexed {i,job ->
            val details=LibraryDetails.inspect(context,job)
            require(details.available && !details.name.isNullOrBlank()) {"A selected file is unavailable or its name was not reported."}
            val extension=details.name!!.substringAfterLast('.',"")
            require(extension.matches(Regex("[A-Za-z0-9]{1,8}"))) {"A file has an unsupported extension."}
            Rename(job.id,details.name,"$clean ${"%03d".format(i+1)} ${job.id.take(6)}.$extension")
        }
    }
    suspend fun rename(context:Context,plan:List<Rename>):Pair<Int,Int> = withContext(Dispatchers.IO) {
        var success=0;var failed=0
        for(item in plan) {
            currentCoroutineContext().ensureActive()
            try {
                val job=Store.downloads.value.first {it.id==item.id}
                require(job.state=="Complete" && !Store.isWorking(job.id))
                val uri=Uri.parse(job.uri)
                val actual=LibraryDetails.inspect(context,job)
                require(actual.available && actual.name==item.previous) {"The file changed after preview."}
                val next=if(DocumentsContract.isDocumentUri(context,uri)) {
                    val document=DocumentFile.fromSingleUri(context,uri)?:error("Unavailable document")
                    check(document.renameTo(item.next));document.uri
                }else {
                    check(context.contentResolver.update(uri,ContentValues().apply {put(OpenableColumns.DISPLAY_NAME,item.next)},null,null)>0);uri
                }
                Store.change(job.id,true) {it.copy(uri=next.toString(),title=item.next.substringBeforeLast('.'))}
                success++
            }catch(e:CancellationException) {throw e} catch(_:Exception) {failed++}
        }
        success to failed
    }
}
