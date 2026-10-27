package com.flux.downloader

import org.json.JSONObject

object InstagramPost {
    const val MAX_ITEMS = 20
    fun parse(json: JSONObject, url: String): List<Video> {
        require(MediaLinks.isInstagram(url)) { "Use a public Instagram post link." }
        val entries = json.optJSONArray("entries")
        val nodes = if(entries == null) listOf(json) else {
            require(entries.length() in 1..MAX_ITEMS) { "This post must contain between 1 and 20 items." }
            (0 until entries.length()).map { entries.getJSONObject(it) }
        }
        val videos = nodes.mapIndexed { index, node ->
            val video = Video.from(node,url)
            require(video.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "This item's identity was not reported. Load the post again." }
            require(video.formats.isNotEmpty() && (video.isPhoto || video.formats.any { it.video })) { "An item has no available photo/video formats. The post may require login or be unavailable." }
            require(video.mediaKind in setOf("photo","video") && video.formats.all { it.photo == video.isPhoto }) { "The source returned inconsistent photo/video information." }
            video.copy(itemIndex=index+1,itemCount=nodes.size,
                title=if(nodes.size>1) "${video.title} · Item ${index+1} of ${nodes.size}" else video.title)
        }
        require(videos.map { it.id }.distinct().size == videos.size) { "This post returned duplicate item identities. Load it again." }
        return videos
    }
}

object MediaDownloads {
    fun best(video: Video): MediaFormat = video.formats.filter { if(video.isPhoto) it.photo else it.video && !it.photo }
        .maxWithOrNull(compareBy<MediaFormat> { if(video.isPhoto) it.width.toLong()*it.height else it.height.toLong() }.thenBy { it.fps })
        ?: error("No downloadable format is available for this item.")

    fun prepare(video: Video, folder: String, format: MediaFormat = best(video), audio: MediaFormat? = null, honourPreference:Boolean=true): Download {
        require(format in video.formats) { "Load the post again to choose a current format." }
        val chosenAudio=audio ?: if(honourPreference)AudioSelection.preferred(video) else null
        val plan = if(video.isPhoto) AudioSelection.Plan(format.id,false,"") else AudioSelection.plan(video,format,chosenAudio)
        return Download(url=video.url,title=video.title,thumbnail=video.thumbnail,selector=plan.selector,
            label=format.label,audioOnly=!video.isPhoto && !format.video,duration=video.seconds,folder=folder,
            replaceEmbeddedAudio=plan.replaceEmbeddedAudio,audioDescription=plan.description,mediaKind=video.mediaKind,
            mediaId=if(video.platform==MediaPlatform.Instagram) video.id else "",
            scheduledAt=UpgradeSettings.preferences.value.startAt,
            expectedBytes=StorageBudget.estimate(format,chosenAudio,format.video && video.formats.any {it.audio && !it.video}),
            expectsAudio=!video.isPhoto && (format.audio || (format.video && video.formats.any {it.audio})))
    }
}
