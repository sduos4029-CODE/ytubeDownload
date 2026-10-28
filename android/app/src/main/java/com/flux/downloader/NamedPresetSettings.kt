package com.flux.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun NamedPresetSettings() {
    val items by NamedPresets.items.collectAsState()
    val active by NamedPresets.default.collectAsState()
    val problem by NamedPresets.error.collectAsState()
    var editing by remember {mutableStateOf<NamedPreset?>(null)}
    var deleting by remember {mutableStateOf<NamedPreset?>(null)}
    var message by remember {mutableStateOf("")}
    HorizontalDivider()
    Text("Custom presets",style=MaterialTheme.typography.titleSmall)
    Text("Save resolution, source format and audio language together. Video/audio merging may save as MKV.",style=MaterialTheme.typography.bodySmall)
    if(active!=null)Text("Named default: ${active!!.name}",style=MaterialTheme.typography.bodySmall)
    items.forEach {preset ->
        Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
            Text(preset.name,style=MaterialTheme.typography.titleSmall)
            Text(preset.summary(),style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick={runCatching {NamedPresets.setDefault(preset.id)}.onFailure {message=it.message.orEmpty()}},modifier=Modifier.weight(1f).semantics{contentDescription="Use default: ${preset.name}"}){Text(if(active?.id==preset.id)"Default preset" else "Use by default")}
                TextButton(onClick={editing=preset},modifier=Modifier.semantics{contentDescription="Edit preset ${preset.name}"}){Text("Edit")}
                TextButton(onClick={deleting=preset},modifier=Modifier.semantics{contentDescription="Delete preset ${preset.name}"}){Text("Delete")}
            }
        }
    }
    TextButton(enabled=items.size<NamedPresets.MAX,onClick={editing=NamedPreset(name="")}){Text("Create named preset")}
    if(problem.isNotBlank())Text(problem,style=MaterialTheme.typography.bodySmall)
    if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
    editing?.let {preset->NamedPresetEditor(preset,{editing=null}) {value->
        runCatching {NamedPresets.save(value)}.onSuccess {editing=null;message="Preset saved."}.exceptionOrNull()?.message
    }}
    deleting?.let {preset->AlertDialog(onDismissRequest={deleting=null},title={Text("Delete preset?")},text={Text("Delete ${preset.name}? Queued downloads keep their chosen formats. A deleted default returns to the stock preset.")},
        confirmButton={TextButton(onClick={runCatching{NamedPresets.remove(preset.id)}.onFailure{message=it.message.orEmpty()};deleting=null}){Text("Delete preset")}},
        dismissButton={TextButton(onClick={deleting=null}){Text("Keep preset")}})}
}

@Composable
private fun NamedPresetEditor(original:NamedPreset,onDismiss:()->Unit,onSave:(NamedPreset)->String?) {
    var name by remember {mutableStateOf(original.name)}
    var audio by remember {mutableStateOf(original.audioOnly)}
    var height by remember {mutableIntStateOf(original.height)}
    var extension by remember {mutableStateOf(original.extension)}
    var language by remember {mutableStateOf(original.language)}
    var menu by remember {mutableStateOf("")}
    var error by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(original.name.isBlank())"Create named preset" else "Edit named preset")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=name,onValueChange={name=it.take(40);error=""},label={Text("Preset name")},singleLine=true,modifier=Modifier.semantics{contentDescription="Preset name"})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!audio,onClick={audio=false;extension=""},label={Text("Video")})
                FilterChip(selected=audio,onClick={audio=true;height=0;extension=""},label={Text("Audio only")})
            }
            if(!audio)Box {
                OutlinedButton(onClick={menu="quality"}){Text(if(height==0)"Resolution: Best available" else "Resolution: Up to ${height}p")}
                DropdownMenu(expanded=menu=="quality",onDismissRequest={menu=""},modifier=Modifier.heightIn(max=300.dp)) {
                    NamedPreset.heights.forEach {value->DropdownMenuItem(text={Text(if(value==0)"Best available" else "Up to ${value}p")},onClick={height=value;menu=""})}
                }
            }
            Box {
                OutlinedButton(onClick={menu="format"}){Text("Source format: ${extension.uppercase().ifBlank {"Any"}}")}
                DropdownMenu(expanded=menu=="format",onDismissRequest={menu=""}) {
                    (if(audio)listOf("","m4a","webm") else listOf("","mp4","webm")).forEach {value->
                        DropdownMenuItem(text={Text(value.uppercase().ifBlank {"Any source format"})},onClick={extension=value;menu=""})
                    }
                }
            }
            Box {
                OutlinedButton(onClick={menu="language"}){Text("Preset language: ${AudioLanguages.label(language)}")}
                DropdownMenu(expanded=menu=="language",onDismissRequest={menu=""},modifier=Modifier.heightIn(max=300.dp)) {
                    AudioLanguages.choices.forEach {(value,label)->DropdownMenuItem(text={Text(label)},onClick={language=value;menu=""})}
                }
            }
            Text("Uses available source formats without upscaling or conversion. Language is preferred when a matching separate track exists; otherwise source audio is kept.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        val value=original.copy(name=name.trim(),height=height,audioOnly=audio,extension=extension,language=language)
        error=runCatching{value.validated();onSave(value)}.getOrElse{it.message?:"Cannot save preset."}.orEmpty()
    }){Text("Save preset")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
fun BatchPresetPicker(value:BatchPreset,onChange:(BatchPreset)->Unit) {
    val named by NamedPresets.items.collectAsState()
    var open by remember {mutableStateOf(false)}
    Box {
        OutlinedButton(onClick={open=true}){Text("Batch preset: ${value.label}")}
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=320.dp)) {
            DownloadPreset.entries.forEach {stock->DropdownMenuItem(text={Text(stock.label)},onClick={onChange(BatchPreset(stock=stock));open=false})}
            named.forEach {preset->DropdownMenuItem(text={Text(preset.name)},onClick={onChange(BatchPreset(named=preset));open=false})}
        }
    }
}
