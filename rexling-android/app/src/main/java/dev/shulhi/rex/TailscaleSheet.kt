package dev.shulhi.rex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TailscaleSheet(onDismiss: ()->Unit,onChoose: (TailscaleDevice)->Unit) {
    val context=LocalContext.current
    val uri=LocalUriHandler.current
    val scope=rememberCoroutineScope()
    var apiKey by remember {mutableStateOf("")}
    var tailnet by remember {mutableStateOf("")}
    var rememberKey by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    var devices by remember {mutableStateOf<List<TailscaleDevice>?>(null)}
    var search by remember {mutableStateOf("")}
    LaunchedEffect(Unit) {
        try {
            val saved=withContext(Dispatchers.IO) {
                runCatching {
                    val bytes=SshCredentials(context).load(TailscaleDiscovery.CREDENTIAL_ID)
                    try {JSONObject(bytes.toString(Charsets.UTF_8))} finally {bytes.fill(0)}
                }.getOrNull()
            }
            if(saved!=null) {apiKey=saved.optString("apiKey");tailnet=saved.optString("tailnet");rememberKey=true}
        } finally {busy=false}
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=720.dp).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Discover your tailnet",style=MaterialTheme.typography.headlineSmall)
            Text("Find a device, then set up its SSH login. Discovery does not connect this phone to Tailscale or verify Rex.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            SshField(apiKey,{apiKey=it},title="Tailscale API key",visualTransformation=PasswordVisualTransformation(),enabled=!busy)
            TextButton(onClick={uri.openUri("https://login.tailscale.com/admin/settings/keys")}) {Text("Create an API key in Tailscale ↗")}
            SshField(tailnet,{tailnet=it},title="Tailnet ID (optional; blank uses your default)",enabled=!busy)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(rememberKey,{rememberKey=it},enabled=!busy)
                Text("Remember API key encrypted on this device",style=MaterialTheme.typography.bodySmall)
            }
            Text("Treat this as a sensitive management key, not a read-only password. Keep the Tailscale VPN connected on your phone. Keyless Tailscale SSH login is not supported here; use your regular SSH login or an existing Rex URL.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            Button(onClick={scope.launch {
                busy=true;error=null
                try {
                    val result=TailscaleDiscovery().devices(apiKey,tailnet)
                    withContext(Dispatchers.IO) {
                        val store=SshCredentials(context)
                        if(rememberKey) {
                            val bytes=JSONObject().put("apiKey",apiKey.trim()).put("tailnet",tailnet.trim()).toString().toByteArray(Charsets.UTF_8)
                            try {store.save(TailscaleDiscovery.CREDENTIAL_ID,bytes)} finally {bytes.fill(0)}
                        } else store.delete(TailscaleDiscovery.CREDENTIAL_ID)
                    }
                    devices=result
                } catch(e: kotlinx.coroutines.CancellationException) {throw e}
                catch(e: java.io.IOException) {error="Could not reach Tailscale. Check your connection and try again."}
                catch(e: org.json.JSONException) {error="Tailscale returned an invalid device list. Previously found devices are unchanged."}
                catch(e: Exception) {error=e.message ?: "Could not discover devices"}
                finally {busy=false}
            }},enabled=!busy && apiKey.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text(if(devices==null) "Find devices" else "Refresh devices")}
            devices?.let {found->
                Text("${found.size} devices · control-plane status, not SSH verification",style=MaterialTheme.typography.labelMedium)
                SshField(search,{search=it},title="Search devices")
                TailscaleDeviceList(found.filter {it.label.contains(search,true) || it.dnsName.contains(search,true) || it.address.contains(search)},onChoose)
            }
            TextButton(onClick={
                SshCredentials(context).delete(TailscaleDiscovery.CREDENTIAL_ID)
                apiKey="";tailnet="";rememberKey=false;devices=null;error=null
            },enabled=!busy) {Text("Forget API key")}
            TextButton(onClick=onDismiss) {Text("Close")}
        }
    }
}

@Composable internal fun TailscaleDeviceList(devices: List<TailscaleDevice>,onChoose: (TailscaleDevice)->Unit) {
    if(devices.isEmpty()) Text("No matching devices.",style=MaterialTheme.typography.bodyMedium)
    devices.forEach {device->
        Surface(onClick={onChoose(device)},enabled=device.authorized && !device.blocksIncoming,
            shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(device.label,style=MaterialTheme.typography.titleSmall)
                Text("${device.address} · ${device.os}",style=MaterialTheme.typography.bodySmall)
                Text(when { !device.authorized->"Device not authorized in tailnet";device.blocksIncoming->"Device blocks incoming connections"
                    device.online==true->"Online in Tailscale";device.online==false->"Offline in Tailscale";else->"Online status unknown"},style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(device.sshEnabled) Text("Tailscale SSH enabled · keyless login not supported",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
