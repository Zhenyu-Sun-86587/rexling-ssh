package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable internal fun ServerEditor(server: ServerProfile?,model: RexModel,onClose: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val id=remember {server?.id ?: UUID.randomUUID().toString()}
    val original=server?.ssh
    var mode by remember {mutableStateOf(if(original!=null) "ssh" else "direct")}
    var name by remember {mutableStateOf(server?.label.orEmpty())}
    var endpoint by remember {mutableStateOf(if(original==null) server?.endpoint.orEmpty() else "")}
    var icon by remember {mutableStateOf(server?.icon ?: ServerIcon.GLOBE)}
    var host by remember {mutableStateOf(original?.host.orEmpty())}
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
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text(if(server==null) "Add server" else "Edit server")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=mode=="direct",onClick={mode="direct"},label={Text("Direct (HTTP/WS)")},enabled=!busy)
                FilterChip(selected=mode=="ssh",onClick={mode="ssh"},label={Text("SSH")},enabled=!busy)
            }
            ServerIconPicker(icon) {icon=it}
            OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),label={Text("Name")},singleLine=true,enabled=!busy)
            if(mode=="direct") {
                OutlinedTextField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),label={Text("Server URL")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),enabled=!busy)
                Text("Use an authenticated Rex HTTP/WS endpoint, such as its Tailscale HTTPS address.",fontSize=11.sp)
            } else {
                OutlinedTextField(host,{host=it;error=null},Modifier.fillMaxWidth(),label={Text("Host")},singleLine=true,enabled=!busy)
                OutlinedTextField(user,{user=it},Modifier.fillMaxWidth(),label={Text("Username")},singleLine=true,enabled=!busy)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!useJump && !ipv6Direct,{useJump=false;ipv6Direct=false},label={Text("Direct")},enabled=!busy)
                    FilterChip(ipv6Direct,{ipv6Direct=true;useJump=false},label={Text("IPv6 direct")},enabled=!busy)
                    FilterChip(useJump,{useJump=true;ipv6Direct=false;advanced=true},label={Text("ProxyJump",maxLines=1,softWrap=false)},enabled=!busy)
                }
                Text(if(ipv6Direct) "Connect directly to the host's IPv6 address or AAAA record. Your network must provide IPv6 connectivity."
                    else "Direct uses IPv4 and IPv6 automatically. For an IPv6 address with a port, use [address]:port.",fontSize=11.sp)
                TextButton(onClick={advanced=!advanced},enabled=!busy) {Text(if(advanced) "Hide advanced options" else "Advanced options")}
                if(advanced) OutlinedTextField(port,{port=it},Modifier.fillMaxWidth(),label={Text("SSH port")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                Text("Target authentication",style=MaterialTheme.typography.labelLarge)
                SshAuthentication(auth,busy,{busy=it},{error=it},advanced=advanced)
                if(advanced) {
                    if(useJump) {
                        Text("Jump host",style=MaterialTheme.typography.labelLarge)
                        OutlinedTextField(jumpHost,{jumpHost=it},Modifier.fillMaxWidth(),label={Text("Jump host address")},singleLine=true,enabled=!busy)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(jumpPort,{jumpPort=it},Modifier.width(95.dp),label={Text("Port")},singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                            OutlinedTextField(jumpUser,{jumpUser=it},Modifier.weight(1f),label={Text("Jump username")},singleLine=true,enabled=!busy)
                        }
                        SshAuthentication(jumpAuth,busy,{busy=it},{error=it},advanced=true)
                        Text("The jump host resolves and connects to the target address. Target SSH remains encrypted end to end.",fontSize=11.sp)
                    }
                    OutlinedTextField(socket,{socket=it},Modifier.fillMaxWidth(),label={Text("Remote socket path · optional")},
                        placeholder={Text("/run/user/<uid>/rex/server.sock",fontSize=11.sp)},singleLine=true,enabled=!busy)
                    Text("Connects to Rex's Unix socket through SSH. Saved credentials are encrypted with Android Keystore.",fontSize=11.sp)
                    if(original!=null) Row {
                        Checkbox(resetTrust,{resetTrust=it},enabled=!busy)
                        Text("Reset host trust for configured hops on save",Modifier.padding(top=12.dp),fontSize=11.sp)
                    }
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        }
    },confirmButton={
        Button(enabled=!busy,onClick={
            if(mode=="direct") {
                error=model.saveServer(server?.id,name,endpoint,icon)
                if(error==null) onClose()
            } else {
                scope.launch {
                    busy=true
                    try {
                        val address=SshAddress.parse(host,port.toIntOrNull() ?: error("Enter a valid SSH port"))
                        val cleanHost=address.host
                        val cleanUser=user.trim()
                        val number=address.port
                        auth.validate("Target")
                        val jump=if(useJump) {
                            jumpAuth.validate("Jump host")
                            val jumpAddress=SshAddress.parse(jumpHost,jumpPort.toIntOrNull() ?: error("Enter a valid jump host port"))
                            SshJumpHost(jumpAddress.host,jumpAddress.port,jumpUser.trim(),jumpAuth.method)
                        } else null
                        val ssh=SshOptions(cleanHost,number,cleanUser,auth.method,"ssh:"+UUID.randomUUID(),socket.trim(),jumpHost=jump,addressFamily=if(ipv6Direct) "ipv6" else "auto")
                        val url=URI("ssh",cleanUser,cleanHost,number,null,null,null).toASCIIString()
                        val profile=ServerProfile.create(name,url,id,icon,ssh)
                        val payload=auth.json()
                        if(useJump) payload.put("jumpHost",jumpAuth.json())
                        val bytes=payload.toString().toByteArray(Charsets.UTF_8)
                        error=model.saveSshServer(profile,bytes,resetTrust)
                        if(error==null) {auth.clear();jumpAuth.clear();onClose()}
                    } catch(e: Exception) {error=e.message}
                    finally {busy=false}
                }
            }
        }) {Text("Save & connect")}
    },dismissButton={TextButton(onClick=onClose,enabled=!busy) {Text("Cancel")}})
}
