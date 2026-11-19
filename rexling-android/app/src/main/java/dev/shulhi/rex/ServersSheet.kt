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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServersSheet(model: RexModel,hazeState: dev.chrisbanes.haze.HazeState?=null,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.background) {
        HostsPanel(model,onDismiss,Modifier.fillMaxWidth().heightIn(max=720.dp))
    }
}

@Composable internal fun HostsPanel(model: RexModel,onOpen: ()->Unit,modifier: Modifier=Modifier) {
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    var editor by remember {mutableStateOf(false)}
    var tailscale by remember {mutableStateOf(false)}
    var suggestion by remember {mutableStateOf<TailscaleDevice?>(null)}
    var forgetting by remember {mutableStateOf<ServerProfile?>(null)}
    var forgetError by remember {mutableStateOf<String?>(null)}
    fun edit(host: ServerProfile?) {editing=host;suggestion=null;editor=true}
    HostsContent(model.servers,model.serverStatuses,model.activeServerId,
        onAdd={edit(null)},onTailscale={tailscale=true},
        onSelect={host->if(!host.canConnect) edit(host) else {model.connectServer(host.id);onOpen()}},
        onEdit={edit(it)},onDisconnect={model.disconnectServer(it.id)},onForget={forgetError=null;forgetting=it},modifier=modifier)
    if(editor) {
        val id=editing?.id
        ServerEditor(editing,onClose={editor=false},onSave={label,url,icon,_ ->
            model.saveServer(id,label,url,icon).also {if(it==null) onOpen()}
        },suggestion=suggestion,hosts=model.servers,onSaveOnly={label,url,icon,relayOnly->model.saveServer(id,label,url,icon,connect=false,relayOnly=relayOnly)})
    }
    if(tailscale) TailscaleSheet(onDismiss={tailscale=false},onChoose={device->
        tailscale=false;suggestion=device;editing=null;editor=true
    })
    forgetting?.let {host->AlertDialog(onDismissRequest={forgetting=null},title={Text("Forget ${host.label}?")},
        text={Text(forgetError ?: "Remove this saved host from this device. Keys in your SSH key library are kept.")},
        confirmButton={TextButton(onClick={forgetError=model.forgetServer(host.id);if(forgetError==null) forgetting=null}) {Text("Forget")}},
        dismissButton={TextButton(onClick={forgetting=null}) {Text("Cancel")}})}
}

@Composable internal fun HostsContent(
    servers: List<ServerProfile>,statuses: Map<String,ServerStatus>,activeId: String,
    onAdd: ()->Unit,onTailscale: ()->Unit,onSelect: (ServerProfile)->Unit,
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
            Text("Connect to Rex over SSH or HTTPS on your phone’s network or with built-in Tailscale.",style=MaterialTheme.typography.bodyMedium,color=scheme.onSurfaceVariant)
            HostEntryCard("Rex server","Enter an SSH or HTTPS URL. Save first, or connect now.","Add server",onAdd,true)
            HostEntryCard("Tailscale devices","Find a device on your tailnet and set it up in the same server editor.","Discover devices",onTailscale,false)
        } else {
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Search hosts")},singleLine=true)
            val matching=servers.filter {search.isBlank() || it.label.contains(search,true) || it.endpoint.contains(search,true)}
            for((heading,group) in listOf(
                "Rex hosts" to matching.filter {it.canConnect},
                "SSH jump hosts" to matching.filter {it.relayOnly && !it.needsSshSetup},
                "Old SSH hosts · setup required" to matching.filter {it.needsSshSetup},
            )) {
                if(group.isNotEmpty()) {
                    Text(heading,style=MaterialTheme.typography.labelLarge,color=scheme.onSurfaceVariant)
                    group.forEach {host->HostRow(host,statuses[host.id] ?: ServerStatus(),host.id==activeId,onSelect,onEdit,onDisconnect,onForget)}
                }
            }
            if(matching.isEmpty()) Text("No matching hosts.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick=onTailscale,modifier=Modifier.fillMaxWidth()) {Text("Discover Tailscale devices")}
        }
    }
}

