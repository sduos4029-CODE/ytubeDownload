package com.flux.downloader

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private val Ink = Color(0xFF0C0D13)
private val Panel = Color(0xFF171822)
private val Lilac = Color(0xFFBAACFF)
private val Mint = Color(0xFF85E5C4)
private val Muted = Color(0xFF9B9BAD)
private val scheme = darkColorScheme(primary = Lilac, onPrimary = Color(0xFF271D46), secondary = Mint,
    background = Ink, surface = Panel, surfaceVariant = Color(0xFF242531), onSurfaceVariant = Muted)

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloads.recover(this)
        shared = extractShare(intent)
        setContent { MaterialTheme(colorScheme = scheme) { FluxApp(shared) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); shared = extractShare(intent) }
    private fun extractShare(intent: Intent?): String {
        val text = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        return MediaLinks.shared(text)
    }
}

class FluxModel(app: Application) : AndroidViewModel(app) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Video>>(emptyList())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Video?>(null)
    var inspecting by mutableStateOf(false)
    var selectedItems by mutableStateOf(setOf<String>())
    fun search() {
        if (busy || query.isBlank()) return
        busy = true; error = null; results = emptyList(); selected = null; selectedItems = emptySet()
        viewModelScope.launch {
            try {
                results = Engine.search(getApplication(), query)
                if (results.isEmpty()) error = "No videos found. Try another search."
            } catch (e: Exception) { error = SourceFailures.message(runCatching { Engine.target(query) }.getOrDefault(""),e.message.orEmpty()) ?: e.message?.takeLast(350) ?: "Search failed. Check your connection." }
            finally { busy = false }
        }
    }
    fun inspect(video: Video) {
        if (inspecting) return
        inspecting = true; error = null
        viewModelScope.launch {
            try { selected = if (video.formats.isNotEmpty()) video else Engine.inspect(getApplication(), video) }
            catch (e: Exception) { error = e.message?.takeLast(350) ?: "Could not load formats." }
            finally { inspecting = false }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FluxApp(shared: String, model: FluxModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by Store.downloads.collectAsState()
    val folder by Store.folder.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    val instagramConnected by InstagramSession.connected.collectAsState()
    var previousInstagramConnection by remember { mutableStateOf(instagramConnected) }
    LaunchedEffect(instagramConnected) {
        val retry=instagramConnected && !previousInstagramConnection && model.error!=null && !model.busy &&
            MediaLinks.isInstagram(runCatching {Engine.target(model.query)}.getOrDefault(""))
        previousInstagramConnection=instagramConnected
        if(retry) {page=0;model.search()}
    }
    var clearDialog by remember { mutableStateOf(false) }
    val notification = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val chooseFolder = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                Store.chooseFolder(context, uri)
            }.onFailure { Toast.makeText(context, "Unable to keep folder access. Choose another folder.", Toast.LENGTH_LONG).show() }
        }
    }
    LaunchedEffect(shared) { if (shared.isNotBlank()) { model.query = shared; page = 0; model.search() } }
    val active = jobs.count { !it.terminal }
    Scaffold(containerColor = Ink, bottomBar = {
        NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
            listOf("Discover" to Icons.Rounded.Explore, "Downloads" to Icons.Rounded.Downloading,
                "Library" to Icons.Rounded.VideoLibrary, "Settings" to Icons.Rounded.Tune).forEachIndexed { index, (label, icon) ->
                NavigationBarItem(selected = page == index, onClick = { page = index },
                    icon = { BadgedBox(badge = { if (index == 1 && active > 0) Badge { Text(active.toString()) } }) { Icon(icon, label) } },
                    label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Lilac.copy(alpha = .15f), selectedIconColor = Lilac, selectedTextColor = Lilac))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_flux_mark), null, tint = Color.Unspecified, modifier = Modifier.size(30.dp))
                }
                Text("flux", Modifier.padding(start = 9.dp), fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Spacer(Modifier.weight(1f))
                Surface(color = Mint.copy(alpha = .09f), shape = CircleShape) {
                    Text("YOUR OFFLINE SPACE", color = Mint, fontSize = 9.sp, letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            when (page) {
                0 -> Discover(model) {
                    if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    page=1
                }
                1 -> QueuePage(jobs.filterNot { it.terminal })
                2 -> LibraryPage(jobs.filter { it.terminal }, onRetry = { old ->
                    if (old.state == "Failed") Downloads.resume(context, old)
                    else Downloads.enqueue(context, old.copy(id = java.util.UUID.randomUUID().toString(), state = "Queued", progress = 0f,
                        detail = "Waiting to download", uri = "", pendingUri = "", bytes = 0, retryCount = 0, nextAttemptAt = 0,
                        folder = folder, created = System.currentTimeMillis()))
                    page = 1
                }, onShowDownloads = { page = 1 }, onDownloadAgain = { old ->
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { Downloads.downloadAgain(context, old.id) }
                            if (!result.created) Toast.makeText(context, "Matching download already in Downloads", Toast.LENGTH_SHORT).show()
                            page = 1
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { Toast.makeText(context, e.message ?: "Unable to queue this download.", Toast.LENGTH_LONG).show() }
                    }
                })
                3 -> SettingsPage(folder, jobs.any { !it.terminal }, model,
                    onFolder = { chooseFolder.launch(folder.takeIf { it.isNotBlank() }?.let(Uri::parse)) },
                    onReset = { Store.chooseFolder(context, null) }, onClear = { clearDialog = true })
            }
        }
    }
    model.selected?.let { video ->
        ModalBottomSheet(onDismissRequest = { model.selected = null }, containerColor = Panel,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            if(video.isPhoto) PhotoSheet(video) {
                Downloads.enqueue(context,MediaDownloads.prepare(video,folder))
                if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                model.selected = null; page = 1
            } else FormatSheet(video) { format, selectedAudio ->
                Downloads.enqueue(context, MediaDownloads.prepare(video,folder,format,selectedAudio))
                if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                model.selected = null; page = 1
            }
        }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Clear download history?") },
        text = { Text("Saved files stay in your download folder. Completed, cancelled, and failed entries will be removed.") },
        confirmButton = { TextButton(onClick = { Downloads.clearHistory(context); clearDialog = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Keep history") } })
}

@Composable
private fun Discover(model: FluxModel, onBatchQueued: ()->Unit) {
    val context=LocalContext.current
    var confirmItems by remember { mutableStateOf<List<Video>>(emptyList()) }
    if(confirmItems.isNotEmpty()) AlertDialog(onDismissRequest={confirmItems=emptyList()},title={Text("Download ${confirmItems.size} items?")},
        text={Text("Photos keep the best available source image. Videos use the highest reported resolution and best available source audio. Each item has separate progress in Downloads.")},
        confirmButton={TextButton(onClick={
            val items=confirmItems;confirmItems=emptyList()
            runCatching {
                val jobs=items.map { MediaDownloads.prepare(it,Store.folder.value) }
                jobs.forEach { Downloads.enqueue(context,it) }
                model.selectedItems=emptySet();onBatchQueued()
            }.onFailure { Toast.makeText(context,it.message ?: "Load this post again before downloading.",Toast.LENGTH_LONG).show() }
        }) {Text("Queue items")}},dismissButton={TextButton(onClick={confirmItems=emptyList()}) {Text("Keep browsing")}})
    val clipboard = LocalClipboardManager.current
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("GOOD THINGS. SAVED.", color = Lilac, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 14.dp))
            Text("A little less online.\nA little more yours.", fontSize = 34.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 12.dp))
            Text("Your favorites, ready when you are.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = Panel, border = BorderStroke(1.dp, Color.White.copy(alpha = .07f))) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, null, tint = Lilac, modifier = Modifier.size(19.dp))
                        Text("START WITH A LINK OR SEARCH", fontSize = 10.sp, letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 8.dp), color = Muted)
                    }
                    OutlinedTextField(value = model.query, onValueChange = { model.query = it },
                        placeholder = { Text("YouTube search or video link", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true,
                        shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                        keyboardActions = KeyboardActions(onSearch = { model.search() }),
                        trailingIcon = { IconButton(onClick = { clipboard.getText()?.text?.let { model.query = it } }) { Icon(Icons.Rounded.ContentPaste, "Paste", tint = Muted, modifier = Modifier.size(20.dp)) } })
                    Button(onClick = { model.search() }, enabled = !model.busy && model.query.isNotBlank(),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        if (model.busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Search, null, Modifier.size(20.dp))
                        Text(if (model.busy) "Finding your video…" else "Find video", Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                    }
                    Text("Words search YouTube. Paste a public Instagram Reel, photo or carousel post link. Profiles, Stories, Threads and login-required posts are not supported.",
                        color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TinyFeature(Icons.Rounded.HighQuality, "Every available quality")
                TinyFeature(Icons.Rounded.GraphicEq, "Original audio")
            }
        }
        model.error?.let { message -> item {
            ErrorCard(message)
            if(MediaLinks.isInstagram(runCatching {Engine.target(model.query)}.getOrDefault(""))) {
                Spacer(Modifier.height(12.dp));ServerConnectionCard()
            }
        } }
        if (model.inspecting) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Lilac) }
        if (model.results.isNotEmpty()) {
            item { SectionTitle("Found for you", "${model.results.size} result${if (model.results.size > 1) "s" else ""}") }
            if(model.results.size>1 && model.results.all { it.platform==MediaPlatform.Instagram }) item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Choose photos and videos",fontWeight=FontWeight.Bold)
                    Text("${model.selectedItems.size} selected · up to 20 items per post",color=Muted,fontSize=12.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={confirmItems=model.results}) {Text("Download all")}
                        FilledTonalButton(enabled=model.selectedItems.isNotEmpty(),onClick={confirmItems=model.results.filter { it.key in model.selectedItems }}) {Text("Download selected")}
                    }
                }
            }
            items(model.results, key = { it.key }) { video ->
                Column {
                    if(video.itemCount>1) Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=video.key in model.selectedItems,onCheckedChange={ checked ->
                            model.selectedItems=if(checked) model.selectedItems+video.key else model.selectedItems-video.key
                        },modifier=Modifier.semantics { contentDescription="Select item ${video.itemIndex}" })
                        Text("Item ${video.itemIndex} of ${video.itemCount} · ${if(video.isPhoto) "Photo" else "Video"}")
                    }
                    VideoCard(video, model.inspecting) { model.inspect(video) }
                }
            }
        } else if (!model.busy) {
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF25213B), Panel))).padding(22.dp)) {
                    Column {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Lilac, modifier = Modifier.size(30.dp))
                        Text("Big quality.\nSmall effort.", fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp, modifier = Modifier.padding(top = 16.dp))
                        Text("Pick a resolution. We’ll pair separate video with the best audio and bring them together.", color = Muted,
                            fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp))
                        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tag("UP TO 8K+"); Tag("VIDEO + AUDIO"); Tag("NO RE-ENCODING")
                        }
                    }
                }
            }
        }
        item { Text("Available formats depend on the video. Download content you own or have permission to save.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun VideoCard(video: Video, disabled: Boolean, select: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Column {
            Box {
                AsyncImage(video.thumbnail, "Thumbnail for ${video.title}", Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color(0xFF242531)), contentScale = ContentScale.Crop)
                if (video.seconds > 0) Surface(color = Ink.copy(alpha = .85f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                    Text(durationLabel(video.seconds), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                Text("${video.platform.label} · ${video.author}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                FilledTonalButton(onClick = select, enabled = !disabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(17.dp)); Text(if(video.isPhoto) "View photo" else "Choose quality", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatSheet(video: Video, onDownload: (MediaFormat, MediaFormat?) -> Unit) {
    var audio by remember(video.key) { mutableStateOf(false) }
    var chosen by remember(video.key, audio) { mutableStateOf<MediaFormat?>(null) }
    val sourceAudios = remember(video.key) { video.formats.filter { it.audio && !it.video }.asReversed().sortedBy { it.isDrc } }
    var selectedAudio by remember(video.key) { mutableStateOf<MediaFormat?>(null) }
    var audioMenu by remember { mutableStateOf(false) }
    val formats = remember(video.key, audio) {
        if (audio) sourceAudios else video.formats.filter { it.video }
            .sortedWith(compareByDescending<MediaFormat> { it.height }.thenByDescending { it.fps }.thenBy { it.extension })
    }
    val selected = chosen ?: formats.firstOrNull()
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f)
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
        Text("Make it yours", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(video.title, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !audio, onClick = { audio = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(if(video.formats.any { it.audio }) "Video + audio" else "Video") }
            SegmentedButton(selected = audio, onClick = { audio = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Audio only") }
        }
        if (!audio && sourceAudios.isNotEmpty()) {
            Text("Audio track", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp))
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { audioMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedAudio?.audioLabel ?: "Best available source audio", fontSize = 12.sp)
                        if (selectedAudio == null) Text("Prefer original dynamics · keep source quality", color = Muted, fontSize = 10.sp)
                    }
                    Icon(Icons.Rounded.ArrowDropDown, "Choose audio track")
                }
                DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }, modifier = Modifier.heightIn(max = 260.dp)) {
                    DropdownMenuItem(text = { Text("Best available source audio") }, onClick = { selectedAudio = null; audioMenu = false })
                    sourceAudios.forEach { track ->
                        DropdownMenuItem(text = { Text(track.audioLabel, fontSize = 12.sp) }, onClick = { selectedAudio = track; audioMenu = false })
                    }
                }
            }
        }
        Text(if (audio) "Source audio • source ranking • no re-encoding" else "All available video formats • best quality listed first",
            color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (formats.isEmpty()) item { Text("No downloadable formats in this category.", color = Muted, modifier = Modifier.padding(16.dp)) }
            items(formats, key = { it.id }) { format ->
                val picked = selected?.id == format.id
                Surface(shape = RoundedCornerShape(14.dp), color = if (picked) Lilac.copy(alpha = .10f) else Ink,
                    border = BorderStroke(1.dp, if (picked) Lilac.copy(alpha = .65f) else Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { chosen = format }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked, onClick = { chosen = format })
                        Column(Modifier.weight(1f)) {
                            Text(format.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${if(format.video && format.height==0) "Format ${format.id} · " else ""}${format.extension.uppercase()}${if (format.video) " · ${format.codec.takeUnless { it in setOf("","null","none") }?.substringBefore('.') ?: "codec not reported"}" else " · ${format.audioLabel}"} · ${if(format.size>0)sizeLabel(format.size) else "size not reported"}",
                                color = Muted, fontSize = 10.sp)
                        }
                        if (format.video && sourceAudios.isNotEmpty()) Tag("AUTO MERGE", Mint)
                    }
                }
            }
        }
        Text(if (!audio && video.formats.none { it.audio }) {
            if(video.formats.filter { it.video }.all { it.audioCodec=="none" }) "The source reports no audio stream. Missing sound cannot be created."
            else "The source did not report audio details. Any embedded sound is kept unchanged."
        }
            else if (!audio && sourceAudios.isNotEmpty())
            "Keeps the chosen video and audio without re-encoding. Existing embedded audio is replaced when needed. Saves as MKV. 4K describes video resolution; audio quality depends on the source."
            else "Keeps the source audio without re-encoding. Higher bitrate across different codecs does not always mean better sound. Quality missing from the source cannot be restored.",
            fontSize = 11.sp, color = Muted, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 14.dp))
        Button(onClick = { selected?.let { onDownload(it, selectedAudio) } }, enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
            Text("Download ${selected?.label.orEmpty()}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun SettingsPage(folder: String, active: Boolean, model: FluxModel, onFolder: () -> Unit, onReset: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Just your way.", fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("A few preferences for your offline space.", color = Muted, fontSize = 13.sp)
        ServerConnectionCard()
        InstagramConnectionCard()
        TransferSettingsCard()
        Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Lilac)
                Text("Download location", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(if (folder.isBlank()) if (Build.VERSION.SDK_INT >= 29) "Internal storage / Download / Flux" else "App storage / Downloads / Flux (removed on uninstall)"
                    else Uri.decode(Uri.parse(folder).lastPathSegment.orEmpty()), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Changes apply to new downloads. Folder access is retained for files in Library. Clearing history releases access to older folders when no job needs it.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onFolder, shape = RoundedCornerShape(12.dp)) { Text("Choose folder") }
                    if (folder.isNotBlank()) TextButton(onClick = onReset) { Text("Use default") }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Mint)
                Text("App updates", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text("Extractor improvements now arrive with signed app updates. Install updates over Flux to keep your history.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                val context = LocalContext.current
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://flux-apk-download-7c114.royalpin4.chatgpt.site/"))) }
                        .onFailure { Toast.makeText(context, "Open the Flux download page in your browser.", Toast.LENGTH_LONG).show() }
                }) { Text("Open download page") }

            }
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(19.dp)); Text("Clear download history", Modifier.padding(start = 8.dp))
        }
        Text("FLUX ${BuildConfig.VERSION_NAME}", color = Lilac, fontSize = 11.sp, letterSpacing = 2.sp)
        Text("Built for the things worth keeping.\nQuality depends on the original upload. Private, paid, age-restricted, and protected videos may be unavailable.", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
    }
}

@Composable private fun TinyFeature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Mint, modifier = Modifier.size(16.dp)); Text(text, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 5.dp)) }
}
@Composable private fun Tag(text: String, color: Color = Lilac) {
    Surface(color = color.copy(alpha = .09f), shape = RoundedCornerShape(5.dp)) { Text(text, color = color, fontSize = 8.sp, letterSpacing = .3.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) }
}
@Composable private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp); Spacer(Modifier.weight(1f)); Text(subtitle, color = Muted, fontSize = 11.sp) }
}
@Composable private fun ErrorCard(message: String) {
    Surface(color = Color(0xFF39262E), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp)) { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)); Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 10.dp)) }
    }
}
