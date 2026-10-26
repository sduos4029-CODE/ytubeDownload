package com.flux.downloader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage

/** Login browser storage is separate from the encrypted native session. */
object InstagramBrowser {
    private fun prefs(context:Context)=context.getSharedPreferences("instagram_browser",Context.MODE_PRIVATE)
    fun opened(context:Context) {prefs(context).edit().putBoolean("needs_cleanup",true).commit()}
    fun recover(context:Context) {
        if(prefs(context).getBoolean("needs_cleanup",false))Handler(Looper.getMainLooper()).post {clear(context)}
    }
    fun clear(context:Context) {
        try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                prefs(context).edit().putBoolean("needs_cleanup",false).apply()
            }
        }catch(_:Exception) { /* Retry at next process start if WebView is unavailable. */ }
    }
}
