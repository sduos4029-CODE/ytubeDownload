package com.flux.downloader

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
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
import java.util.Locale

enum class LibraryLayout { List, Grid, Compact }
enum class LibrarySize { Small, Medium, Large }
enum class LibrarySort(val label: String) { Newest("Newest"), Oldest("Oldest"), Largest("Largest first"), Title("Title A–Z") }
data class LibraryPreferences(val layout: LibraryLayout = LibraryLayout.List, val size: LibrarySize = LibrarySize.Medium,
    val sort: LibrarySort = LibrarySort.Newest)

fun libraryJobs(jobs: List<Download>, query: String, kind: String, sort: LibrarySort): List<Download> {
    val matching = jobs.filter { it.terminal && it.title.contains(query.trim(), ignoreCase = true) &&
        (kind == "All" || (kind == "Audio" && it.audioOnly) || (kind == "Video" && !it.audioOnly)) }
    return when (sort) {
        LibrarySort.Newest -> matching.sortedByDescending { it.created }
        LibrarySort.Oldest -> matching.sortedBy { it.created }
        LibrarySort.Largest -> matching.sortedWith(compareByDescending<Download> { it.bytes }.thenByDescending { it.created })
        LibrarySort.Title -> matching.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
}

@Composable
fun LibraryPage(jobs: List<Download>, onRetry: (Download) -> Unit) {
    val prefs by Store.library.collectAsState()
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("All") }
    var sortMenu by remember { mutableStateOf(false) }
    val shown = remember(jobs, query, kind, prefs.sort) { libraryJobs(jobs, query, kind, prefs.sort) }
    val context = LocalContext.current
    LaunchedEffect(jobs.map { it.id to it.bytes }) {
        withContext(Dispatchers.IO) {
            jobs.filter { it.state == "Complete" && it.bytes == 0L && it.uri.isNotBlank() }.forEach { old ->
                val bytes = runCatching {
                    context.contentResolver.query(Uri.parse(old.uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
                    } ?: 0L
                }.getOrDefault(0L)
                if (bytes > 0) Store.change(old.id) { it.copy(bytes = bytes) }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    val columns = if (prefs.layout == LibraryLayout.Grid) GridCells.Adaptive(when (prefs.size) {
        LibrarySize.Small -> 128.dp; LibrarySize.Medium -> 176.dp; LibrarySize.Large -> 256.dp
    }) else GridCells.Fixed(1)
    LazyVerticalGrid(columns = columns, contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Your offline collection", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("${jobs.count { it.state == "Complete" }} saved · arrange it your way", color = colors.onSurfaceVariant,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 16.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search your library") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("All", "Video", "Audio").forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option) })
                    }
                    Box {
                        OutlinedButton(onClick = { sortMenu = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Rounded.Sort, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(prefs.sort.label)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                Store.setLibrary(prefs.copy(sort = option)); sortMenu = false
                            }) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryLayout.entries.forEach { layout ->
                        FilterChip(selected = prefs.layout == layout, onClick = { Store.setLibrary(prefs.copy(layout = layout)) },
                            label = { Text(layout.name) }, leadingIcon = { Icon(when (layout) {
                                LibraryLayout.List -> Icons.Rounded.ViewList; LibraryLayout.Grid -> Icons.Rounded.GridView
                                LibraryLayout.Compact -> Icons.Rounded.ViewHeadline
                            }, null, Modifier.size(17.dp)) })
                    }
                }
                if (prefs.layout != LibraryLayout.Compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Card size", color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    LibrarySize.entries.forEach { size -> FilterChip(selected = prefs.size == size,
                        onClick = { Store.setLibrary(prefs.copy(size = size)) }, label = { Text(size.name) }) }
                }
            }
        }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = colors.primary, modifier = Modifier.size(42.dp))
                Text(if (jobs.isEmpty()) "Your collection starts here." else "No matching downloads.",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 15.dp))
                Text(if (jobs.isEmpty()) "Find a video in Discover to save it." else "Try another title or filter.",
                    color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
        items(shown, key = { it.id }) { job -> LibraryCard(job, prefs, onRetry) }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LibraryCard(job: Download, prefs: LibraryPreferences, onRetry: (Download) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val compact = prefs.layout == LibraryLayout.Compact
    val grid = prefs.layout == LibraryLayout.Grid
    val small = prefs.size == LibrarySize.Small
    val large = prefs.size == LibrarySize.Large
    fun open() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.uri), job.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(context, "No compatible player, or the file was moved. Open your download folder.", Toast.LENGTH_LONG).show() }
    }
    Surface(shape = RoundedCornerShape(if (compact) 12.dp else 18.dp), color = colors.surface) {
        Column(Modifier.padding(if (compact || small) 10.dp else 14.dp)) {
            if (grid) LibraryThumbnail(job, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (grid) 11.dp else 0.dp)) {
                if (!grid) LibraryThumbnail(job, Modifier.size(when { compact -> 44.dp; small -> 60.dp; large -> 112.dp; else -> 80.dp },
                    when { compact -> 38.dp; small -> 48.dp; large -> 84.dp; else -> 60.dp }))
                Column(Modifier.weight(1f).padding(start = if (grid) 0.dp else 12.dp)) {
                    Text(job.title, maxLines = if (compact) 1 else if (large) 3 else 2, overflow = TextOverflow.Ellipsis,
                        fontSize = if (compact || small) 12.sp else if (large) 17.sp else 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${if (job.audioOnly) "Audio" else "Video"} · ${job.label}${if (job.bytes > 0) " · ${sizeLabel(job.bytes)}" else ""}",
                        color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 2,
                        modifier = Modifier.padding(top = 5.dp), overflow = TextOverflow.Ellipsis)
                    if (compact && job.state != "Complete") Text(job.state, color = colors.error, fontSize = 10.sp)
                }
                if (compact) IconButton(onClick = { if (job.state == "Complete") open() else onRetry(job) }) {
                    Icon(if (job.state == "Complete") Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
                        if (job.state == "Complete") "Open ${job.title}" else "Retry ${job.title}", tint = colors.primary)
                }
            }
            if (!compact) {
                if (job.audioDescription.isNotBlank() && !small) Text(job.audioDescription, color = colors.secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
                if (job.state != "Complete") Text(job.detail, color = colors.error, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (job.state == "Complete") java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(job.created))
                        else job.state, color = colors.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (job.state == "Complete") open() else onRetry(job) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(if (job.state == "Complete") Icons.Rounded.PlayArrow else Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Text(if (job.state == "Complete") "Open" else "Retry", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryThumbnail(job: Download, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
        Icon(if (job.audioOnly) Icons.Rounded.Headphones else Icons.Rounded.PlayCircle, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        if (job.thumbnail.isNotBlank()) AsyncImage(job.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
