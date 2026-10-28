package com.flux.downloader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Immutable source-format preferences. Queued jobs retain their resolved stream selectors. */
data class NamedPreset(val id:String=UUID.randomUUID().toString(),val name:String,val height:Int=0,
    val audioOnly:Boolean=false,val extension:String="",val language:String="") {
    fun validated():NamedPreset {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {"Invalid preset identity."}
        require(name==name.trim() && name.length in 1..40 && name.none {it.isISOControl()}) {"Use a preset name of 1–40 characters."}
        require(height in heights && AudioLanguages.valid(language)) {"Unsupported resolution or language."}
        require(extension in (if(audioOnly)setOf("","m4a","webm") else setOf("","mp4","webm"))) {"Choose a supported source format."}
        return this
    }
    fun choose(video:Video):MediaFormat?=choose(video,height,audioOnly,extension,language)
    fun summary()="${if(audioOnly)"Audio only" else if(height==0)"Best available" else "Up to ${height}p"} · ${extension.uppercase().ifBlank {"Any source format"}} · ${AudioLanguages.label(language)}"
    fun json()=JSONObject().put("id",id).put("name",name).put("height",height).put("audioOnly",audioOnly).put("extension",extension).put("language",language)
    companion object {
        val heights=listOf(0,360,480,720,1080,1440,2160,4320)
        fun from(o:JSONObject):NamedPreset {
            require(o.keys().asSequence().all {it in setOf("id","name","height","audioOnly","extension","language")}) {"Preset contains unsupported fields."}
            return NamedPreset(o.getString("id"),o.getString("name"),o.getInt("height"),o.getBoolean("audioOnly"),o.getString("extension"),o.getString("language")).validated()
        }
        fun choose(video:Video,height:Int,audioOnly:Boolean,extension:String,language:String):MediaFormat? {
            if(video.isPhoto)return null
            val eligible=video.formats.filter {format ->
                (extension.isBlank() || format.extension==extension) &&
                    if(audioOnly)format.audio && !format.video else format.video &&
                        (height==0 || (if(format.width>0 && format.height>format.width)format.width else format.height) in 1..height)
            }
            if(audioOnly)return AudioSelection.preferred(video.copy(formats=eligible),language)
                ?:eligible.asReversed().sortedBy {it.isDrc}.firstOrNull()
            return eligible.sortedWith(compareByDescending<MediaFormat>{it.height}.thenByDescending{it.fps}.thenBy{it.extension}).firstOrNull()
        }
    }
}

object NamedPresets {
    const val MAX=20
    private lateinit var prefs:android.content.SharedPreferences
    val items=MutableStateFlow<List<NamedPreset>>(emptyList())
    val default=MutableStateFlow<NamedPreset?>(null)
    val error=MutableStateFlow("")
    fun decode(text:String):List<NamedPreset> {
        require(text.toByteArray().size<=32768) {"Saved presets are too large."}
        val array=JSONArray(text);require(array.length()<=MAX) {"Save up to 20 presets."}
        val list=(0 until array.length()).map {NamedPreset.from(array.getJSONObject(it))}
        require(list.map {it.id}.distinct().size==list.size && list.map {it.name.lowercase(Locale.ROOT)}.distinct().size==list.size) {"Preset names and identities must be unique."}
        return list
    }
    fun init(context:Context) {
        if(::prefs.isInitialized)return
        prefs=context.getSharedPreferences("flux_named_presets",0)
        runCatching {items.value=decode(prefs.getString("items","[]").orEmpty());default.value=items.value.firstOrNull {it.id==prefs.getString("default_id","")}}
            .onFailure {error.value="Saved presets could not be read. Restore a valid preference backup or save your presets again."}
    }
    @Synchronized fun save(preset:NamedPreset) {
        preset.validated()
        val next=items.value.filterNot {it.id==preset.id}+preset
        replace(next,prefs.getString("default_id","").orEmpty())
    }
    @Synchronized fun remove(id:String) {replace(items.value.filterNot {it.id==id},if(default.value?.id==id)"" else default.value?.id.orEmpty())}
    @Synchronized fun setDefault(id:String) {
        if(!::prefs.isInitialized) {require(id.isEmpty());return}
        require(id.isEmpty() || items.value.any {it.id==id}) {"This preset no longer exists."}
        check(prefs.edit().putString("default_id",id).commit()) {"Could not save the default preset."}
        default.value=items.value.firstOrNull {it.id==id}
    }
    @Synchronized fun replace(list:List<NamedPreset>,defaultId:String) {
        val json=JSONArray().apply {list.forEach {put(it.validated().json())}}.toString()
        val validated=decode(json)
        require(defaultId.isEmpty() || validated.any {it.id==defaultId}) {"The default named preset is missing."}
        check(prefs.edit().putString("items",json).putString("default_id",defaultId).commit()) {"Could not save presets."}
        items.value=validated;default.value=validated.firstOrNull {it.id==defaultId};error.value=""
    }
}

data class BatchPreset(val stock:DownloadPreset=DownloadPreset.Best,val named:NamedPreset?=null,val language:String=UpgradeSettings.preferences.value.language) {
    val label get()=named?.name ?: stock.label
    fun prepare(video:Video,folder:String,startAt:Long):Download {
        val format=named?.choose(video) ?: if(named==null)NamedPreset.choose(video,stock.height,stock.audioOnly,"",language) else null
        require(format!=null) {"No source format matches $label for this item."}
        val audio=AudioSelection.preferred(video,named?.language ?: language)
        return MediaDownloads.prepare(video,folder,format,audio,honourPreference=false).copy(scheduledAt=startAt)
    }
}

object PlaylistBatch {
    fun range(items:List<Video>,start:String,end:String):Set<String> {
        val first=start.toIntOrNull()?:error("Enter a start item number.")
        val last=end.toIntOrNull()?:error("Enter an end item number.")
        require(first in 1..items.size && last in first..items.size) {"Choose a range from 1 to ${items.size}."}
        return items.subList(first-1,last).map {it.key}.toSet()
    }
    suspend fun saved(context:Context,plan:Download,jobs:List<Download>):Boolean=withContext(Dispatchers.IO) {
        val source=RepeatDownloads.sourceKey(plan.url) ?: return@withContext false
        jobs.any {old->
            currentCoroutineContext().ensureActive()
            old.state=="Complete" && old.audioOnly==plan.audioOnly && old.mediaKind==plan.mediaKind &&
            old.selector==plan.selector && old.mediaId==plan.mediaId && old.replaceEmbeddedAudio==plan.replaceEmbeddedAudio &&
            RepeatDownloads.sourceKey(old.url)==source && LibraryDetails.inspect(context,old).available}
    }
}
