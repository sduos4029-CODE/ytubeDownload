package com.flux.downloader

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import coil.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

enum class LibraryLayout { List, Grid, Compact }
enum class LibrarySize { Small, Medium, Large }
enum class LibrarySort(val label: String) { Newest("Newest"), Oldest("Oldest"), Largest("Largest first"), Title("Title A–Z") }
data class LibraryPreferences(val layout: LibraryLayout = LibraryLayout.List, val size: LibrarySize = LibrarySize.Medium,
    val sort: LibrarySort = LibrarySort.Newest)

fun libraryJobs(jobs: List<Download>, query: String, kind: String, sort: LibrarySort, favouritesOnly: Boolean = false,
    status: LibraryStatus = LibraryStatus.All): List<Download> {
    val matching = jobs.filter { it.terminal && (status.state == null || it.state == status.state) && (!favouritesOnly || it.favourite) && (it.title+" "+it.collection+" "+it.tags).contains(query.trim(), ignoreCase = true) &&
        (kind == "All" || (kind == "Audio" && it.audioOnly) || (kind == "Video" && !it.audioOnly && !it.isPhoto) || (kind == "Photos" && it.isPhoto)) }
    return when (sort) {
        LibrarySort.Newest -> matching.sortedByDescending { it.created }
        LibrarySort.Oldest -> matching.sortedBy { it.created }
        LibrarySort.Largest -> matching.sortedWith(compareByDescending<Download> { it.bytes }.thenByDescending { it.created })
        LibrarySort.Title -> matching.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPage(jobs: List<Download>, onRetry: (Download) -> Unit, onDownloadAgain: (Download) -> Unit, onShowDownloads: () -> Unit) {
    val prefs by Store.library.collectAsState()
    var collectionFilter by remember {mutableStateOf("")}
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("All") }
    var sortMenu by remember { mutableStateOf(false) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(LibraryStatus.All) }
    var options by remember {mutableStateOf(false)}
    var searchOpen by rememberSaveable {mutableStateOf(false)}
    var toolsId by remember {mutableStateOf<String?>(null)}
    var detailsId by remember { mutableStateOf<String?>(null) }
    var repeatId by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var removal by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = remember(jobs, query, kind, prefs.sort, favouritesOnly, status,collectionFilter) { libraryJobs(jobs, query, kind, prefs.sort, favouritesOnly, status).filter {collectionFilter.isBlank() || it.collection==collectionFilter} }
    val context = LocalContext.current
    jobs.find {it.id==toolsId}?.let {source->MediaToolsDialog(source,onDismiss={toolsId=null},onQueued={toolsId=null;onShowDownloads()})}
    jobs.find { it.id == detailsId }?.let { job -> LibraryDetailsDialog(job,
        onDownloadAgain = { detailsId = null; repeatId = job.id }, onDismiss = { detailsId = null }) }
    jobs.find { it.id == repeatId }?.let { job -> RepeatDownloadDialog(job,
        onDismiss = { repeatId = null }, onConfirm = { repeatId = null; onDownloadAgain(job) },
        onShowExisting = { repeatId = null; onShowDownloads() }) }
    fun favourite(job: Download) {
        scope.launch { withContext(Dispatchers.IO) { Store.toggleFavourite(job.id) } }
    }
    val shownIds=shown.map {it.id}.toSet()
    val unselectResults=LibraryResultSelection.unselect(shownIds,selected)
    val chosen = jobs.filter { it.id in selected && it.terminal }
    val summary = remember(chosen) { selectionSummary(chosen) }
    val eligibleFavourites = chosen.filter { it.state == "Complete" && it.uri.isNotBlank() }
    fun setFavourites(value: Boolean) {
        if (busy) return
        val ids = selected
        busy = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { Store.setFavourites(ids, value) }
                Toast.makeText(context, "$count favourites updated", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { Toast.makeText(context, "Could not update favourites. Try again.", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    LaunchedEffect(jobs.map { it.id to it.state }) {
        selected = selected.intersect(jobs.filter { it.terminal }.map { it.id }.toSet())
    }
    BackHandler(selecting && !busy) { selecting = false; selected = emptySet() }
    fun toggle(job: Download) {
        if (job.id in selected) selected = selected - job.id
        else if (selected.size < LibraryActions.MAX_SELECTION) selected = selected + job.id
        else Toast.makeText(context, "Select up to 100 items at a time.", Toast.LENGTH_SHORT).show()
    }
    fun share(items: List<Download>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val intent = LibraryActions.shareIntent(context, items)
                context.startActivity(Intent.createChooser(intent, "Share downloads"))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "No app is available to share these files.", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    if (removal.isNotEmpty()) AlertDialog(onDismissRequest = { if (!busy) removal = emptySet() },
        title = { Text("Remove ${removal.size} history entries?") },
        text = { Text("Saved files stay in your download folder. Failed download partials are cleared. Active items and unfinished saves are kept.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            val ids = removal
            scope.launch {
                try {
                    val count = LibraryActions.removeHistory(context, ids)
                    selected = emptySet(); selecting = false; removal = emptySet()
                    Toast.makeText(context, "$count history entries removed. Saved files kept.", Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not update history. Try again.", Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }) { Text("Remove history") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { removal = emptySet() }) { Text("Keep entries") } })
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
    val filterCount=libraryFilterCount(query,kind,favouritesOnly,status,collectionFilter)
    fun clearFilters() {query="";kind="All";favouritesOnly=false;status=LibraryStatus.All;collectionFilter=""}
    if(options)LibraryOptionsSheet(prefs,favouritesOnly,status,filterCount,{favouritesOnly=it},{status=it},::clearFilters,{options=false}) {
        LibraryOrganisationPanel(jobs,selected,collectionFilter) {collectionFilter=it;options=false}
    }
    val colors = MaterialTheme.colorScheme
    val columns = if (prefs.layout == LibraryLayout.Grid) GridCells.Adaptive(when (prefs.size) {
        LibrarySize.Small -> 128.dp; LibrarySize.Medium -> 176.dp; LibrarySize.Large -> 256.dp
    }) else GridCells.Fixed(1)
    Column(Modifier.fillMaxSize()) {
    if (selecting) Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${chosen.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy && shownIds.isNotEmpty() && (shownIds.any {it in selected} || selected.size<LibraryActions.MAX_SELECTION),
                    onClick = { selected = LibraryResultSelection.toggle(shownIds,selected) }) { Text(if(unselectResults)"Unselect results" else "Select results") }
                TextButton(enabled = !busy, onClick = { selected = emptySet(); selecting = false }) { Text("Done") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = !busy && chosen.isNotEmpty() && chosen.all { it.state == "Complete" && it.uri.isNotBlank() },
                    onClick = { share(chosen) }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text(" Share files") }
                OutlinedButton(enabled = !busy && chosen.isNotEmpty(), onClick = { removal = selected }) { Text("Remove history") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.weight(1f), enabled = !busy && eligibleFavourites.any { !it.favourite },
                    onClick = { setFavourites(true) }) { Icon(Icons.Rounded.Favorite, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Favourite") }
                TextButton(modifier = Modifier.weight(1f), enabled = !busy && eligibleFavourites.any { it.favourite },
                    onClick = { setFavourites(false) }) { Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Unfavourite") }
            }
            Text(summary.label, fontSize = 11.sp, color = colors.onSurfaceVariant)
            Text("Recorded sizes; files may have changed. Selection stays across filters.", fontSize = 10.sp, color = colors.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Library",fontSize=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            IconButton(onClick={searchOpen=!searchOpen}) {Icon(Icons.Rounded.Search,"Search library")}
            if(!selecting && shown.isNotEmpty())IconButton(onClick={selecting=true}) {Icon(Icons.Rounded.Checklist,"Select downloads")}
        }
        if(searchOpen || query.isNotBlank())OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,
            placeholder={Text("Search your library")},leadingIcon={Icon(Icons.Rounded.Search,null)},
            trailingIcon={IconButton(onClick={query="";searchOpen=false}) {Icon(Icons.Rounded.Close,"Clear search")}},
            modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("All","Video","Audio","Photos").forEach {option->
                FilterChip(selected=kind==option,onClick={kind=option},label={Text(option,fontSize=12.sp)},modifier=Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box {
                TextButton(onClick={sortMenu=true},contentPadding=PaddingValues(horizontal=4.dp)) {
                    Text(prefs.sort.label,fontSize=12.sp);Icon(Icons.Rounded.ArrowDropDown,null,Modifier.size(18.dp))
                }
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    LibrarySort.entries.forEach {option->DropdownMenuItem(text={Text(option.label)},onClick={Store.setLibrary(prefs.copy(sort=option));sortMenu=false})}
                }
            }
            Text("${shown.size} results",color=colors.onSurfaceVariant,fontSize=11.sp,modifier=Modifier.weight(1f).padding(start=8.dp))
            TextButton(onClick={options=true},modifier=Modifier.semantics {contentDescription="Library options"}) {
                Icon(Icons.Rounded.Tune,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(if(filterCount>0)"Options ($filterCount)" else "Options",fontSize=12.sp)
            }
        }
    }
    LazyVerticalGrid(columns=columns,contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(if(prefs.layout==LibraryLayout.Compact)6.dp else 10.dp),
        modifier=Modifier.weight(1f)) {
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = colors.primary, modifier = Modifier.size(42.dp))
                Text(if (jobs.isEmpty()) "Your collection starts here." else "No matching downloads.",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 15.dp))
                Text(if (jobs.isEmpty()) "Find a video in Discover to save it." else "Try another title or filter.",
                    color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                if(filterCount>0)TextButton(onClick=::clearFilters) {Text("Clear filters ($filterCount)")}
            }
        }
        items(shown, key = { it.id }) { job -> LibraryCard(job, prefs, onRetry, selecting, job.id in selected,
            busy, { toggle(job) }, { share(listOf(job)) }, { favourite(job) }, { detailsId = job.id }, { repeatId = job.id },{toolsId=job.id}) }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
private fun LibraryCard(job: Download, prefs: LibraryPreferences, onRetry: (Download) -> Unit,
    selecting: Boolean, selected: Boolean, busy: Boolean, onSelect: () -> Unit, onShare: () -> Unit,
    onFavourite: () -> Unit, onDetails: () -> Unit, onDownloadAgain: () -> Unit,onMediaTools:()->Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val compact = prefs.layout == LibraryLayout.Compact
    val grid = prefs.layout == LibraryLayout.Grid
    val small = prefs.size == LibrarySize.Small
    val large = prefs.size == LibrarySize.Large
    var actions by remember { mutableStateOf(false) }
    @Composable fun actionsMenu() {
        Box {
            IconButton(enabled = !busy, onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, "More actions for ${job.title}", tint = colors.primary) }
            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (job.state == "Complete" && job.uri.isNotBlank()) DropdownMenuItem(
                    text = { Text(if (job.favourite) "Remove from favourites" else "Add to favourites") },
                    onClick = { actions = false; onFavourite() }, leadingIcon = { Icon(Icons.Rounded.Favorite, null) })
                if(job.state=="Complete" && job.uri.isNotBlank() && !job.isPhoto)DropdownMenuItem(text={Text("Media tools")},onClick={actions=false;onMediaTools()},leadingIcon={Icon(Icons.Rounded.ContentCut,null)})
                DropdownMenuItem(text = { Text("File details") }, onClick = { actions = false; onDetails() }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                if (job.state == "Complete" && RepeatDownloads.sourceKey(job.url) != null) DropdownMenuItem(
                    text = { Text("Download again") }, onClick = { actions = false; onDownloadAgain() }, leadingIcon = { Icon(Icons.Rounded.Download, null) })
                if (job.state == "Complete") DropdownMenuItem(text = { Text("Share file") }, onClick = { actions = false; onShare() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
            }
        }
    }
    fun open() {
        runCatching { context.startActivity(if(!job.audioOnly && !job.isPhoto)
            PlaybackActivity.intent(context,job.id) else Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.uri), job.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { Toast.makeText(context, "No compatible viewer, or the file was moved. Open your download folder.", Toast.LENGTH_LONG).show() }
    }
    Surface(shape = RoundedCornerShape(if (compact) 12.dp else 18.dp), color = colors.surface) {
        Column(Modifier.padding(if (compact || small) 10.dp else 14.dp)) {
            if (grid) LibraryThumbnail(job, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (grid) 11.dp else 0.dp)) {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onSelect() }, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Select ${job.title}" })
                if (!grid) LibraryThumbnail(job, Modifier.size(when { compact -> 44.dp; small -> 60.dp; large -> 112.dp; else -> 80.dp },
                    when { compact -> 38.dp; small -> 48.dp; large -> 84.dp; else -> 60.dp }))
                Column(Modifier.weight(1f).padding(start = if (grid) 0.dp else 12.dp)) {
                    Text(if (job.favourite) "★ ${job.title}" else job.title, maxLines = if (compact) 1 else if (large) 3 else 2, overflow = TextOverflow.Ellipsis,
                        fontSize = if (compact || small) 12.sp else if (large) 17.sp else 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${if(job.isPhoto) "Photo" else if (job.audioOnly) "Audio" else "Video"} · ${job.label}${if (job.bytes > 0) " · ${sizeLabel(job.bytes)}" else ""}",
                        color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 2,
                        modifier = Modifier.padding(top = 5.dp), overflow = TextOverflow.Ellipsis)
                    if (compact && job.state != "Complete") Text(job.state, color = colors.error, fontSize = 10.sp)
                }
                if (compact && !selecting) actionsMenu()
                if (compact && !selecting) IconButton(onClick = { if (job.state == "Complete") open() else onRetry(job) }) {
                    Icon(if(job.state=="Complete" && job.isPhoto) Icons.Rounded.Image else if (job.state == "Complete") Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
                        if (job.state == "Complete") "Open ${job.title}" else "Retry ${job.title}", tint = colors.primary)
                }
            }
            if (!compact) {
                if (job.audioDescription.isNotBlank() && !small) Text(job.audioDescription, color = colors.secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
                if(job.verification.isNotBlank() && !small)Text(job.verification,color=if(job.verification.startsWith("Unverified"))colors.error else colors.onSurfaceVariant,fontSize=10.sp,modifier=Modifier.padding(top=6.dp))
                if (job.state != "Complete") Text(job.detail, color = colors.error, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (job.state == "Complete") java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(job.created))
                        else job.state, color = colors.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    if (!selecting) actionsMenu()
                    if (!selecting) TextButton(onClick = { if (job.state == "Complete") open() else onRetry(job) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(if(job.state=="Complete" && job.isPhoto) Icons.Rounded.Image else if (job.state == "Complete") Icons.Rounded.PlayArrow else Icons.Rounded.Refresh, null, Modifier.size(18.dp))
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
        Icon(if(job.isPhoto) Icons.Rounded.Image else if (job.audioOnly) Icons.Rounded.Headphones else Icons.Rounded.PlayCircle, null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        if (job.thumbnail.isNotBlank() || (job.isPhoto && job.uri.isNotBlank())) AsyncImage(if(job.isPhoto && job.state=="Complete") job.uri else job.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
