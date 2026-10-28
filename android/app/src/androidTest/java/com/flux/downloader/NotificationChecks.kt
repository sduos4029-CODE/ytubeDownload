package com.flux.downloader

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.graphics.drawable.AnimationDrawable
import androidx.core.app.NotificationCompat

class NotificationChecks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    fun styles() {
        for(state in listOf("Complete","Paused","Pausing","Failed","Retrying","Queued","Cancelled")) {
            // Even a late active update cannot re-enable animation/progress for a stopped state.
            val notice=DownloadNotices.style(NotificationCompat.Builder(app,"downloads"),state,true,68,true).build()
            check(notice.smallIcon.resId!=android.R.drawable.stat_sys_download)
            check(app.getDrawable(notice.smallIcon.resId) !is AnimationDrawable)
            check(notice.flags and Notification.FLAG_ONGOING_EVENT==0)
            check(notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX)==0)
            check(!notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        }
        for(state in DownloadNotices.activeStates) {
            val notice=DownloadNotices.style(NotificationCompat.Builder(app,"downloads"),state,true,68,false).build()
            check(notice.smallIcon.resId==android.R.drawable.stat_sys_download)
            check(notice.flags and Notification.FLAG_ONGOING_EVENT!=0 && notice.extras.getInt(Notification.EXTRA_PROGRESS)==68)
        }
        report("PASS: notification states reserve animation and progress for active transfers; completed, paused, failed, waiting and cancelled states are static")
    }
    fun legacy(job:Download) {
        val manager=app.getSystemService(NotificationManager::class.java)
        manager.notify(job.id.hashCode(),NotificationCompat.Builder(app,"downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.title).setOngoing(true).setProgress(0,0,true).build())
        DownloadNotices.reconcile(app)
        posted(job,"Complete")
        report("PASS: reopening repairs a legacy completed notification that retained the animated icon and indeterminate progress")
    }
    fun posted(job:Download,state:String) {
        val notification=app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {it.id==job.id.hashCode()}?.notification
            ?: error("No $state notification was posted")
        check(notification.extras.getString(DownloadNotices.STATE)==state)
        check(notification.smallIcon.resId==DownloadNotices.icon(state,false))
        check(notification.flags and Notification.FLAG_ONGOING_EVENT==0)
        check(notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)==0 && !notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        if(state=="Complete")check(notification.actions.any {it.title=="Share"})
        report("PASS: actual native download $state notification has a static icon, no ongoing flag and no progress bar")
    }
}
