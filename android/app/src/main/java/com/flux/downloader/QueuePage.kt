package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun QueuePage(jobs: List<Download>) {
    val context = LocalContext.current
    val paused by Store.queuePaused.collectAsState()
    val transfers by Store.transfers.collectAsState()
    val onWifi by NetworkGate.wifi.collectAsState()
    val colors = MaterialTheme.colorScheme
    var scheduleIds by remember {mutableStateOf<Set<String>?>(null)}
    scheduleIds?.let {ids->QueueScheduleDialog(jobs,ids) {scheduleIds=null}}
    var confirmCancel by remember { mutableStateOf(false) }
    val ordered = jobs.sortedWith(compareBy<Download> { when (it.state) {
        in QueueRules.busyStates -> 0; "Queued", "Retrying" -> 1; else -> 3
    } }.then(QueueRules.order))
    val waiting = ordered.filter { it.state in QueueRules.runnableStates }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your download queue", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(if (paused) "Queue paused · your downloaded data is kept" else if (transfers.wifiOnly && !onWifi) "Waiting for Wi-Fi · downloaded data is kept" else "Up to ${transfers.parallel} at a time · ${if (transfers.wifiOnly) "Wi-Fi only" else "any connected network"}",
                color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (jobs.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val allPaused = paused || jobs.all { it.state in setOf("Paused", "Pausing") }
                FilledTonalButton(onClick = { if (allPaused) Downloads.resumeAll(context) else Downloads.pauseAll(context) },
                    enabled = jobs.none { it.state == "Pausing" }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(if (allPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                    Text(if (jobs.any { it.state == "Pausing" }) "Pausing…" else if (allPaused) "Resume all" else "Pause all", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { confirmCancel = true }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Cancel all") }
            }
            if(jobs.any(QueueSchedules::eligible))TextButton(onClick={scheduleIds=emptySet()}) {Text("Manage schedules")}
            Text("Resume depends on the source. Some streams restart; temporary network errors retry up to three times.",
                color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
            Text("Eligible High priority items start first; current transfers continue. Download next raises an item to High. Speed cap per download: ${DownloadLimits.label(transfers.rateKiB)}.",color=colors.onSurfaceVariant,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        }
        if (jobs.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 55.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.CloudDownload, null, tint = colors.primary, modifier = Modifier.size(48.dp))
                Text("Nothing waiting. Find your next video.", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
                if (paused) TextButton(onClick = { Downloads.resumeAll(context) }) { Text("Resume queue for new downloads") }
            }
        }
        items(ordered, key = { it.id }) { job ->
            Surface(color = colors.surface, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    var priorityMenu by remember(job.id){mutableStateOf(false)}
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(72.dp, 55.dp), contentAlignment = Alignment.Center) {
                            Icon(if (job.audioOnly) Icons.Rounded.Headphones else Icons.Rounded.VideoLibrary, null, tint = colors.primary)
                            if (job.thumbnail.isNotBlank()) AsyncImage(job.thumbnail, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(job.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${if (job.audioOnly) "Audio" else "Video"} · ${job.label}", color = colors.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                        IconButton(onClick = { Downloads.cancel(context, job) }) { Icon(Icons.Rounded.Close, "Cancel ${job.title}", Modifier.size(20.dp)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (job.state) {
                            "Queued" -> if(job.scheduledAt>System.currentTimeMillis()) "Scheduled · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(job.scheduledAt))}" else if(!ChargingGate.allowed()) "Waiting for charging" else if (paused) "Queue paused" else if (transfers.wifiOnly && !onWifi) "Waiting for Wi-Fi" else "Queued · #${waiting.indexOfFirst { it.id == job.id } + 1}"
                            "Pausing" -> "Pausing…"
                            "Retrying" -> "Retry ${job.retryCount}/${QueueRules.MAX_RETRIES} scheduled"
                            else -> job.state
                        }, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (job.state == "Saving") Text("${job.progress.toInt()}%", color = colors.onSurfaceVariant, fontSize = 11.sp)
                    }
                    ProgressPanel(job)
                    if(job.isLocalEdit)Text("Local export · pause/interruption restarts this edit; original stays unchanged.",fontSize=10.sp,color=colors.onSurfaceVariant)
                    Text(job.detail, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp))
                    if(job.state in QueueRules.runnableStates+setOf("Paused"))Row(verticalAlignment=Alignment.CenterVertically) {
                        Box {
                        TextButton(onClick={priorityMenu=true},modifier=Modifier.semantics{contentDescription="Priority for ${job.title}"}){Text("${listOf("Low","Normal","High")[job.priority.coerceIn(0,2)]} priority")}
                        DropdownMenu(expanded=priorityMenu,onDismissRequest={priorityMenu=false}) {
                            listOf("Low","Normal","High").forEachIndexed {value,label->DropdownMenuItem(text={Text("$label priority")},onClick={Downloads.priority(context,job,value);priorityMenu=false})}
                        }
                        }
                        if(QueueSchedules.eligible(job))TextButton(onClick={scheduleIds=setOf(job.id)},modifier=Modifier.semantics{contentDescription="Schedule download ${job.title}"}) {Text("Schedule")}
                    }
                    if (job.audioDescription.isNotBlank()) Text("Audio: ${job.audioDescription}", color = colors.secondary,
                        fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.End) {
                        if(job.state in QueueRules.runnableStates && job.scheduledAt>System.currentTimeMillis())TextButton(onClick={Downloads.startNow(context,job)}){Text("Start now")}
                        if (job.state in QueueRules.runnableStates && waiting.firstOrNull()?.id != job.id) TextButton(onClick = { Downloads.next(context, job) }) {
                            Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(17.dp)); Text("Download next", fontSize = 12.sp)
                        }
                        if (job.state == "Retrying") TextButton(onClick = { Downloads.retryNow(context, job) }) { Text("Retry now", fontSize = 12.sp) }
                        if (job.state == "Paused") TextButton(onClick = { Downloads.resume(context, job) }) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text("Resume")
                        } else if (job.state != "Pausing") TextButton(onClick = { Downloads.pause(context, job) }) {
                            Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Text("Pause")
                        }
                    }
                }
            }
        }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("Cancel all downloads?") },
        text = { Text("Partial data for unfinished downloads will be removed. Files already saved in Library stay on your device.") },
        confirmButton = { TextButton(onClick = { Downloads.cancelAll(context); confirmCancel = false }) { Text("Cancel downloads") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep downloads") } })
}
