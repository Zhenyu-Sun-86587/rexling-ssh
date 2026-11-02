package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ActivitySheet(sources: List<ActivitySource>,onRefresh: ()->Unit,onSelect: (ActivityTarget)->Unit,onClose: ()->Unit) {
    var filter by remember {mutableStateOf(ActivityFilter.ALL)}
    val filtered=sources.map {it to ActivityCatalog.records(it,filter)}
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=640.dp).padding(bottom=20.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Activity",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
                TextButton(onClick=onRefresh,enabled=sources.any {it.connected}) {Text("Refresh")}
                ChromeButton(ChromeIcon.CLOSE,"Close activity",onClick=onClose)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ActivityFilter.entries.forEach {entry->FilterChip(selected=filter==entry,onClick={filter=entry},label={Text(entry.label)})}
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if(sources.any {it.loading}) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                filtered.forEach {(source,records)->
                    if(records.isNotEmpty() || source.error.isNotBlank() || source.partial) {
                        item(key="server:${source.server.id}") {
                            Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(source.server.label,Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if(source.connected) "${records.size}" else "Offline",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(source.error.isNotBlank()) Text(source.error,Modifier.padding(bottom=6.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)
                            else if(source.partial) Text("Some activity could not be refreshed.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(records,key={"${source.server.id}:${it.key}"}) {record->
                            val attention=record.state in listOf(ActivityState.BLOCKED,ActivityState.ERROR)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .semantics {contentDescription="${record.label}, ${record.stateLabel}, ${record.sessionLabel}, ${source.server.label}"}
                                .clickable(enabled=source.connected){onSelect(ActivityTarget(source.server,record))}.padding(12.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                ProgramStack(listOf(record.program),0,framed=true)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                        Text(record.label,Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(record.stateLabel,fontSize=10.sp,color=if(attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    }
                                    if(record.message.isNotBlank()) Text(record.message,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(listOf(record.sessionLabel,record.windowLabel,record.id).filter {it.isNotBlank()}.joinToString(" · "),fontSize=10.sp,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    record.progress?.let {progress->
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            LinearProgressIndicator(progress={progress/100f},modifier=Modifier.weight(1f).height(3.dp))
                                            Text("$progress%",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if(filtered.all {it.second.isEmpty()} && sources.none {it.error.isNotBlank() || it.partial}) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(if(sources.any {it.loading}) "Checking activity…" else "No activity to show",fontWeight=FontWeight.SemiBold)
                        Text("Programs appear here when they report a status.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
