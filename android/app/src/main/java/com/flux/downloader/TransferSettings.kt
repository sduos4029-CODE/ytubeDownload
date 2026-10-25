package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

@Composable
fun TransferSettingsCard() {
    val prefs by Store.transfers.collectAsState()
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Icon(Icons.Rounded.Tune, null, tint = colors.primary)
            Text("Download controls", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text("Simultaneous downloads", fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..3).forEach { count -> FilterChip(selected = prefs.parallel == count,
                    onClick = { Store.setTransfers(prefs.copy(parallel = count)) }, label = { Text("$count") }) }
            }
            Text("Reducing the limit lets current items finish before more start. One download uses less storage and processing at a time.",
                color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", fontSize = 14.sp)
                    Text("Wait for active Wi-Fi; keep partial data when it disconnects.", color = colors.onSurfaceVariant,
                        fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(end = 12.dp, top = 5.dp))
                }
                Switch(modifier = Modifier.semantics { contentDescription = "Use Wi-Fi only" }, checked = prefs.wifiOnly, onCheckedChange = { Store.setTransfers(prefs.copy(wifiOnly = it)) })
            }
            Text("Wi-Fi can also be metered. Check your network's data plan.", color = colors.onSurfaceVariant,
                fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
