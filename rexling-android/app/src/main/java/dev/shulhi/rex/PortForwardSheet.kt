package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortForwardSheet(servers: List<ForwardServer>,selectedServer: String,hazeState: HazeState?,onClose: ()->Unit,onStart: ((String)->Unit)?=null,
    onAdd: (ForwardServer,String,Int,Int)->Unit) {
    val context=LocalContext.current
    var editing by remember {mutableStateOf(false)}
    val canAdd=servers.any {it.connected && it.session.isNotBlank()} && PortForwards.items.size<64
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets.safeDrawing.only(WindowInsetsSides.Top)}) {
        Column(frost.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(bottom=4.dp)) {
            DrawerHandle()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Port forwarding",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.ADD,"Add port forward",enabled=canAdd) {editing=true}
                ChromeButton(ChromeIcon.CLOSE,"Close port forwarding",onClick=onClose)
            }
            Text("All servers · ${PortForwards.items.count {it.running}} running",Modifier.padding(horizontal=16.dp,vertical=6.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PortForwards.notice?.let {notice->
                Text(notice,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                TextButton(onClick={PortForwards.notice=null}) {Text("Dismiss")}
            }
            if(PortForwards.items.isEmpty()) {
                Text("Tap a localhost link in a terminal, or add a forward.",Modifier.padding(16.dp),style=SettingsType.detail)
                TextButton(onClick={editing=true},enabled=canAdd,modifier=Modifier.padding(horizontal=8.dp)) {Text("Add port forward")}
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=440.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                PortForwards.items.groupBy {it.serverId.ifBlank {it.endpoint}}.forEach {(serverId,entries)->
                    item(key="server:$serverId") {
                        val profile=servers.firstOrNull {it.profile.id==serverId}?.profile
                        Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            ServerGlyph(profile?.icon ?: ServerIcon.GLOBE,Modifier.size(16.dp))
                            Text(profile?.label ?: entries.first().server,style=SettingsType.section,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries,key={it.id}) {item->
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f),RoundedCornerShape(12.dp)).padding(start=4.dp,end=0.dp,top=4.dp,bottom=4.dp)) {
                            Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                                val stateColor=when(item.phase) {
                                    "active"->MaterialTheme.colorScheme.primary
                                    "connecting","reconnecting"->Color(0xFFD69B42)
                                    "failed"->MaterialTheme.colorScheme.error
                                    else->MaterialTheme.colorScheme.outline
                                }
                                Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                                    IconButton(onClick={
                                        if(item.running) PortForwards.stop(context,item.id) else (onStart ?: {id: String->PortForwards.requestStart(context,id)})(item.id)
                                    },modifier=Modifier.fillMaxSize().semantics {contentDescription=if(item.running) "Stop" else "Start"}) {
                                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha=.09f)),contentAlignment=Alignment.Center) {
                                            ChromeSymbol(if(item.running) ChromeIcon.STOP else ChromeIcon.PLAY,Modifier.size(19.dp),color=MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(6.dp).background(stateColor,CircleShape).semantics {contentDescription="Forward ${item.phase}"})
                                Spacer(Modifier.width(6.dp))
                                Text("${item.boundPort.takeIf {it>0} ?: "auto"}",style=SettingsType.row.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.onSurface)
                                ChromeSymbol(ChromeIcon.RIGHT,Modifier.padding(horizontal=4.dp).size(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.target,Modifier.weight(1f),style=SettingsType.row.copy(fontFamily=MonoUi),color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick={PortForwards.remove(context,item.id)},modifier=Modifier.size(48.dp).semantics {contentDescription="Remove forward to ${item.target}"}) {
                                    ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(18.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if(item.error.isNotBlank()) Text(item.error,Modifier.padding(start=68.dp,bottom=4.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
                            if(item.address.isNotBlank() && item.phase=="active") Row(Modifier.padding(start=68.dp).height(32.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(item.url,Modifier.weight(1f),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                TextButton(onClick={PortForwards.openBrowser(context,item.url)},modifier=Modifier.height(32.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)) {Text("Open",style=SettingsType.detail)}
                                IconButton(onClick={
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Forwarded URL",item.url))
                                },modifier=Modifier.width(48.dp).height(32.dp).semantics {contentDescription="Copy forwarded URL"}) {
                                    ChromeSymbol(ChromeIcon.COPY,Modifier.size(19.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(editing) ForwardEditor(servers,selectedServer,onClose={editing=false}) {target,host,port,local->editing=false;onAdd(target,host,port,local)}
}
@Composable private fun ForwardEditor(servers: List<ForwardServer>,selectedServer: String,onClose: ()->Unit,onSave: (ForwardServer,String,Int,Int)->Unit) {
    var serverId by remember {mutableStateOf(servers.firstOrNull {it.profile.id==selectedServer && it.connected && it.session.isNotBlank()}?.profile?.id ?: servers.firstOrNull {it.connected && it.session.isNotBlank()}?.profile?.id)}
    var serverMenu by remember {mutableStateOf(false)}
    val server=servers.firstOrNull {it.profile.id==serverId}
    var host by remember {mutableStateOf("127.0.0.1")}
    var port by remember {mutableStateOf("")}
    var local by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var advanced by remember {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onClose,title={Text("Add port forward",style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton(onClick={serverMenu=true}) {server?.let {ServerGlyph(it.profile.icon,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))};Text("${server?.profile?.label ?: "Choose server"} ▾")}
                DropdownMenu(serverMenu,{serverMenu=false}) {
                    servers.forEach {target->DropdownMenuItem(text={Text(target.profile.label+if(!target.connected || target.session.isBlank()) " · connect first" else "")},
                        leadingIcon={ServerGlyph(target.profile.icon,Modifier.size(19.dp))},enabled=target.connected && target.session.isNotBlank(),onClick={serverId=target.profile.id;serverMenu=false})}
                }
            }
            OutlinedTextField(port,{port=it;error=null},label={Text("Remote port")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            TextButton(onClick={advanced=!advanced},contentPadding=PaddingValues(horizontal=0.dp)) {Text(if(advanced) "Hide advanced" else "Advanced")}
            if(advanced) {
                OutlinedTextField(host,{host=it;error=null},label={Text("Remote host")},singleLine=true)
                OutlinedTextField(local,{local=it;error=null},label={Text("Phone port")},placeholder={Text("Same as remote port")},supportingText={Text("Use 0 to choose an available port.",style=SettingsType.detail)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={
        val remote=port.toIntOrNull();val localPort=if(local.isBlank()) remote else local.toIntOrNull()
        val name=host.trim().removeSurrounding("[","]")
        if(remote==null || remote !in 1..65535 || localPort==null || localPort !in 0..65535) error="Enter valid ports from 1 to 65535."
        else if(name.isBlank() || name.length>253 || name.any {it.isWhitespace() || it=='/' || it.isISOControl()}) error="Enter a hostname or IP address."
        else if(server==null || !server.connected || server.session.isBlank()) error="Connect a server first."
        else onSave(server,name,remote,localPort)
    }) {Text("Forward")}},dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}
