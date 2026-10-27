package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun LibraryOrganisationPanel(jobs:List<Download>,selected:Set<String>,collectionFilter:String,onFilter:(String)->Unit) {
    var open by remember {mutableStateOf(false)}
    TextButton(onClick={open=true}) {Text("Collections & file tools")}
    if(!open)return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var collection by remember {mutableStateOf("")};var tags by remember {mutableStateOf("")}
    var prefix by remember {mutableStateOf("")};var message by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)};var task by remember {mutableStateOf<Job?>(null)}
    var plan by remember {mutableStateOf<List<LibraryOrganisation.Rename>>(emptyList())}
    val chosen=jobs.filter {it.id in selected}
    fun work(block:suspend ()->Unit) {
        if(busy)return
        busy=true;message=""
        task=scope.launch {try {block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Unable to finish this operation."}finally{busy=false}}
    }
    if(plan.isNotEmpty())AlertDialog(onDismissRequest={if(!busy)plan=emptyList()},title={Text("Confirm file renaming")},text={
        Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())) {
            Text("Files are renamed in place. Providers may reject some names; each result is retained separately.")
            plan.forEach {Text("${it.previous}\n→ ${it.next}",Modifier.padding(vertical=8.dp))}
        }
    },confirmButton={TextButton(enabled=!busy,onClick={work {
        val result=LibraryOrganisation.rename(context,plan);plan=emptyList();message="${result.first} renamed · ${result.second} failed. Original file extensions were kept."
    }}){Text("Rename files")}},dismissButton={TextButton(enabled=!busy,onClick={plan=emptyList()}){Text("Keep names")}})
    AlertDialog(onDismissRequest={task?.cancel();open=false},title={Text("Collections & file tools")},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${chosen.size} selected. Close this panel and use Select downloads to choose up to 100 items.")
            Text("Collection filter: ${collectionFilter.ifBlank {"All"}}")
            TextButton(enabled=!busy,onClick={onFilter("");open=false}) {Text("Show all collections")}
            jobs.map {it.collection}.filter {it.isNotBlank()}.distinct().sorted().forEach {name ->
                TextButton(enabled=!busy,onClick={onFilter(name);open=false}) {Text("Show collection: $name")}
            }
            OutlinedTextField(value=collection,onValueChange={collection=it.take(60)},label={Text("Collection name")},singleLine=true,enabled=!busy)
            OutlinedTextField(value=tags,onValueChange={tags=it.take(400)},label={Text("Tags separated by commas")},enabled=!busy)
            Text("Blank values clear that field. Tags and collection names are included in Library search.")
            FilledTonalButton(enabled=!busy && chosen.isNotEmpty(),onClick={work {
                withContext(Dispatchers.IO){LibraryOrganisation.set(selected,collection,tags)};message="Collection and tags saved."
            }}){Text("Apply collection and tags")}
            HorizontalDivider()
            OutlinedTextField(value=prefix,onValueChange={prefix=it.take(70)},label={Text("Rename prefix")},singleLine=true,enabled=!busy)
            Text("Preview sequential names before changing actual files. Scoped-storage providers can reject renaming; those files are kept unchanged.")
            OutlinedButton(enabled=!busy && chosen.isNotEmpty(),onClick={work {plan=LibraryOrganisation.preview(context,chosen,prefix)}}){Text("Preview bulk rename")}
            HorizontalDivider()
            OutlinedButton(enabled=!busy,onClick={work {
                val scan=if(chosen.isNotEmpty())chosen else jobs.filter {it.state=="Complete"}
                val groups=LibraryOrganisation.duplicates(context,scan) {n,total ->message="Checking file $n of $total · SHA-256"}
                message=if(groups.isEmpty())"No matching content found among readable files." else groups.joinToString("\n\n",prefix="Matching content; no files removed.\n\n") {group -> group.joinToString("\n"){id ->jobs.first {it.id==id}.title}}
            }}){Text("Find duplicate content")}
            Text("Reads up to 100 files and compares SHA-256. Unavailable files are skipped. This can take time for large videos.")
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={task?.cancel()}){Text("Cancel operation")}}
            if(message.isNotBlank())Text(message)
        }
    },confirmButton={TextButton(onClick={task?.cancel();open=false}){Text("Close tools")}})
}
