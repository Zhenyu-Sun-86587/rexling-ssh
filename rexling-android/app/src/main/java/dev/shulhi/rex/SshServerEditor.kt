package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@Composable internal fun SshField(
    value: String,onValueChange: (String)->Unit,modifier: Modifier=Modifier,title: String,
    singleLine: Boolean=true,minLines: Int=1,maxLines: Int=if(singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions=KeyboardOptions.Default,
    visualTransformation: VisualTransformation=VisualTransformation.None,enabled: Boolean=true,
) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value,onValueChange,Modifier.fillMaxWidth().semantics {contentDescription=title},singleLine=singleLine,
            minLines=minLines,maxLines=maxLines,keyboardOptions=keyboardOptions,visualTransformation=visualTransformation,enabled=enabled,
            shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

@Composable internal fun SshChoiceRow(labels: List<String>,selected: Int,enabled: Boolean,onSelect: (Int)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed {index,label->
            FilterChip(selected==index,{onSelect(index)},modifier=Modifier.weight(1f).widthIn(min=64.dp),enabled=enabled,
                label={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {Text(label,fontSize=12.sp,maxLines=1,softWrap=false)}},
                border=FilterChipDefaults.filterChipBorder(enabled,selected==index))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServerEditor(server: ServerProfile?,model: RexModel,initialMode: String="ssh",suggestedHost: String="",suggestedName: String="",onClose: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val id=remember {server?.id ?: UUID.randomUUID().toString()}
    val original=server?.ssh
    var mode by remember {mutableStateOf(if(server!=null) {if(original!=null) "ssh" else "direct"} else initialMode)}
    var name by remember {mutableStateOf(server?.label ?: suggestedName)}
    var endpoint by remember {mutableStateOf(if(original==null) server?.endpoint.orEmpty() else "")}
    var icon by remember {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var host by remember {mutableStateOf(original?.host ?: suggestedHost)}
    var port by remember {mutableStateOf((original?.port ?: 22).toString())}
    var user by remember {mutableStateOf(original?.user.orEmpty())}
    val auth=remember {SshAuthState(original?.authMethod ?: "key")}
    var socket by remember {mutableStateOf(original?.socketPath.orEmpty())}
    var useJump by remember {mutableStateOf(original?.jumpHost!=null)}
    var ipv6Direct by remember {mutableStateOf(original?.addressFamily=="ipv6")}
    var advanced by remember {mutableStateOf(false)}
    var jumpHost by remember {mutableStateOf(original?.jumpHost?.host.orEmpty())}
    var jumpPort by remember {mutableStateOf((original?.jumpHost?.port ?: 22).toString())}
    var jumpUser by remember {mutableStateOf(original?.jumpHost?.user.orEmpty())}
    val jumpAuth=remember {SshAuthState(original?.jumpHost?.authMethod ?: "key")}
    var resetTrust by remember {mutableStateOf(false)}
    var jumpOnly by remember {mutableStateOf(server?.jumpOnly ?: false)}
    var chooseJump by remember {mutableStateOf(false)}
    var manualJump by remember {mutableStateOf(false)}
    var selectedJumpName by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(original!=null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(original?.credentialRef) {
        if(original!=null) {
            try {
                val stored=withContext(Dispatchers.IO) {
                    val bytes=SshCredentials(context).load(original.credentialRef)
                    try {JSONObject(bytes.toString(Charsets.UTF_8))} finally {bytes.fill(0)}
                }
                auth.restore(stored);jumpAuth.restore(stored.optJSONObject("jumpHost"))
            } catch(e: Exception) {error=e.message ?: "Could not unlock saved credentials"}
            finally {busy=false}
        }
    }
    DisposableEffect(Unit) {onDispose {auth.clear();jumpAuth.clear()}}
    fun save(connect: Boolean) {
        if(mode=="direct") {
            error=model.saveServer(server?.id,name,endpoint,icon,connect)
            if(error==null) onClose()
        } else scope.launch {
            busy=true
            try {
                val address=SshAddress.parse(host,port.toIntOrNull() ?: error("Enter a valid SSH port"))
                val cleanUser=user.trim()
                auth.validate("Host")
                val jump=if(useJump) {
                    jumpAuth.validate("Jump host")
                    val jumpAddress=SshAddress.parse(jumpHost,jumpPort.toIntOrNull() ?: error("Enter a valid jump host port"))
                    require(jumpAddress.host!=address.host || jumpAddress.port!=address.port || jumpUser.trim()!=cleanUser) {"A host cannot be its own jump host"}
                    SshJumpHost(jumpAddress.host,jumpAddress.port,jumpUser.trim(),jumpAuth.method)
                } else null
                val ssh=SshOptions(address.host,address.port,cleanUser,auth.method,"ssh:"+UUID.randomUUID(),socket.trim(),jumpHost=jump,addressFamily=if(ipv6Direct) "ipv6" else "auto")
                val url=URI("ssh",cleanUser,address.host,address.port,null,null,null).toASCIIString()
                val profile=ServerProfile.create(name,url,id,icon,ssh,jumpOnly)
                val payload=auth.json()
                if(useJump) payload.put("jumpHost",jumpAuth.json())
                error=model.saveSshServer(profile,payload.toString().toByteArray(Charsets.UTF_8),resetTrust,connect && !jumpOnly)
                if(error==null) onClose()
            } catch(e: kotlinx.coroutines.CancellationException) {throw e}
            catch(e: Exception) {error=e.message ?: "Could not save host"}
            finally {busy=false}
        }
    }
    Dialog(onDismissRequest={if(!busy) onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
      Scaffold(modifier=Modifier.fillMaxSize().imePadding(),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(if(server==null) "New host" else "Edit host")},navigationIcon={TextButton(onClick=onClose,enabled=!busy) {Text("Back")}})},
        bottomBar={Surface(tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick={save(false)},enabled=!busy,modifier=Modifier.weight(1f)) {Text("Save only")}
                    if(!jumpOnly || mode=="direct") Button(onClick={save(true)},enabled=!busy,modifier=Modifier.weight(1f)) {Text("Save & connect")}
                }
            }
        }}) {insets->
        Column(Modifier.padding(insets).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Save a connection now. Verify it when you connect.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SshChoiceRow(listOf("SSH","Rex URL"),if(mode=="ssh") 0 else 1,!busy) {mode=if(it==0) "ssh" else "direct"}
            ServerIconPicker(icon) {icon=it}
            SshField(name,{name=it},Modifier.fillMaxWidth(),title="Name",singleLine=true,enabled=!busy)
            if(mode=="direct") {
                SshField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),title="Server URL",singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),enabled=!busy)
            } else {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    SshField(host,{host=it;error=null},Modifier.weight(1f),title="Host",singleLine=true,enabled=!busy)
                    SshField(port,{port=it},Modifier.width(90.dp),title="Port",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                }
                SshField(user,{user=it},Modifier.fillMaxWidth(),title="Username",singleLine=true,enabled=!busy)
                SshChoiceRow(listOf("Rex host","Jump host only"),if(jumpOnly) 1 else 0,!busy) {jumpOnly=it==1;if(jumpOnly) useJump=false}
                Text(if(jumpOnly) "SSH settings only. No Rex server needed; this host will not auto-connect." else "Rex must be running on the target, not on its jump host.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Authentication",style=MaterialTheme.typography.titleSmall)
                SshAuthentication(auth,busy,{busy=it},{error=it},advanced=advanced)
                if(!jumpOnly) SshRouteCard(useJump,selectedJumpName.ifBlank {"Jump host"},
                    if(jumpHost.isBlank()) "" else "$jumpUser@$jumpHost:$jumpPort",if(host.isBlank()) "This host" else "$user@$host",!busy,
                    onToggle={useJump=it;if(it) ipv6Direct=false},onChoose={chooseJump=true},onManual={manualJump=!manualJump})
                if(useJump && manualJump) {
                    SshField(jumpHost,{jumpHost=it;selectedJumpName=""},title="Jump host address",enabled=!busy)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        SshField(jumpPort,{jumpPort=it},Modifier.width(90.dp),title="Jump port",keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                        SshField(jumpUser,{jumpUser=it},Modifier.weight(1f),title="Jump username",enabled=!busy)
                    }
                    SshAuthentication(jumpAuth,busy,{busy=it},{error=it},advanced=advanced)
                }
                TextButton(onClick={advanced=!advanced},enabled=!busy) {Text(if(advanced) "Hide advanced options" else "Advanced options")}
                if(advanced) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(ipv6Direct,{ipv6Direct=it},enabled=!busy && !useJump)
                        Text("IPv6 only (direct connection)",style=MaterialTheme.typography.bodySmall)
                    }
                    if(!jumpOnly) SshField(socket,{socket=it},Modifier.fillMaxWidth(),title="Socket path (optional)",
                        singleLine=true,enabled=!busy)
                    if(original!=null) Row {
                        Checkbox(resetTrust,{resetTrust=it},enabled=!busy)
                        Text("Reset host trust for configured hops on save",Modifier.padding(top=12.dp),fontSize=11.sp)
                    }
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      }
    }
    if(chooseJump) JumpHostPicker(jumpHostChoices(model.servers,id),onDismiss={chooseJump=false},onSelect={source->
        chooseJump=false
        scope.launch {
            busy=true
            try {
                val ssh=source.ssh!!
                val stored=withContext(Dispatchers.IO) {
                    val bytes=SshCredentials(context).load(ssh.credentialRef)
                    try {JSONObject(bytes.toString(Charsets.UTF_8))} finally {bytes.fill(0)}
                }
                jumpAuth.clear();jumpAuth.restore(stored)
                jumpHost=ssh.host;jumpPort=ssh.port.toString();jumpUser=ssh.user;selectedJumpName=source.label
                manualJump=false;error=null
            } catch(e: kotlinx.coroutines.CancellationException) {throw e}
            catch(e: Exception) {error=e.message ?: "Could not unlock jump host credentials"}
            finally {busy=false}
        }
    })
}

