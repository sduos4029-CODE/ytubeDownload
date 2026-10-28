package com.flux.downloader

fun audioCodecLabel(codec: String): String = when {
    codec.contains("mp4a", true) || codec.contains("aac", true) -> "AAC"
    codec.contains("opus", true) -> "Opus"
    codec.contains("vorbis", true) -> "Vorbis"
    codec.contains("mp3", true) || codec.contains("mpeg", true) -> "MP3"
    codec.isBlank() || codec in listOf("none", "null") -> "Source audio"
    else -> codec.uppercase()
}

object AudioSelection {
    // Keep yt-dlp's codec/quality/language ranking; prefer non-DRC source audio.
    // Parentheses keep the fallback inside the audio side of a video+audio pair.
    const val BEST = "bestaudio[format_id!*=drc]/bestaudio"
    fun preferred(video:Video,language:String=UpgradeSettings.preferences.value.language):MediaFormat? {
        if(language.isBlank())return null
        val target=language.replace('_','-')
        val source=video.formats.filter {it.audio && !it.video}.asReversed().sortedBy {it.isDrc}
        return source.firstOrNull {it.language.replace('_','-').equals(target,true)}
            ?:source.firstOrNull {it.language.replace('_','-').substringBefore('-').equals(target.substringBefore('-'),true)}
    }
    data class Plan(val selector: String, val replaceEmbeddedAudio: Boolean, val description: String)
    fun plan(video: Video, format: MediaFormat, selectedAudio: MediaFormat? = null): Plan {
        if (!format.video) return Plan(format.id, false, format.audioLabel)
        val separate = video.formats.filter { it.audio && !it.video }
        if (separate.isEmpty()) return Plan(format.id, false, if(format.audio) "Embedded source audio · ${format.audioLabel}"
            else if(format.audioCodec=="none") "Source reports no audio" else "Audio metadata not reported")
        require(selectedAudio == null || separate.any { it.id == selectedAudio.id }) { "Audio format is no longer available. Search again." }
        val audio = selectedAudio?.id ?: "($BEST)"
        return Plan("${format.id}+$audio", format.audio,
            selectedAudio?.audioLabel ?: "Best source audio · prefer original dynamics")
    }
}
