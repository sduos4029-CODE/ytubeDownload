package com.flux.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

data class TrimClip(val start:Double,val end:Double) { val seconds get()=end-start }

/** Ordered ranges from one saved source. Only fixed local filenames enter concat input. */
object ClipEdits {
    const val MAX_CLIPS=20
    fun validate(clips:List<TrimClip>,duration:Double) {
        require(duration.isFinite() && duration>0){"Source duration is unavailable."}
        require(clips.size in 2..MAX_CLIPS){"Choose 2 to $MAX_CLIPS clips."}
        clips.forEach {require(it.start.isFinite() && it.end.isFinite() && it.start>=0 && it.end<=duration && it.seconds+1e-9>=0.1 && it.end<=604800){"Each clip needs at least 0.1 seconds within the source duration."}}
        require(clips.sumOf{it.seconds}<=604800){"The combined video exceeds seven days."}
    }
    fun encode(clips:List<TrimClip>)=JSONArray().apply {clips.forEach {put(JSONObject().put("start",it.start).put("end",it.end))}}.toString()
    fun decode(value:String):List<TrimClip> {
        require(value.length<=8192){"Clip list is too large."}
        val array=JSONArray(value);require(array.length() in 2..MAX_CLIPS)
        return (0 until array.length()).map {val item=array.getJSONObject(it);TrimClip(item.getDouble("start"),item.getDouble("end"))}
    }
    fun prepare(source:Download,info:EditSource,clips:List<TrimClip>,quality:EditQuality,folder:String):Download {
        require(info.video){"Choose a saved video for multiple clips."};validate(clips,info.seconds)
        val bytes=if(info.bytes>0)Math.multiplyExact(info.bytes,ceil((clips.sumOf{it.seconds}/info.seconds).coerceAtLeast(1.0)).toLong()) else 0L
        val first=clips.first()
        return MediaEdits.prepare(source,info,EditOperation.Trim,EditPreset.Mp4H264,quality,trimTime(first.start),trimTime(first.end),folder)
            .copy(title="${source.title} · ${clips.size} clips",duration=ceil(clips.sumOf{it.seconds}).toLong(),editSegments=encode(clips),expectedBytes=bytes,label="${clips.size} clips → one MP4")
    }
    fun segment(work:File,index:Int)=File(work,"segment-${index.toString().padStart(3,'0')}.mp4")
    fun manifest(count:Int):String {
        require(count in 2..MAX_CLIPS)
        return (0 until count).joinToString("\n",postfix="\n"){"file 'segment-${it.toString().padStart(3,'0')}.mp4'"}
    }
    fun joinArguments(manifest:File,output:File)=listOf("-hide_banner","-nostdin","-y","-loglevel","error","-protocol_whitelist","file,pipe","-format_whitelist","concat,mov","-f","concat","-safe","1","-i",manifest.absolutePath,"-map","0:v:0","-map","0:a:0?","-map_metadata","-1","-map_chapters","-1","-c","copy","-movflags","+faststart","-avoid_negative_ts","make_zero",output.absolutePath)
}
