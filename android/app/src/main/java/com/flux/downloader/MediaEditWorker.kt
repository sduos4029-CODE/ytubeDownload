package com.flux.downloader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File

class MediaEditWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=MediaEdits.execution.withLock {
        Store.init(applicationContext)
        Store.downloads.value.filter {it.isLocalEdit && it.state in QueueRules.busyStates}.forEach {job ->
            Store.change(job.id,true){it.copy(state=if(it.state=="Pausing")"Paused" else "Queued",detail="Interrupted edit · original kept; export restarts")}
        }
        while(!Store.queuePaused.value) {
            currentCoroutineContext().ensureActive()
            val job=Store.downloads.value.filter {it.isLocalEdit && it.state=="Queued"}.minByOrNull {it.queueOrder}?:break
            setForeground(foreground(job.id,job.title))
            process(job)
        }
        Result.success()
    }
    override suspend fun getForegroundInfo()=foreground("", "Local export")
    private fun foreground(id:String,title:String):ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("media-tools","Media tools",NotificationManager.IMPORTANCE_LOW))
        val cancel=PendingIntent.getBroadcast(applicationContext,id.hashCode(),Intent(applicationContext,DownloadActionReceiver::class.java)
            .setAction("com.flux.downloader.CANCEL").setData(Uri.parse("flux://download/$id/local-cancel")).putExtra("job",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(applicationContext,"media-tools").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Flux media tools")
            .setContentText(title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(0,0,true).addAction(android.R.drawable.ic_delete,"Cancel",cancel).build()
        return if(Build.VERSION.SDK_INT>=29)ForegroundInfo(710024,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(710024,notice)
    }
    private class Stopped:Exception()
    private suspend fun control(id:String) {
        currentCoroutineContext().ensureActive()
        if(isStopped)throw CancellationException("Local export interrupted")
        if(Store.downloads.value.find {it.id==id}?.state in setOf("Pausing","Paused","Cancelled"))throw Stopped()
    }
    private suspend fun process(job:Download)=withContext(Dispatchers.IO) {
        val work=Downloads.workDir(applicationContext,job.id)
        Store.workStarted(job)
        fun update(state:String,percent:Float,detail:String) {
            Store.change(job.id){if(it.state in setOf("Pausing","Paused","Cancelled","Complete"))it else it.copy(state=state,progress=percent,detail=detail)}
        }
        try {
            control(job.id)
            Downloads.cleanupPending(applicationContext,job)
            require(job.editSourceUri.startsWith("content://")){"This saved source cannot be opened securely."}
            Engine.initialize(applicationContext)
            val preset=EditPreset.valueOf(job.editPreset)
            require(preset.encoders.all {it in NativeMedia.encoders(applicationContext)}){"This output's encoder is not available on this device. Choose another format."}
            StorageBudget.check(applicationContext,job.expectedBytes)
            check(work.isDirectory || work.mkdirs())
            work.listFiles().orEmpty().forEach {it.delete()}
            val input=File(work,"input.media");val output=File(work,"export.${preset.extension}")
            update("Editing",0f,"Copying saved source · original file stays unchanged")
            applicationContext.contentResolver.openInputStream(Uri.parse(job.editSourceUri))?.use {source ->input.outputStream().use {target ->
                val buffer=ByteArray(256*1024);var copied=0L
                while(true){control(job.id);val count=source.read(buffer);if(count<0)break;target.write(buffer,0,count);copied+=count
                    if(copied%(16L*1024*1024)==0L)StorageBudget.check(applicationContext,0)
                }
            }}?:error("Saved source unavailable. Restore folder access before retrying.")
            input.setReadable(false,false);input.setReadable(true,true);input.setWritable(false,false);input.setWritable(true,true)
            require(input.length()>0){"The source file is empty."}
            control(job.id)
            update("Editing",0f,if(preset.copies)"Copying original streams · progress may be unavailable" else "Encoding ${preset.label} · ${EditQuality.valueOf(job.editQuality).label}")
            var last=0L
            NativeMedia.run(applicationContext,MediaEdits.arguments(job,input,output),{control(job.id)}) {line ->
                if(line.startsWith("out_time=")) {
                    val time=runCatching {MediaEdits.seconds(line.substringAfter('=').let {value ->if(value.contains('.'))value.substringBefore('.')+"."+value.substringAfter('.').take(3) else value})}.getOrNull()
                    val now=System.currentTimeMillis()
                    if(time!=null && now-last>500){last=now
                        val progress=if(job.duration>0)(time/job.duration*100).toFloat().coerceIn(0f,99f) else 0f
                        update("Editing",progress,if(job.duration>0)"Processed ${durationLabel(time.toLong())} / about ${durationLabel(job.duration)}" else "Processing · total duration unavailable")
                    }
                }
            }
            control(job.id);require(output.isFile && output.length()>0){"No edited media file was produced."}
            update("Verifying",0f,"Checking exported tracks and samples")
            MediaVerification.inspect(applicationContext,Uri.fromFile(output),job)
            val result=publish(output,job,preset,::update)
            control(job.id)
            check(Store.finishDownload(job.id,result.toString(),preset.mime,output.length(),if(preset.copies)"Original source streams copied" else "${preset.label} · ${EditQuality.valueOf(job.editQuality).label}")){"Export was stopped before completion."}
        }catch(e:CancellationException) {
            settle(job.id);throw e
        }catch(e:Exception) {
            if(e is Stopped || Store.downloads.value.find{it.id==job.id}?.state in setOf("Pausing","Paused","Cancelled"))settle(job.id)
            else Store.change(job.id,true){it.copy(state="Failed",detail=e.message?.take(300)?:"Local export failed. Original file kept.")}
        }finally {
            withContext(NonCancellable) {
                val current=Store.downloads.value.find {it.id==job.id}
                if(current!=null && current.state!="Complete" && current.pendingUri.isNotBlank())runCatching {Downloads.cleanupPending(applicationContext,current)}.onFailure {
                    Store.change(job.id,true){it.copy(detail="Restore destination folder access to remove an unfinished export.")}
                }
                work.deleteRecursively();Store.workFinished(job.id)
                if(Store.downloads.value.find{it.id==job.id}?.state=="Paused")Store.change(job.id,true){it.copy(detail="Paused · original kept; export restarts when resumed")}
            }
        }
    }
    private fun settle(id:String) {
        Store.change(id,true){when(it.state){"Cancelled","Complete"->it;"Pausing","Paused"->it.copy(state="Paused",detail="Paused · original kept; export restarts when resumed");else->it.copy(state="Queued",detail="Interrupted · original kept; export restarts")}}
    }
    private suspend fun publish(file:File,job:Download,preset:EditPreset,update:(String,Float,String)->Unit):Uri {
        control(job.id);update("Saving",0f,"Saving a new file · original kept")
        val name="${LibraryOrganisation.clean(job.title,100)} ${job.id.take(8)}.${preset.extension}"
        var pending=false
        val target=when {
            job.folder.isNotBlank()-> {
                val folder=DocumentFile.fromTreeUri(applicationContext,Uri.parse(job.folder))?:error("Choose the destination folder again.")
                require(folder.canWrite()){"Restore destination folder access."}
                folder.createFile(preset.mime,name)?.uri?:error("Unable to create a new export.")
            }
            Build.VERSION.SDK_INT>=29 -> {
                pending=true
                applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,preset.mime);put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Flux");put(MediaStore.MediaColumns.IS_PENDING,1)
                })?:error("Unable to create export. Check destination storage.")
            }
            else -> {
                val folder=File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"Flux").apply{mkdirs()}
                FileProvider.getUriForFile(applicationContext,"${applicationContext.packageName}.files",File(folder,name))
            }
        }
        require(target!=Uri.parse(job.editSourceUri)){"The destination provider reused the source file. Export was not written."}
        Store.change(job.id,true){it.copy(pendingUri=target.toString())}
        file.inputStream().use {input->applicationContext.contentResolver.openOutputStream(target,"w")?.use {output->
            val buffer=ByteArray(256*1024);var bytes=0L;var last=0L
            while(true){control(job.id);val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);bytes+=count
                if(System.currentTimeMillis()-last>500){last=System.currentTimeMillis();update("Saving",bytes.toFloat()/file.length()*100,"${sizeLabel(bytes)} / ${sizeLabel(file.length())}")}
            }
        }?:error("Unable to write the new export.")}
        control(job.id)
        val verified=MediaVerification.inspect(applicationContext,target,job)
        Store.change(job.id,true){it.copy(verification=verified)}
        if(pending && Build.VERSION.SDK_INT>=29)applicationContext.contentResolver.update(target,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        return target
    }
}
