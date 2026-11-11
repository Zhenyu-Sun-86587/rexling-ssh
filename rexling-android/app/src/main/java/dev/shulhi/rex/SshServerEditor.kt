package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
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

@Composable internal fun SshField(
    value: String,onValueChange: (String)->Unit,modifier: Modifier=Modifier,title: String,
    singleLine: Boolean=true,minLines: Int=1,maxLines: Int=if(singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions=KeyboardOptions.Default,
    visualTransformation: VisualTransformation=VisualTransformation.None,enabled: Boolean=true,
) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value,onValueChange,Modifier.fillMaxWidth(),singleLine=singleLine,
            minLines=minLines,maxLines=maxLines,keyboardOptions=keyboardOptions,visualTransformation=visualTransformation,enabled=enabled)
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
            SshChoiceRow(listOf("HTTP / WS","SSH"),if(mode=="ssh") 1 else 0,!busy) {mode=if(it==1) "ssh" else "direct"}
            ServerIconPicker(icon) {icon=it}
            SshField(name,{name=it},Modifier.fillMaxWidth(),title="Name",singleLine=true,enabled=!busy)
            if(mode=="direct") {
                SshField(endpoint,{endpoint=it;error=null},Modifier.fillMaxWidth(),title="Server URL",singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),enabled=!busy)
            } else {
                SshField(host,{host=it;error=null},Modifier.fillMaxWidth(),title="Host",singleLine=true,enabled=!busy)
                SshField(user,{user=it},Modifier.fillMaxWidth(),title="Username",singleLine=true,enabled=!busy)
                SshChoiceRow(listOf("Direct","IPv6","Jump"),if(useJump) 2 else if(ipv6Direct) 1 else 0,!busy) {
                    useJump=it==2;ipv6Direct=it==1;if(useJump) advanced=true
                }
                TextButton(onClick={advanced=!advanced},enabled=!busy) {Text(if(advanced) "Hide advanced options" else "Advanced options")}
                if(advanced) SshField(port,{port=it},Modifier.fillMaxWidth(),title="SSH port",singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                Text("Target authentication",style=MaterialTheme.typography.labelLarge)
                SshAuthentication(auth,busy,{busy=it},{error=it},advanced=advanced)
                if(advanced) {
                    if(useJump) {
                        Text("Jump host",style=MaterialTheme.typography.labelLarge)
                        SshField(jumpHost,{jumpHost=it},Modifier.fillMaxWidth(),title="Host",singleLine=true,enabled=!busy)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SshField(jumpPort,{jumpPort=it},Modifier.width(95.dp),title="Port",singleLine=true,
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy)
                            SshField(jumpUser,{jumpUser=it},Modifier.weight(1f),title="Username",singleLine=true,enabled=!busy)
                        }
                        SshAuthentication(jumpAuth,busy,{busy=it},{error=it},advanced=true)
                    }
                    SshField(socket,{socket=it},Modifier.fillMaxWidth(),title="Socket path (optional)",
                        singleLine=true,enabled=!busy)
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
