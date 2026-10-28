package com.flux.downloader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.Calendar

internal fun scheduleDescription(time:Long)=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(java.util.Date(time))+" · "+java.util.TimeZone.getDefault().id

@Composable
fun UpgradeSettingsCard() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val prefs by UpgradeSettings.preferences.collectAsState()
    val transfers by Store.transfers.collectAsState()
    val preset by DownloadPresets.default.collectAsState()
    val named by NamedPresets.default.collectAsState()
    var language by remember(prefs.language){mutableStateOf(prefs.language)}
    var languageMenu by remember{mutableStateOf(false)}
    var custom by remember{mutableStateOf(false)};var code by remember{mutableStateOf("")};var codeError by remember{mutableStateOf("")}
    var message by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var now by remember{mutableStateOf(System.currentTimeMillis())}
    var pendingSchedule by remember{mutableStateOf<Long?>(null)}
    var exportPreview by remember{mutableStateOf<Pair<String,PreferenceBackup>?>(null)}
    var exportData by rememberSaveable{mutableStateOf<String?>(null)}
    var restorePreview by remember{mutableStateOf<Pair<String,PreferenceBackup>?>(null)}
    LaunchedEffect(prefs.startAt) {
        now=System.currentTimeMillis()
        while(prefs.startAt>now){delay(minOf(30000L,(prefs.startAt-now).coerceAtLeast(1)));now=System.currentTimeMillis()}
    }
    fun save(value:UpgradePreferences):Boolean=runCatching {
        UpgradeSettings.set(value);Downloads.refreshConstraints(context);Downloads.recover(context);true
    }.getOrElse {message=it.message?:"Could not save settings.";false}
    fun propose(time:Long) {
        if(time>System.currentTimeMillis()){pendingSchedule=time;message=""}else message="Choose a future start time."
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        val snapshot=exportData;exportData=null
        if(uri!=null && snapshot==null)message="Backup review expired. Export preferences again."
        if(uri!=null && snapshot!=null)scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"w")?.use{it.write(snapshot.toByteArray(Charsets.UTF_8))}?:error("Cannot write this backup.")}
                message="Backup saved. It contains preferences only; videos, history, credentials, folder access and schedules were excluded."
            }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Export failed."}finally{busy=false}
        }
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null)scope.launch {
            busy=true
            try {
                restorePreview=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=65536){"Preference backup is too large."};output.write(buffer,0,n)}
                    val text=output.toString("UTF-8");text to UpgradeSettings.inspectBackup(text)
                }?:error("Cannot read this backup.")}
                message=""
            }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Import failed."}finally{busy=false}
        }
    }
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Audio, schedule & backup",style=MaterialTheme.typography.titleMedium)
            Text("1. Choose your audio language",style=MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(enabled=!busy,onClick={languageMenu=true}){Text("Audio language: ${AudioLanguages.label(language)}")}
                DropdownMenu(expanded=languageMenu,onDismissRequest={languageMenu=false},modifier=Modifier.heightIn(max=320.dp)) {
                    AudioLanguages.choices.forEach{(value,label)->DropdownMenuItem(text={Text(label)},onClick={language=value;languageMenu=false;message=""})}
                    DropdownMenuItem(text={Text("Another language…")},onClick={code=language;codeError="";custom=true;languageMenu=false})
                }
            }
            Text(if(language==prefs.language)"Saved: ${AudioLanguages.label(prefs.language)}" else "Not saved yet. Tap Save audio preference to apply.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy && language!=prefs.language,onClick={if(save(prefs.copy(language=language)))message="Audio preference saved: ${AudioLanguages.label(language)}."}){Text("Save audio preference")}
            Text("Uses a matching separate audio track when available. Otherwise Flux keeps the best available source audio; this does not translate speech.",style=MaterialTheme.typography.bodySmall)
            if(named!=null)Text("Named default ${named!!.name} uses ${AudioLanguages.label(named!!.language)} for its audio preference.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("2. Choose when new downloads start",style=MaterialTheme.typography.titleSmall)
            Text(if(prefs.startAt>now)"New downloads: ${scheduleDescription(prefs.startAt)}" else "New downloads: As soon as possible")
            Text("Set this before adding downloads. Changing it does not move items already in Downloads.",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Text("Only while connected to power",Modifier.weight(1f))
                Switch(enabled=!busy,checked=prefs.chargingOnly,onCheckedChange={if(save(prefs.copy(chargingOnly=it)))message="Power requirement updated."})
            }
            Text("${if(transfers.wifiOnly)"Wi-Fi required" else "Any connected network"} · ${if(prefs.chargingOnly)"Charging required" else "Charging optional"}",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy,onClick={
                val time=Calendar.getInstance().apply{if(prefs.startAt>System.currentTimeMillis())timeInMillis=prefs.startAt}
                DatePickerDialog(context,{_,year,month,day->
                    time.set(year,month,day)
                    TimePickerDialog(context,{_,hour,minute->
                        time.set(Calendar.HOUR_OF_DAY,hour);time.set(Calendar.MINUTE,minute);time.set(Calendar.SECOND,0);time.set(Calendar.MILLISECOND,0);propose(time.timeInMillis)
                    },time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(context)).show()
                },time.get(Calendar.YEAR),time.get(Calendar.MONTH),time.get(Calendar.DAY_OF_MONTH)).show()
            }){Text("Schedule new downloads")}
            TextButton(enabled=!busy,onClick={propose(System.currentTimeMillis()+3600000)}){Text("In 1 hour")}
            if(prefs.startAt!=0L)TextButton(enabled=!busy,onClick={if(save(prefs.copy(startAt=0)))message="New downloads can start now. Existing items keep their scheduled times."}){Text("Start new downloads now")}
            Text("Android may start later because of network, power or background restrictions.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("3. Back up your preferences",style=MaterialTheme.typography.titleSmall)
            Text("Includes named presets, audio language, quality preset, Wi-Fi and charging rules, parallel downloads, speed cap and Library layout, size and sort.",style=MaterialTheme.typography.bodySmall)
            Text("Does not include downloaded files, history, login details, server settings, folder permissions or schedules. No file editing is needed.",style=MaterialTheme.typography.bodySmall)
            TextButton(enabled=!busy,onClick={runCatching{val text=UpgradeSettings.backup();exportPreview=text to UpgradeSettings.inspectBackup(text)}.onFailure{message=it.message?:"Cannot prepare backup."}}){Text("Export preferences")}
            TextButton(enabled=!busy,onClick={import.launch(arrayOf("application/json","text/plain"))}){Text("Import preferences")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
        }
    }
    if(custom)AlertDialog(onDismissRequest={custom=false},title={Text("Another audio language")},text={
        Column {Text("Optional: enter a source language code, such as pt-BR.");OutlinedTextField(value=code,onValueChange={code=it.take(20);codeError=""},label={Text("Language code")},singleLine=true,isError=codeError.isNotBlank());if(codeError.isNotBlank())Text(codeError)}
    },confirmButton={TextButton(onClick={val value=code.trim();if(AudioLanguages.valid(value)){language=value;custom=false}else codeError="Use a code such as en, hi or pt-BR."}){Text("Choose language")}},dismissButton={TextButton(onClick={custom=false}){Text("Cancel")}})
    pendingSchedule?.let{time->AlertDialog(onDismissRequest={pendingSchedule=null},title={Text("Review new-download schedule")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Start after: ${scheduleDescription(time)}")
            Text("Audio: ${AudioLanguages.label(named?.language ?: prefs.language)} when available\nQuality preset: ${named?.name ?: preset.label}\n${if(transfers.wifiOnly)"Wi-Fi required" else "Any connected network"}\n${if(prefs.chargingOnly)"Charging required" else "Charging optional"}")
            Text("Only downloads added after you apply this schedule use it. Existing items keep their times. Android may start later.")
        }
    },confirmButton={TextButton(onClick={pendingSchedule=null;if(time<=System.currentTimeMillis())message="Choose a future start time." else if(save(prefs.copy(startAt=time)))message="Schedule applied to new downloads."}){Text("Apply schedule")}},dismissButton={TextButton(onClick={pendingSchedule=null}){Text("Keep current schedule")}})}
    exportPreview?.let{preview->AlertDialog(onDismissRequest={exportPreview=null},title={Text("Review preference backup")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(preview.second.summary())
            Text("Not saved: videos, history, credentials, server settings, folder permissions or schedules. Save the backup file in a folder you can find later.")
        }
    },confirmButton={TextButton(onClick={exportData=preview.first;exportPreview=null;export.launch("Flux-preferences.json")}){Text("Save backup file")}},dismissButton={TextButton(onClick={exportPreview=null}){Text("Keep editing")}})}
    restorePreview?.let{preview->AlertDialog(onDismissRequest={restorePreview=null},title={Text("Review preferences to restore")},text={
        Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(preview.second.summary())
            Text("These preferences will replace your current choices. Downloaded files, history, login details, folder access and current schedules stay unchanged.")
        }
    },confirmButton={TextButton(onClick={restorePreview=null;scope.launch{
        busy=true
        try{withContext(Dispatchers.IO){UpgradeSettings.restore(context,preview.first);Downloads.refreshConstraints(context)};message="Preferences restored. Files, history and current schedules were kept."}
        catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Restore failed."}finally{busy=false}
    }}){Text("Restore preferences")}},dismissButton={TextButton(onClick={restorePreview=null}){Text("Keep current preferences")}})}
}
