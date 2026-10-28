package com.flux.downloader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Calendar

object QueueSchedules {
    fun eligible(job:Download)=!job.isLocalEdit && job.state in setOf("Queued","Retrying","Paused")
    fun apply(context:android.content.Context,ids:Set<String>,time:Long):Int {
        val count=Store.schedule(ids,time)
        if(count>0)Downloads.kick(context)
        return count
    }
}

@Composable
fun QueueScheduleDialog(jobs:List<Download>,initial:Set<String>,onDone:()->Unit) {
    val context=LocalContext.current
    val eligible=jobs.filter(QueueSchedules::eligible)
    var selected by remember {mutableStateOf(initial.intersect(eligible.map {it.id}.toSet()))}
    var proposed by remember {mutableStateOf<Long?>(null)}
    var message by remember {mutableStateOf("")}
    LaunchedEffect(eligible.map {it.id}) {selected=selected.intersect(eligible.map {it.id}.toSet())}
    fun propose(time:Long) {
        if(selected.isEmpty())message="Select downloads first."
        else if(time!=0L && time<=System.currentTimeMillis())message="Choose a future start time."
        else {proposed=time;message=""}
    }
    AlertDialog(onDismissRequest=onDone,title={Text("Manage download schedules")},text={
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("${selected.size} selected · queued and paused downloads only. Active transfers and local edits keep their settings.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={selected=LibraryResultSelection.toggle(eligible.map {it.id}.toSet(),selected)}) {Text(if(LibraryResultSelection.unselect(eligible.map {it.id}.toSet(),selected))"Unselect downloads" else "Select downloads")}
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=200.dp)) {
                items(eligible,key={it.id}) {job->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=job.id in selected,onCheckedChange={checked->
                            if(!checked)selected=selected-job.id else if(selected.size<100)selected=selected+job.id else message="Select up to 100 downloads."
                        },modifier=Modifier.semantics{contentDescription="Schedule ${job.title}"})
                        Column(Modifier.weight(1f)) {Text(job.title,maxLines=1);Text(if(job.scheduledAt>0)scheduleDescription(job.scheduledAt) else "No schedule",style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
            TextButton(enabled=selected.isNotEmpty(),onClick={
                val time=Calendar.getInstance().apply{timeInMillis=jobs.firstOrNull {it.id in selected}?.scheduledAt?.takeIf{it>System.currentTimeMillis()}?:System.currentTimeMillis()+3600000}
                DatePickerDialog(context,{_,year,month,day->time.set(year,month,day)
                    TimePickerDialog(context,{_,hour,minute->time.set(Calendar.HOUR_OF_DAY,hour);time.set(Calendar.MINUTE,minute);time.set(Calendar.SECOND,0);time.set(Calendar.MILLISECOND,0);propose(time.timeInMillis)},time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(context)).show()
                },time.get(Calendar.YEAR),time.get(Calendar.MONTH),time.get(Calendar.DAY_OF_MONTH)).show()
            }){Text("Choose start date and time")}
            TextButton(enabled=selected.isNotEmpty(),onClick={propose(System.currentTimeMillis()+3600000)}){Text("In 1 hour")}
            TextButton(enabled=selected.isNotEmpty(),onClick={propose(0)}){Text("Remove schedules")}
            if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick=onDone){Text("Close schedules")}})
    proposed?.let {time->
        val ids=selected.toSet()
        AlertDialog(onDismissRequest={proposed=null},title={Text("Review schedule changes")},text={Column {
            Text("${ids.size} selected downloads\n${if(time==0L)"Remove scheduled start times" else "Start after: ${scheduleDescription(time)}"}")
            Text("Paused items stay paused. Removing a schedule does not cancel a download; queued items may start when network and power rules allow. Items that have already started are skipped.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={
            runCatching {QueueSchedules.apply(context,ids,time)}.onSuccess {count->message="$count schedules updated · ${ids.size-count} started or unavailable items kept";android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_LONG).show();proposed=null;onDone()}.onFailure {message=it.message?:"Could not update schedules.";proposed=null}
        }){Text(if(time==0L)"Confirm remove schedules" else "Apply to selected")}},dismissButton={TextButton(onClick={proposed=null}){Text("Keep current schedules")}})
    }
}
