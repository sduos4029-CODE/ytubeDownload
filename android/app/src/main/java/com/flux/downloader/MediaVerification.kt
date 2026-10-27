package com.flux.downloader

import android.content.Context
import android.net.Uri
import android.media.MediaExtractor
import android.os.StatFs
import java.io.File

object StorageBudget {
    const val RESERVE=64L*1024*1024
    fun required(sourceBytes:Long):Long?=if(sourceBytes<=0)null else runCatching {Math.addExact(Math.multiplyExact(sourceBytes,3),RESERVE)}.getOrNull()
    fun available(context:Context)=StatFs(context.noBackupFilesDir.absolutePath).availableBytes
    fun check(context:Context,sourceBytes:Long) {
        val available=available(context)
        val required=required(sourceBytes)?:RESERVE
        require(available>=required) {"Not enough working storage. Need about ${sizeLabel(required)}; ${sizeLabel(available)} available. Free space before retrying."}
    }
    fun estimate(format:MediaFormat,audio:MediaFormat?,needsAudio:Boolean):Long {
        if(format.size<=0 || (needsAudio && (audio==null || audio.size<=0)))return 0
        return runCatching {Math.addExact(format.size,if(needsAudio)audio!!.size else 0)}.getOrDefault(0)
    }
}

/** Checks container tracks and samples, not decoder support or complete playback. */
object MediaVerification {
    fun inspect(context:Context,uri:Uri,job:Download):String {
        context.contentResolver.openAssetFileDescriptor(uri,"r")?.use {require(it.length!=0L) {"Saved file is empty."}}
        if(job.isPhoto) {
            val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            context.contentResolver.openInputStream(uri)?.use {android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
            require(bounds.outWidth>0 && bounds.outHeight>0) {"Saved photo could not be read. No completed file was published."}
            return "Photo dimensions verified"
        }
        val header=context.contentResolver.openInputStream(uri)?.use {input ->
            val bytes=ByteArray(16);var total=0
            while(total<bytes.size){val n=input.read(bytes,total,bytes.size-total);if(n<0)break;total+=n}
            bytes.copyOf(total)
        }?:error("Saved media cannot be read.")
        val text=header.toString(Charsets.ISO_8859_1)
        val atom=if(header.size>=8)text.substring(4,8) else ""
        val recognized=atom in setOf("ftyp","moov","mdat","wide","free") ||
            header.take(4).map {it.toInt() and 255}==listOf(0x1a,0x45,0xdf,0xa3) ||
            text.startsWith("OggS") || text.startsWith("fLaC") || text.startsWith("ID3") ||
            (text.startsWith("RIFF") && text.contains("WAVE")) ||
            (header.size>=2 && header[0].toInt() and 255==255 && header[1].toInt() and 224==224)
        require(recognized) {"Saved file has an invalid media header. No completed file was published."}
        val extractor=MediaExtractor()
        try {
            try {extractor.setDataSource(context,uri,null)} catch(_:Exception) {return "Unverified · Android cannot inspect this container"}
            var video=false;var audio=false
            for(i in 0 until extractor.trackCount) {
                val mime=extractor.getTrackFormat(i).getString("mime").orEmpty()
                if(!mime.startsWith("video/") && !mime.startsWith("audio/"))continue
                extractor.selectTrack(i)
                val sample=if(android.os.Build.VERSION.SDK_INT>=28)extractor.sampleSize else if(extractor.sampleTime>=0)1L else -1L
                extractor.unselectTrack(i)
                require(sample>0) {"Saved media contains an empty track. Retry another format."}
                video=video || mime.startsWith("video/");audio=audio || mime.startsWith("audio/")
            }
            require(if(job.audioOnly)audio else video) {"Saved media is missing its selected ${if(job.audioOnly)"audio" else "video"} track."}
            require(!job.expectsAudio || audio) {"Saved video is missing its expected audio. Retry another format."}
            return if(video && audio)"Video + audio tracks and samples verified" else if(audio)"Audio track and sample verified" else "Video track and sample verified · source has no expected audio"
        } finally {extractor.release()}
    }
}
