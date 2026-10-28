package com.flux.downloader

import android.app.Instrumentation
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONObject

class V3Checks(private val instrumentation:Instrumentation,private val report:(String)->Unit) {
    private val app get()=instrumentation.targetContext
    companion object {
        fun video(id:String="v3Fixture01",title:String="V3 single")=Video.from(JSONObject("""{"id":"$id","title":"$title","duration":6,"formats":[
            {"format_id":"720","ext":"mp4","width":1280,"height":720,"vcodec":"avc1","acodec":"aac"},
            {"format_id":"1080","ext":"mp4","width":1920,"height":1080,"vcodec":"avc1","acodec":"aac"},
            {"format_id":"2160","ext":"webm","width":3840,"height":2160,"vcodec":"vp9","acodec":"none"},
            {"format_id":"portrait","ext":"mp4","width":720,"height":1280,"vcodec":"avc1","acodec":"aac"},
            {"format_id":"unknown","ext":"mp4","vcodec":"avc1","acodec":"aac"},
            {"format_id":"a-en","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","language":"en","abr":192},
            {"format_id":"a-hi","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","language":"hi","abr":128}
        ]}"""),"https://www.youtube.com/watch?v=$id")
    }
    suspend fun run(restart:Boolean=false) {
        Store.init(app)
        if(restart) {
            check(NamedPresets.default.value?.id=="v3-native-preset")
            check(NamedPresets.items.value.first {it.id=="v3-native-preset"}.name=="Native Hindi")
            val paused=Store.downloads.value.first {it.id=="v3-paused"}
            check(paused.state=="Paused" && paused.scheduledAt>System.currentTimeMillis())
            NamedPresets.remove("v3-native-preset");DownloadPresets.set(DownloadPreset.Best)
            Store.change(paused.id,true){it.copy(state="Cancelled")};Store.removeHistory(setOf(paused.id))
            report("PASS: named preset/default and paused per-download schedule survive force-stop; cleanup preserves other preferences")
            return
        }
        WorkManager.getInstance(app).cancelAllWork().result.get();withTimeout(60000){Downloads.awaitIdle()}
        Store.setQueuePaused(true)
        val source=video()
        val audio=NamedPreset(id="v3-native-preset",name="Native Hindi",audioOnly=true,extension="m4a",language="hi")
        NamedPresets.save(audio);NamedPresets.setDefault(audio.id)
        check(audio.choose(source)?.id=="a-hi")
        val capped=NamedPreset(name="720 MP4",height=720,extension="mp4",language="hi")
        check(capped.choose(source)?.id=="portrait")
        check(capped.copy(extension="webm").choose(source)==null)
        check(capped.copy(height=0,extension="webm").choose(source)?.id=="2160")
        check(capped.copy(height=360).choose(source)==null)
        check(runCatching{audio.copy(name=" ").validated()}.isFailure)
        check(runCatching{audio.copy(extension="exe").validated()}.isFailure)
        check(runCatching{NamedPresets.save(audio.copy(id="v3-duplicate",name="native hindi"))}.isFailure)
        val items=(1..3).map {video("v3Batch00$it","V3 batch $it").copy(itemIndex=it,itemCount=3)}
        check(PlaylistBatch.range(items,"2","3")==setOf(items[1].key,items[2].key))
        check(runCatching{PlaylistBatch.range(items,"0","3")}.isFailure)
        check(runCatching{PlaylistBatch.range(items,"3","2")}.isFailure)
        val plan=BatchPreset(named=audio).prepare(items[1],"",0)
        check(plan.audioOnly && plan.selector=="a-hi")
        NamedPresets.save(audio.copy(name="Changed named preset",language="en"))
        check(plan.selector=="a-hi" && BatchPreset(named=audio).prepare(items[1],"",0).selector==plan.selector)
        NamedPresets.save(audio)
        val file=LibraryChecks(instrumentation,report).fixture("v3-saved-file")
        val saved=plan.copy(id="v3-saved",state="Complete",uri=file.uri,mime=file.mime,bytes=file.bytes)
        check(PlaylistBatch.saved(app,plan,listOf(saved)))
        check(!PlaylistBatch.saved(app,plan.copy(selector="a-en"),listOf(saved)))
        check(!PlaylistBatch.saved(app,plan,listOf(saved.copy(uri="content://missing.invalid/no-file"))))
        val snapshot=UpgradeSettings.backup();val parsed=UpgradeSettings.inspectBackup(snapshot)
        check(parsed.named?.first?.any{it.id==audio.id}==true && parsed.named?.second==audio.id)
        val legacy=JSONObject(snapshot).apply{remove("namedPresets")}.toString()
        check(UpgradeSettings.inspectBackup(legacy).named==null)
        val malicious=JSONObject(snapshot).apply{getJSONObject("namedPresets").put("cookies","not allowed")}
        check(runCatching{UpgradeSettings.inspectBackup(malicious.toString())}.isFailure)
        NamedPresets.remove(audio.id);UpgradeSettings.restore(app,snapshot)
        check(NamedPresets.default.value?.id==audio.id)
        report("PASS: named preset source-format/portrait/language constraints, immutable batch choice, playlist ranges, readable matching saved-file skip and validated named preference backup")
        val now=System.currentTimeMillis();val time=now+7200000
        val base=plan.copy(id="v3-queued",state="Queued",scheduledAt=0)
        val jobs=listOf(base,base.copy(id="v3-paused",state="Paused"),base.copy(id="v3-retry",state="Retrying",nextAttemptAt=time+10000),
            base.copy(id="v3-active",state="Downloading"),base.copy(id="v3-complete",state="Complete"),base.copy(id="v3-local",editOperation="Trim"))
        jobs.forEach(Store::add)
        check(Store.schedule(jobs.map {it.id}.toSet(),time,now)==3)
        check(Store.downloads.value.first{it.id=="v3-active"}.scheduledAt==0L && Store.downloads.value.first{it.id=="v3-local"}.scheduledAt==0L)
        val paused=Store.downloads.value.first{it.id=="v3-paused"};check(paused.state=="Paused" && paused.scheduledAt==time)
        check(Download.from(paused.json()).scheduledAt==time)
        check(QueueRules.next(Store.downloads.value.filter {it.id in setOf("v3-queued","v3-retry")},now)==null)
        check(Store.schedule(setOf("v3-queued"),0,now)==1)
        check(Store.downloads.value.first{it.id=="v3-queued"}.scheduledAt==0L)
        check(Store.downloads.value.first{it.id=="v3-retry"}.nextAttemptAt==time+10000)
        check(runCatching {Store.schedule(setOf("v3-paused"),now-1,now)}.isFailure)
        jobs.filter {it.id!="v3-paused"}.forEach {job->Store.change(job.id,true){it.copy(state="Cancelled")}}
        Store.removeHistory(jobs.filter {it.id!="v3-paused"}.map{it.id}.toSet())
        report("PASS: bulk schedules change only eligible queued/retrying/paused downloads, preserve pause/retry rules and skip active/local/complete items")
    }
}