@Composable internal fun ServerEditor(server: ServerProfile?,onClose: ()->Unit,onSave: (String,String,ServerIcon,Boolean)->String?,
    suggestion: TailscaleDevice?=null,hosts: List<ServerProfile> = emptyList(),onSaveOnly: ((String,String,ServerIcon,Boolean)->String?)?=null) {
    val networkStatus by NetworkSettings.status.collectAsState()
    val tailnetConnected=networkStatus.optJSONObject("tailscale")?.optString("BackendState")=="Running"
    var name by remember(server?.id,suggestion?.id) {mutableStateOf(server?.label ?: suggestion?.label.orEmpty())}
    var endpoint by remember(server?.id,suggestion?.id) {mutableStateOf(server?.endpoint ?: suggestion?.let {
        java.net.URI("ssh","",it.address,-1,null,null,null).toASCIIString()
    }.orEmpty())}
    var icon by remember(server?.id) {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var error by remember {mutableStateOf<String?>(null)}
    var route by remember(server?.id) {mutableStateOf(NetworkSettings.route(server?.endpoint.orEmpty()))}
    var selectedKey by remember(server?.id) {mutableStateOf(NetworkSettings.selectedKey(server?.endpoint.orEmpty()))}
    var relayOnly by remember(server?.id) {mutableStateOf(server?.relayOnly==true)}
    var jump by remember(server?.id) {mutableStateOf(server?.let {NetworkSettings.jumpEndpoint(it.endpoint)})}
    var jumpOpen by remember {mutableStateOf(false)}
    var routesOpen by remember {mutableStateOf(false)}
    var networkOpen by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val normalized=runCatching {ServerProfile.normalizeEndpoint(endpoint)}.getOrNull()
    val ssh=normalized?.startsWith("ssh://")==true
    val jumpOnly=ssh && relayOnly
    val chosenJump=jump.takeIf {ssh && !jumpOnly}
    val usedAsJump=server?.let {NetworkSettings.isUsedAsJump(it.endpoint)}==true
    fun save(action: (String,String,ServerIcon,Boolean)->String?) {
        val profile=runCatching {ServerProfile.create(name,endpoint,icon=icon)}.getOrElse {error=it.message;return}
        if(hosts.any {it.id!=server?.id && it.endpoint==profile.endpoint && !it.needsSshSetup}) {error="That server is already saved";return}
        if(usedAsJump && server?.endpoint!=profile.endpoint) {error="Remove this jump host from its targets before changing its URL";return}
        if(jumpOnly && !java.net.URI(profile.endpoint).path.isNullOrEmpty()) {error="A jump-only host needs ssh://user@host without a Rex socket path";return}
        if(chosenJump!=null && hosts.none {it.endpoint==chosenJump && !it.needsSshSetup}) {error="Choose a saved SSH host before saving";return}
        val requestedRoute=route
        val requestedKey=selectedKey
        busy=true
        scope.launch {
            val previous=NetworkSettings.route(profile.endpoint)
            val previousKey=NetworkSettings.selectedKey(profile.endpoint)
            val previousJump=NetworkSettings.jumpEndpoint(profile.endpoint)
            error=runCatching {
                NetworkSettings.saveHost(profile.endpoint,requestedRoute,requestedKey.takeIf {ssh},chosenJump) ?:
                    action(profile.label,profile.endpoint,profile.icon,jumpOnly)?.also {
                        NetworkSettings.saveHost(profile.endpoint,previous,previousKey,previousJump)
                    }
            }.getOrElse {it.message ?: "Could not save server"}
            busy=false;if(error==null) onClose()
        }
    }
    if(networkOpen) NetworkSheet(onClose={networkOpen=false})
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text(if(server==null) "Add server" else "Edit server",style=SettingsType.title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                ServerIconPicker(icon,compact=true) {icon=it}
                OutlinedTextField(name,{name=it;error=null},Modifier.weight(1f),label={Text("Name")},singleLine=true,textStyle=SettingsType.body)
            }
            OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text("Server URL")},singleLine=true,textStyle=SettingsType.body,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Done),placeholder={Text("https://host.tailnet.ts.net",fontSize=11.sp)})
            if(server?.needsSshSetup==true) Text("Set up this host again. Old SSH credentials and trust are not reused.",style=SettingsType.detail,color=MaterialTheme.colorScheme.primary)
            Text(when {
                suggestion!=null && endpoint.startsWith("ssh://@") -> "Enter the remote username before @. The API key only discovers devices."
                jumpOnly -> "Assign an SSH key for forwarding. This host doesn’t need Rex installed."
                endpoint.startsWith("ssh://") -> "Use ssh://user@host. SSH may start Rex if it’s installed on the host."
                else -> "Use an HTTPS address, or ssh://user@host."
            },style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(ssh && onSaveOnly!=null) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Jump host only",Modifier.weight(1f),style=SettingsType.row)
                    Switch(relayOnly,{relayOnly=it;error=null},enabled=!busy,modifier=Modifier.semantics {contentDescription="Jump host only"})
                }
                if(!jumpOnly && !usedAsJump) {
                    Text("SSH jump host",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val jumpName=hosts.firstOrNull {it.endpoint==chosenJump}?.label ?: chosenJump
                    OutlinedButton(onClick={jumpOpen=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                        Text(jumpName?.let {"Via $it"} ?: "Choose a saved SSH host")
                    }
                    if(chosenJump!=null) Text("Uses the jump host’s network. The target still uses its own SSH key.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } else if(usedAsJump) Text("Other hosts use this SSH login as a jump host.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(normalized!=null && chosenJump==null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f)).padding(12.dp)) {
                Text("Connection network",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(enabled=!busy,onClick={routesOpen=true},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) {Text(route.label,style=SettingsType.row);Text(" ▾")}
                    DropdownMenu(expanded=routesOpen,onDismissRequest={routesOpen=false}) {
                        ServerNetworkRoute.entries.forEach {choice->DropdownMenuItem(text={Text(choice.label,style=SettingsType.row)},onClick={route=choice;routesOpen=false})}
                    }
                }
                if(route==ServerNetworkRoute.BUILTIN) {
                    Text("Use a tailnet IP or device name. No Tailscale VPN app required.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick={networkOpen=true},contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) {Text(if(tailnetConnected) "Connected" else "Set up / sign in",style=SettingsType.detail)}
                } else Text("Use this phone’s network and existing VPN.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(ssh) SshKeyEditor(selectedKey) {selectedKey=it}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={Button(onClick={save(if(jumpOnly && onSaveOnly!=null) onSaveOnly else onSave)},enabled=endpoint.isNotBlank() && !busy) {
        Text(if(busy) "Saving…" else if(jumpOnly) "Save only" else "Save & connect")
    }},
        dismissButton={
            TextButton(enabled=!busy,onClick=onClose) {Text("Cancel")}
            if(onSaveOnly!=null && !jumpOnly) TextButton(enabled=endpoint.isNotBlank() && !busy,onClick={save(onSaveOnly)}) {Text("Save only")}
        })
    if(jumpOpen && normalized!=null) JumpHostPicker(hosts.filter {
        it.id!=server?.id && !it.needsSshSetup && it.endpoint.startsWith("ssh://") && !sameSshHost(normalized,it.endpoint) && NetworkSettings.jumpEndpoint(it.endpoint)==null
    },chosenJump,onClose={jumpOpen=false},onSelect={jump=it;error=null;jumpOpen=false})
}

@Composable private fun JumpHostPicker(hosts: List<ServerProfile>,selected: String?,onClose: ()->Unit,onSelect: (String?)->Unit) {
    var search by remember {mutableStateOf("")}
    val matching=hosts.filter {it.label.contains(search,true) || it.endpoint.contains(search,true)}
    AlertDialog(onDismissRequest=onClose,title={Text("Choose jump host",style=SettingsType.title)},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Search saved SSH hosts")},singleLine=true)
            Column(Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState())) {
                if(hosts.isEmpty()) Text("Save a direct SSH host first. It doesn’t need Rex installed.",style=SettingsType.detail)
                else if(matching.isEmpty()) Text("No matching SSH hosts.",style=SettingsType.detail)
                matching.forEach {host->
                    Surface(onClick={onSelect(host.endpoint)},shape=RoundedCornerShape(12.dp),color=if(host.endpoint==selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(host.label,style=SettingsType.row)
                            Text(host.endpoint,style=SettingsType.detail,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onClose) {Text("Cancel")}},dismissButton={
        if(selected!=null) TextButton(onClick={onSelect(null)}) {Text("Use direct SSH")}
    })
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
                Text(if(host.needsSshSetup) "Setup required · tap to edit" else if(host.relayOnly) "Jump only · tap to edit" else when(status.phase) {
                    "connected"->if(selected) "Connected · viewing" else "Connected"
                    "connecting"->"Connecting…";"reconnecting"->"Reconnecting…";"paused"->"Paused"
                    "error"->"Connection failed · tap to retry";else->"Saved · tap to connect"
                },style=MaterialTheme.typography.labelSmall,color=if(status.connected) scheme.primary else scheme.onSurfaceVariant)
                if(status.error.isNotBlank()) Text(status.error,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=scheme.error)
            }
            Box {
                ChromeButton(ChromeIcon.MORE,"Host actions for ${host.label}") {menu=true}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("Edit host")},onClick={menu=false;onEdit(host)})
                    if(host.canConnect && status.maintained) DropdownMenuItem(text={Text("Disconnect")},onClick={menu=false;onDisconnect(host)})
                    DropdownMenuItem(text={Text("Forget host")},onClick={menu=false;onForget(host)})
                }
            }
        }
    }
}