@Composable internal fun SshRouteCard(
    useJump: Boolean,jumpLabel: String,jumpEndpoint: String,target: String,enabled: Boolean,
    onToggle: (Boolean)->Unit,onChoose: ()->Unit,onManual: ()->Unit,
) {
    Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainer,
        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Connect through a jump host",style=MaterialTheme.typography.titleSmall)
                    Text(if(useJump) "One SSH hop · independent login" else "Off · connect directly from this device",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(useJump,onToggle,enabled=enabled,modifier=Modifier.semantics {contentDescription="Use jump host"})
            }
            if(useJump) {
                HorizontalDivider()
                Text("○  This device",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick=onChoose,enabled=enabled,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                        Text(if(jumpEndpoint.isBlank()) "+ Choose a saved SSH host" else jumpLabel)
                        if(jumpEndpoint.isNotBlank()) Text(jumpEndpoint,style=MaterialTheme.typography.bodySmall)
                    }
                }
                Text("◎  $target",style=MaterialTheme.typography.bodyMedium)
                Text("The jump host only needs SSH forwarding, not Rex. Saved settings are copied into this route; later edits to the source host do not change it.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=onManual,enabled=enabled) {Text("Enter or edit jump settings manually")}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun JumpHostPicker(hosts: List<ServerProfile>,onDismiss: ()->Unit,onSelect: (ServerProfile)->Unit) {
    var search by remember {mutableStateOf("")}
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Choose a jump host",style=MaterialTheme.typography.headlineSmall)
            Text("Saved SSH logins · no Rex server required",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SshField(search,{search=it},title="Search saved hosts")
            val matching=hosts.filter {search.isBlank() || it.label.contains(search,true) || it.endpoint.contains(search,true)}
            if(matching.isEmpty()) Text(if(hosts.isEmpty()) "No eligible SSH hosts yet. Save a direct SSH host first, or enter jump settings manually. Hosts with their own jump are excluded: one hop is supported." else "No matching SSH hosts.",style=MaterialTheme.typography.bodyMedium)
            matching.forEach {host->
                Surface(onClick={onSelect(host)},shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        ServerGlyph(host.icon,Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.label,style=MaterialTheme.typography.titleSmall)
                            Text(host.endpoint,style=MaterialTheme.typography.bodySmall)
                            Text(if(host.jumpOnly) "SSH only · Rex not required" else "Saved SSH login",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }
                        Text("Choose",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TextButton(onClick=onDismiss) {Text("Cancel")}
        }
    }
}
