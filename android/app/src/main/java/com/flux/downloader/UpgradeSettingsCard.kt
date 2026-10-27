package com.flux.downloader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.util.Calendar

@Composable
fun UpgradeSettingsCard() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val prefs by UpgradeSettings.preferences.collectAsState()
    var language by remember(prefs.language){mutableStateOf(prefs.language)}
    var message by remember {mutableStateOf("")}
    var restoreText by remember {mutableStateOf<String?>(null)}
    fun save(value:UpgradePreferences) {
        runCatching {UpgradeSettings.set(value);Downloads.refreshConstraints(context);Downloads.recover(context)}.onFailure {message=it.message?:"Could not save settings."}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri!=null)scope.launch {
            try {
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"w")?.use {it.write(UpgradeSettings.backup().toByteArray(Charsets.UTF_8))}?:error("Cannot write this backup.")}
                message="Preferences exported. Credentials, folder permissions, history and scheduled times were excluded."
            }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Export failed."}
        }
    }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null)scope.launch {
            try {
                restoreText=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use {input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=65536){"Preference backup is too large."};output.write(buffer,0,n)}
                    output.toString("UTF-8")
                }?:error("Cannot read this backup.")}
            }catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Import failed."}
        }
    }
    if(restoreText!=null)AlertDialog(onDismissRequest={restoreText=null},title={Text("Restore preferences?")},text={Text("Replace language, charging, Wi-Fi, parallel download and Library display preferences. Saved files, history, folder access and credentials stay on this device. Schedules are not restored.")},
        confirmButton={TextButton(onClick={val data=restoreText!!;restoreText=null;scope.launch {
            try{withContext(Dispatchers.IO){UpgradeSettings.restore(context,data);Downloads.refreshConstraints(context)};message="Preferences restored."}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"Restore failed."}
        }}){Text("Restore preferences")}},dismissButton={TextButton(onClick={restoreText=null}){Text("Keep current preferences")}})
    Surface(shape=RoundedCornerShape(20.dp),tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Audio, schedule & backup",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(value=language,onValueChange={language=it.take(20)},label={Text("Preferred audio language")},placeholder={Text("en, hi, en-US · blank for source default")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("Uses a matching separate audio track when available; otherwise keeps the source ranking. You can override the track before downloading.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={save(prefs.copy(language=language.trim()))}){Text("Save audio preference")}
            Row(Modifier.fillMaxWidth()) {
                Text("Only while connected to power",Modifier.weight(1f))
                Switch(checked=prefs.chargingOnly,onCheckedChange={save(prefs.copy(chargingOnly=it))})
            }
            Text("New-download start time: ${if(prefs.startAt>System.currentTimeMillis())java.text.DateFormat.getDateTimeInstance().format(java.util.Date(prefs.startAt)) else "As soon as possible"}")
            Text("Applies when new items are queued. Android may start later because of network, power or background restrictions. Existing schedules are kept.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                val time=Calendar.getInstance()
                DatePickerDialog(context,{_,year,month,day ->
                    time.set(year,month,day)
                    TimePickerDialog(context,{_,hour,minute ->
                        time.set(Calendar.HOUR_OF_DAY,hour);time.set(Calendar.MINUTE,minute);time.set(Calendar.SECOND,0);time.set(Calendar.MILLISECOND,0)
                        if(time.timeInMillis>System.currentTimeMillis())save(prefs.copy(startAt=time.timeInMillis)) else message="Choose a future start time."
                    },time.get(Calendar.HOUR_OF_DAY),time.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(context)).show()
                },time.get(Calendar.YEAR),time.get(Calendar.MONTH),time.get(Calendar.DAY_OF_MONTH)).show()
            }){Text("Schedule new downloads")}
            if(prefs.startAt!=0L)TextButton(onClick={save(prefs.copy(startAt=0))}){Text("Remove new-download schedule")}
            HorizontalDivider()
            Text("Preference backup",style=MaterialTheme.typography.titleSmall)
            Text("Exports an allowlisted JSON file. Login sessions, server keys, addresses, folders, schedules and download history are excluded.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={export.launch("Flux-preferences.json")}){Text("Export preferences")}
            TextButton(onClick={import.launch(arrayOf("application/json","text/plain"))}){Text("Import preferences")}
            if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
        }
    }
}
