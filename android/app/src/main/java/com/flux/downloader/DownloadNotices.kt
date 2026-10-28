package com.flux.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat

/** Animated system download drawable is reserved for active work. Terminal notices clear progress explicitly. */
object DownloadNotices {
    const val STATE="flux_notice_state"
    val activeStates=setOf("Downloading","Merging","Saving")
    fun icon(state:String,active:Boolean)=when {
        state=="Complete" -> R.drawable.ic_notice_done
        state in setOf("Paused","Pausing") -> R.drawable.ic_notice_pause
        state=="Failed" -> R.drawable.ic_notice_error
        active && state in activeStates -> android.R.drawable.stat_sys_download
        else -> R.drawable.ic_notice_wait
    }
    fun style(builder:NotificationCompat.Builder,state:String,active:Boolean,percent:Int,indeterminate:Boolean):NotificationCompat.Builder {
        val running=active && state in activeStates
        return builder.setSmallIcon(icon(state,running)).setOngoing(running).setAutoCancel(!running)
            .setCategory(if(running)NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setProgress(if(running)100 else 0,if(running)percent.coerceIn(0,100) else 0,running && indeterminate)
            .addExtras(Bundle().apply {putString(STATE,state)})
    }
    /** Repair notices left by older versions when the app is reopened. Active transfers keep their own updates. */
    fun reconcile(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        for(posted in manager.activeNotifications) {
            if(posted.notification.channelId!="downloads")continue
            val job=Store.downloads.value.firstOrNull {it.id.hashCode()==posted.id} ?: continue
            if(job.state in activeStates)continue
            if(job.state=="Cancelled") {manager.cancel(posted.id);continue}
            manager.createNotificationChannel(NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW))
            val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val detail=when(job.state) {"Complete"->"Download complete";"Paused","Pausing"->"Paused · downloaded data kept";else->job.detail}
            val builder=NotificationCompat.Builder(context,"downloads").setContentTitle(job.title).setContentText(detail)
                .setContentIntent(open).setOnlyAlertOnce(true)
            style(builder,job.state,false,0,false)
            if(job.state=="Paused") {
                val resume=PendingIntent.getBroadcast(context,0,Intent(context,DownloadActionReceiver::class.java)
                    .setAction("com.flux.downloader.RESUME").setData(Uri.parse("flux://download/${job.id}/RESUME")).putExtra("job",job.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_play,"Resume",resume)
            }
            addCompletedActions(context,builder,job)
            manager.notify(posted.id,builder.build())
        }
    }
    fun addCompletedActions(context:Context,builder:NotificationCompat.Builder,job:Download) {
        if(job.state!="Complete" || !job.uri.startsWith("content://"))return
        runCatching {
        val uri=Uri.parse(job.uri)
        val clip=ClipData.newRawUri("Saved file",uri)
        val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,job.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=clip}
        val send=Intent(Intent.ACTION_SEND).setType(job.mime).putExtra(Intent.EXTRA_STREAM,uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=clip}
        val share=Intent.createChooser(send,"Share download").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=clip}
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if(view.resolveActivity(context.packageManager)!=null)builder.addAction(android.R.drawable.ic_media_play,"Open",PendingIntent.getActivity(context,job.id.hashCode(),view,flags))
        builder.addAction(android.R.drawable.ic_menu_share,"Share",PendingIntent.getActivity(context,job.id.hashCode(),share,flags))
        }
    }
}
