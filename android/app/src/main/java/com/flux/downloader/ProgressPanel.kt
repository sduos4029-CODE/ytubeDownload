package com.flux.downloader

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ProgressPanel(job: Download) {
    if(job.isLocalEdit) {
        Text("Local media · original file kept",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp))
        if(job.state=="Saving" || (job.state=="Editing" && job.progress>0 && job.duration>0))LinearProgressIndicator(progress={(job.progress/100).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=8.dp))
        else if(job.state in QueueRules.busyStates)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
        if(job.state=="Editing")Text("Time remaining unavailable",style=MaterialTheme.typography.bodySmall)
        return
    }
    val live by TransferProgress.live.collectAsState()
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(job.id, job.state) {
        while (job.state == "Downloading") { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val sample = live[job.id]
    val fresh = sample?.takeIf { it.fresh(maxOf(now, SystemClock.elapsedRealtime())) }
    val colors = MaterialTheme.colorScheme
    val stages = buildList {
        add("Download")
        if (!job.audioOnly && job.selector.contains('+')) add("Merge")
        add("Save")
    }
    val current = when (job.state) { "Merging" -> "Merge"; "Saving", "Verifying" -> "Save"; else -> "Download" }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        stages.forEachIndexed { index, stage ->
            val active = stage == current
            Text("${index + 1} · $stage", fontSize = 10.sp,
                color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).background(if (active) colors.primaryContainer else colors.surfaceVariant,
                    RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 7.dp))
        }
    }
    if (job.state == "Downloading") {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sample?.stream ?: "Connecting", fontSize = 12.sp, color = colors.primary)
            Text(fresh?.percent?.let { "${if (fresh.estimated) "~" else ""}${it.toInt()}%" } ?: "—",
                fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        if (fresh?.percent != null) LinearProgressIndicator(progress = { fresh.percent!! / 100 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(sample?.byteLabel ?: "Waiting for source size", color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
        Text("${fresh?.speedLabel ?: "Speed unavailable"} · ${fresh?.etaLabel ?: "Time unavailable"}",
            color = colors.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        Text(if(job.isPhoto) "Estimates apply to downloading; saving takes additional time." else "Estimates apply to this stream; merging and saving take additional time.", color = colors.onSurfaceVariant,
            fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
    } else if (job.state == "Merging" || job.state == "Pausing" || job.state == "Verifying") {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
    } else if (job.state == "Saving" || job.progress > 0) {
        LinearProgressIndicator(progress = { (job.progress / 100).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}
