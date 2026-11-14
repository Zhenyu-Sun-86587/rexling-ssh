package dev.shulhi.rex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServersSheet(model: RexModel,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background) {
        HostsPanel(model,onDismiss,Modifier.fillMaxWidth().heightIn(max=720.dp))
    }
}

@Composable internal fun HostsPanel(model: RexModel,onOpen: ()->Unit,modifier: Modifier=Modifier) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    var initialMode by remember {mutableStateOf("ssh")}
    var tailscale by remember {mutableStateOf(false)}
    var suggestion by remember {mutableStateOf<TailscaleDevice?>(null)}
    var forgetting by remember {mutableStateOf<ServerProfile?>(null)}
    fun add(mode: String) {editing=null;suggestion=null;initialMode=mode;editor=true}
    HostsContent(model.servers,model.serverStatuses,model.activeServerId,
        onAdd={add("ssh")},onTailscale={tailscale=true},onDirect={add("direct")},
        onSelect={host->if(host.jumpOnly) {editing=host;suggestion=null;editor=true} else {model.connectServer(host.id);onOpen()}},
        onEdit={editing=it;suggestion=null;editor=true},onDisconnect={model.disconnectServer(it.id)},onForget={forgetting=it},modifier=modifier)
    if(editor) ServerEditor(editing,model,initialMode,suggestion?.address.orEmpty(),suggestion?.label.orEmpty()) {editor=false}
    if(tailscale) TailscaleSheet(onDismiss={tailscale=false},onChoose={device->
        tailscale=false;suggestion=device;editing=null;initialMode="ssh";editor=true
    })
    forgetting?.let {host->AlertDialog(onDismissRequest={forgetting=null},title={Text("Forget ${host.label}?")},
        text={Text("Remove this saved host and its login from this device. Routes that copied its jump settings are kept.")},
        confirmButton={TextButton(onClick={model.forgetServer(host.id);forgetting=null}) {Text("Forget")}},
        dismissButton={TextButton(onClick={forgetting=null}) {Text("Cancel")}})}
}

@Composable internal fun HostsContent(
    servers: List<ServerProfile>,statuses: Map<String,ServerStatus>,activeId: String,
    onAdd: ()->Unit,onTailscale: ()->Unit,onDirect: ()->Unit,onSelect: (ServerProfile)->Unit,
    onEdit: (ServerProfile)->Unit,onDisconnect: (ServerProfile)->Unit,onForget: (ServerProfile)->Unit,
    modifier: Modifier=Modifier,
) {
    val scheme=MaterialTheme.colorScheme
    var search by remember {mutableStateOf("")}
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Your hosts",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                Text("${servers.size} saved · ${statuses.values.count {it.connected}} connected",style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick=onAdd) {Text("+ Add host")}
        }
        if(servers.isEmpty()) {
            Text("Your workspace starts here",style=MaterialTheme.typography.titleLarge)
            Text("Connect to Rex over SSH, or discover a device on your tailnet. Save a jump host even when it has no Rex server.",style=MaterialTheme.typography.bodyMedium,color=scheme.onSurfaceVariant)
            HostEntryCard("SSH host","A Rex target or an SSH-only jump host. Save first, connect when you are ready.","Add SSH host",onAdd,true)
            HostEntryCard("Tailscale devices","Find machines on your tailnet, then configure their SSH login. Your phone still needs the Tailscale VPN.","Discover devices",onTailscale,false)
            TextButton(onClick=onDirect,modifier=Modifier.fillMaxWidth()) {Text("Already have a Rex URL? Add it here")}
        } else {
            SshField(search,{search=it},title="Search hosts")
            val matching=servers.filter {search.isBlank() || it.label.contains(search,true) || it.endpoint.contains(search,true)}
            for(jumpOnly in listOf(false,true)) {
                val group=matching.filter {it.jumpOnly==jumpOnly}
                if(group.isNotEmpty()) {
                    Text(if(jumpOnly) "SSH jump hosts · Rex not required" else "Rex hosts",style=MaterialTheme.typography.labelLarge,color=scheme.onSurfaceVariant)
                    group.forEach {host->HostRow(host,statuses[host.id] ?: ServerStatus(),host.id==activeId,onSelect,onEdit,onDisconnect,onForget)}
                }
            }
            if(matching.isEmpty()) Text("No matching hosts.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick=onTailscale,modifier=Modifier.fillMaxWidth()) {Text("Discover Tailscale devices")}
            TextButton(onClick=onDirect,modifier=Modifier.fillMaxWidth()) {Text("Add an existing Rex URL")}
        }
        Text("Only Rex targets open workspaces. Jump hosts supply an SSH login and forwarding; they never auto-connect to Rex.",style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
    }
}

@Composable private fun HostEntryCard(title: String,description: String,action: String,onClick: ()->Unit,primary: Boolean) {
    val scheme=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(20.dp),color=if(primary) scheme.primaryContainer.copy(alpha=.35f) else scheme.surfaceContainer,
        border=BorderStroke(1.dp,if(primary) scheme.primary.copy(alpha=.45f) else scheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Text(description,style=MaterialTheme.typography.bodyMedium,color=scheme.onSurfaceVariant)
            if(primary) Button(onClick=onClick,modifier=Modifier.fillMaxWidth()) {Text(action)}
            else FilledTonalButton(onClick=onClick,modifier=Modifier.fillMaxWidth()) {Text(action)}
        }
    }
}

@Composable private fun HostRow(host: ServerProfile,status: ServerStatus,selected: Boolean,
    onSelect: (ServerProfile)->Unit,onEdit: (ServerProfile)->Unit,onDisconnect: (ServerProfile)->Unit,onForget: (ServerProfile)->Unit,
) {
    val scheme=MaterialTheme.colorScheme
    var menu by remember {mutableStateOf(false)}
    Surface(onClick={onSelect(host)},shape=RoundedCornerShape(18.dp),color=if(selected) scheme.primaryContainer.copy(alpha=.35f) else scheme.surfaceContainer,
        border=BorderStroke(1.dp,if(selected) scheme.primary.copy(alpha=.4f) else scheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,top=14.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ServerGlyph(host.icon,Modifier.size(30.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(host.label,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(host.endpoint,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=scheme.onSurfaceVariant)
                Text(if(host.jumpOnly) "SSH only · tap to edit" else when(status.phase) {
                    "connected"->if(selected) "Connected · viewing" else "Connected"
                    "connecting"->"Connecting…";"reconnecting"->"Reconnecting…";"paused"->"Paused"
                    "error"->"Connection failed · tap to retry";else->"Saved · tap to connect"
                },style=MaterialTheme.typography.labelSmall,color=if(status.connected) scheme.primary else scheme.onSurfaceVariant)
                host.ssh?.jumpHost?.let {Text("Via ${it.user}@${it.host}",style=MaterialTheme.typography.labelSmall,color=scheme.primary)}
                if(status.error.isNotBlank()) Text(status.error,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=scheme.error)
            }
            Box {
                ChromeButton(ChromeIcon.MORE,"Host actions for ${host.label}") {menu=true}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("Edit host")},onClick={menu=false;onEdit(host)})
                    if(!host.jumpOnly && status.maintained) DropdownMenuItem(text={Text("Disconnect")},onClick={menu=false;onDisconnect(host)})
                    DropdownMenuItem(text={Text("Forget host")},onClick={menu=false;onForget(host)})
                }
            }
        }
    }
}
