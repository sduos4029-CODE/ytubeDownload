package com.flux.downloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun libraryFilterCount(query:String,kind:String,favourites:Boolean,status:LibraryStatus,collection:String)=
    listOf(query.isNotBlank(),kind!="All",favourites,status!=LibraryStatus.All,collection.isNotBlank()).count {it}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryOptionsSheet(prefs:LibraryPreferences,favourites:Boolean,status:LibraryStatus,count:Int,
    onFavourites:(Boolean)->Unit,onStatus:(LibraryStatus)->Unit,onClear:()->Unit,onDone:()->Unit,
    collections:@Composable ()->Unit) {
    var statusMenu by remember {mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=onDone,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Library options",style=MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Status",Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick={statusMenu=true}) {Text("Status: ${status.label}")}
                    DropdownMenu(expanded=statusMenu,onDismissRequest={statusMenu=false}) {
                        LibraryStatus.entries.forEach {option ->DropdownMenuItem(text={Text(option.label)},onClick={onStatus(option);statusMenu=false})}
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable {onFavourites(!favourites)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Favourites only",Modifier.weight(1f));Switch(checked=favourites,onCheckedChange=onFavourites)
            }
            if(count>0)TextButton(onClick=onClear) {Text("Clear filters ($count)")}
            HorizontalDivider()
            Text("Layout",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(LibraryLayout.List,LibraryLayout.Grid).forEach {layout->
                    FilterChip(selected=if(layout==LibraryLayout.List)prefs.layout!=LibraryLayout.Grid else prefs.layout==layout,
                        onClick={Store.setLibrary(prefs.copy(layout=layout))},label={Text(layout.name)},modifier=Modifier.weight(1f))
                }
            }
            Text("Item size",style=MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=prefs.layout==LibraryLayout.Compact,onClick={Store.setLibrary(prefs.copy(layout=LibraryLayout.Compact))},label={Text("Compact",fontSize=12.sp)})
                LibrarySize.entries.forEach {size->
                    FilterChip(selected=prefs.layout!=LibraryLayout.Compact && prefs.size==size,
                        onClick={Store.setLibrary(prefs.copy(layout=if(prefs.layout==LibraryLayout.Compact)LibraryLayout.List else prefs.layout,size=size))},
                        label={Text(size.name,fontSize=12.sp)})
                }
            }
            if(prefs.layout==LibraryLayout.Compact)Text("Compact uses list layout.",style=MaterialTheme.typography.bodySmall)
            collections()
            Button(onClick=onDone,modifier=Modifier.fillMaxWidth().height(48.dp)) {Text("Done")}
            Spacer(Modifier.height(12.dp))
        }
    }
}
