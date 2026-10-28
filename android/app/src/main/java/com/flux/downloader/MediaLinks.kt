package com.flux.downloader

import android.net.Uri
import java.util.Locale

enum class MediaPlatform(val label: String) { YouTube("YouTube"), Instagram("Instagram"), Threads("Threads") }
data class MediaLink(val url: String, val platform: MediaPlatform, val postId: String? = null)

object MediaLinks {
    private val youtube = setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be")
    private val instagram = setOf("instagram.com","www.instagram.com","m.instagram.com")
    private val threads = setOf("threads.com","www.threads.com","threads.net","www.threads.net")
    fun parse(input: String): MediaLink? = runCatching {
        if(input.length > 2048 || input.any { it.isISOControl() }) return null
        val uri=Uri.parse(input)
        if(!uri.scheme.equals("https",true) || !uri.userInfo.isNullOrEmpty() || uri.port !in setOf(-1,443))return null
        val host=uri.host?.lowercase(Locale.ROOT) ?: return null
        if(host in youtube)return MediaLink(uri.buildUpon().scheme("https").encodedAuthority(host+if(uri.port==443)":443" else "").build().toString(),MediaPlatform.YouTube)
        if(host in threads) {
            val parts=uri.pathSegments
            if(parts.size!=3 || !parts[0].matches(Regex("@[A-Za-z0-9_.]{1,30}")) || parts[1]!="post" || !parts[2].matches(Regex("[A-Za-z0-9_-]{5,80}")))return null
            return MediaLink("https://www.threads.com/${parts[0]}/post/${parts[2]}",MediaPlatform.Threads,parts[2])
        }
        if(host !in instagram)return null
        val parts=uri.pathSegments
        val offset=when {
            parts.size==2 -> 0
            parts.size==3 -> 1
            else -> return null
        }
        if(offset==1 && (parts[0] in setOf("share","stories","accounts","explore") || !parts[0].matches(Regex("[A-Za-z0-9_.]{1,30}"))))return null
        if(parts[offset] !in setOf("p","reel","reels","tv"))return null
        val id=parts[offset+1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,80}")) } ?: return null
        val route=if(parts[offset] in setOf("reel","reels"))"reel" else parts[offset]
        MediaLink("https://www.instagram.com/$route/$id/",MediaPlatform.Instagram,id)
    }.getOrNull()
    fun target(input: String): String {
        val text=input.trim()
        require(text.isNotEmpty()) { "Paste a YouTube link, public Instagram post link, public Threads post link, or enter a YouTube search." }
        val looksLikeLink=text.contains("://") || text.startsWith("www.",true) ||
            (youtube+instagram+setOf("threads.net","threads.com","www.threads.net","www.threads.com")).any { text.startsWith("$it/",true) }
        if(!looksLikeLink)return "ytsearch5:$text"
        val url=if(text.contains("://"))text else "https://$text"
        return parse(url)?.url ?: throw IllegalArgumentException("Use a valid HTTPS YouTube link or a direct public Instagram Reel, photo or carousel post link. Public Threads post links are also supported. Stories, profiles and profile galleries are not supported.")
    }
    fun shared(text: String): String {
        val urls=Regex("https://[^\\s<>]+",RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd(')',']','.',',','!') }.toList()
        urls.firstNotNullOfOrNull { parse(it)?.url }?.let { return it }
        // Known but unsupported Meta links reach the validator so sharing a
        // profile/Threads link produces a useful message instead of silence.
        return urls.firstOrNull { url -> runCatching {
            val uri=Uri.parse(url)
            uri.userInfo.isNullOrEmpty() && uri.port in setOf(-1,443) &&
                uri.host?.lowercase(Locale.ROOT) in instagram+setOf("threads.net","threads.com","www.threads.net","www.threads.com")
        }.getOrDefault(false) }.orEmpty()
    }
    fun isThreads(url: String) = parse(url)?.platform == MediaPlatform.Threads
    fun isInstagram(url: String) = parse(url)?.platform == MediaPlatform.Instagram
    fun validateInstagramMetadata(json: org.json.JSONObject, url: String) {
        if(!isInstagram(url))return
        InstagramPost.parse(json,url)
    }
}
