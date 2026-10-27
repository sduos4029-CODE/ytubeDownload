package com.flux.downloader

import android.net.Uri
import org.json.JSONObject
import com.yausername.youtubedl_android.YoutubeDLRequest

object SubtitleTracks {
    fun valid(language:String)=language.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,29}"))
    fun languages(json:JSONObject):List<String> {
        val tracks=json.optJSONObject("subtitles")?:return emptyList()
        return tracks.keys().asSequence().filter(::valid).filter {language ->
            val formats=tracks.optJSONArray(language)
            formats!=null && (0 until formats.length()).any {i ->
                val f=formats.optJSONObject(i)
                val uri=runCatching {Uri.parse(f?.optString("url").orEmpty())}.getOrNull()
                f?.optString("ext") in setOf("vtt","srt") && uri?.scheme=="https" && uri.userInfo.isNullOrEmpty()
            }
        }.take(100).sorted().toList()
    }
    fun apply(request:YoutubeDLRequest,language:String) {
        if(language.isBlank())return
        require(valid(language)) {"Invalid subtitle language. Choose it again in Discover."}
        request.addOption("--write-subs");request.addOption("--no-write-auto-subs")
        request.addOption("--sub-langs",language);request.addOption("--sub-format","srt/vtt")
        request.addOption("--embed-subs");request.addOption("--remux-video","mkv")
    }
}

object PlaylistSelection {
    const val MAX_ITEMS=50
    fun isPlaylist(url:String):Boolean {
        val link=MediaLinks.parse(url)?:return false
        val uri=Uri.parse(link.url)
        return link.platform==MediaPlatform.YouTube && uri.path=="/playlist" &&
            uri.getQueryParameter("list")?.matches(Regex("[A-Za-z0-9_-]{10,100}"))==true
    }
    fun parse(json:JSONObject):List<Video> {
        val entries=json.optJSONArray("entries")?:error("No playlist entries were returned.")
        return (0 until minOf(entries.length(),MAX_ITEMS)).mapNotNull {i ->
            val entry=entries.optJSONObject(i)?:return@mapNotNull null
            val id=entry.optString("id")
            if(!id.matches(Regex("[A-Za-z0-9_-]{6,80}")))return@mapNotNull null
            if(entry.optString("availability") in setOf("private","premium_only","subscriber_only","needs_auth"))return@mapNotNull null
            Video.from(entry,"https://www.youtube.com/watch?v=$id")
        }.distinctBy {it.id}.let {items -> items.mapIndexed {i,v ->v.copy(itemIndex=i+1,itemCount=items.size)}}
    }
}
