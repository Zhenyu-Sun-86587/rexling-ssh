package dev.shulhi.rex

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NetworkSheet(server: ServerProfile?=null,serverConnected: Boolean=false,hazeState: dev.chrisbanes.haze.HazeState?=null,onClose: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val status by NetworkSettings.status.collectAsState()
    var control by remember {mutableStateOf(NetworkSettings.controlUrl)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val tailscale=status.optJSONObject("tailscale")
    val auth=tailscale?.optString("AuthURL").orEmpty()
    fun openLogin(url: String) {
        val uri=Uri.parse(url)
        require(uri.scheme in listOf("https","http") && !uri.host.isNullOrEmpty()) {"Tailscale returned an invalid sign-in address"}
        context.startActivity(Intent(Intent.ACTION_VIEW,uri))
    }
    fun signIn() {
        busy=true;error=null
        scope.launch {
            try {
                error=NetworkSettings.prepareBuiltIn(control)
                if(error==null) error=NetworkSettings.login()
                if(error==null) {
                    val url=withTimeoutOrNull(20_000) {
                        awaitTailscaleAuth {
                            NetworkSettings.refreshStatus()
                            NetworkSettings.status.value
                        }
                    }
                    if(url==null) error="Sign-in did not return a browser link. Check your connection and try again."
                    else if(url.isNotEmpty()) openLogin(url)
                }
            } catch(cancelled: kotlinx.coroutines.CancellationException) {throw cancelled}
            catch(failure: Exception) {error=failure.message ?: "Could not start Tailscale sign-in"}
            finally {busy=false}
        }
    }
    SettingsDrawer("Network","Close network settings",onClose,hazeState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(server!=null && server.endpoint.isNotBlank()) ServerNetworkStatusCard(server.label,NetworkSettings.route(server.endpoint),serverConnected)
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                TailscaleStatusCard(if(!NetworkSettings.tailscaleEnabled) "Off" else tailscale?.optString("BackendState").orEmpty(),busy,
                    peers=tailscalePeers(tailscale),networkError=if(NetworkSettings.tailscaleEnabled) status.optString("error") else "",onSignIn=::signIn)
                if(NetworkSettings.tailscaleEnabled && auth.isNotBlank() && tailscale?.optString("BackendState")!="Running") PendingTailscaleSignIn(busy,
                    onContinue={runCatching {openLogin(auth)}.onFailure {error=it.message}},
                    onCancel={busy=true;error=null;scope.launch {try {error=NetworkSettings.cancelLogin()} catch(failure: Exception) {error=failure.message} finally {busy=false}}})
            }
            Text("Choose the connection network separately in each server’s settings.",style=SettingsType.detail)
            OutlinedTextField(control,{control=it},Modifier.fillMaxWidth(),label={Text("Control server (optional)")},placeholder={Text("https://headscale.example.com")},singleLine=true)
            Text("Leave blank for Tailscale. Each control server keeps its own sign-in.",style=SettingsType.detail)
            Button(enabled=!busy,onClick={busy=true;scope.launch {error=NetworkSettings.prepareBuiltIn(control);busy=false}}) {Text(if(busy) "Applying…" else "Apply")}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=SettingsType.detail)}
        }
    }
}

@Composable internal fun PendingTailscaleSignIn(busy: Boolean,onContinue: ()->Unit,onCancel: ()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(enabled=!busy,onClick=onContinue) {Text("Continue in browser",style=SettingsType.detail)}
        TextButton(enabled=!busy,onClick=onCancel) {Text("Cancel sign-in",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

internal suspend fun awaitTailscaleAuth(readStatus: suspend ()->org.json.JSONObject): String {
    while(true) {
        val latest=readStatus()
        latest.optString("error").takeIf {it.isNotBlank()}?.let {throw IllegalStateException(it)}
        val state=latest.optJSONObject("tailscale")
        if(state?.optString("BackendState")=="Running") return ""
        state?.optString("AuthURL")?.takeIf {it.isNotBlank()}?.let {return it}
        delay(300)
    }
}

internal fun tailscaleStatusLabel(state: String)=when(state) {
    "Running"->"Connected";"NeedsLogin"->"Sign-in required";"NeedsMachineAuth"->"Device approval required"
    "Off"->"Not set up";"Stopped"->"Stopped";"Starting"->"Connecting…";else->"Not connected"
}

@Composable internal fun ServerNetworkStatusCard(name: String,route: ServerNetworkRoute,connected: Boolean) {
    SettingsSection("Current server") {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(name,style=SettingsType.row,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(route.label,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if(connected) "Connected" else "Disconnected",style=SettingsType.detail,color=if(connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal data class TailscalePeerStatus(val name: String,val route: String)
internal fun tailscalePeers(status: org.json.JSONObject?): List<TailscalePeerStatus> {
    val peers=status?.optJSONObject("Peer") ?: return emptyList()
    return peers.keys().asSequence().mapNotNull {id->
        val peer=peers.optJSONObject(id)?.takeIf {it.optBoolean("Active")} ?: return@mapNotNull null
        val name=peer.optString("HostName").ifBlank {peer.optString("DNSName").trimEnd('.')}.ifBlank {"Device"}
        val route=if(peer.optString("CurAddr").isNotBlank()) "Direct" else if(peer.optString("PeerRelay").isNotBlank()) "Peer relay" else "Tailscale relay"
        TailscalePeerStatus(name,route)
    }.sortedBy {it.name.lowercase()}.toList()
}

@Composable internal fun TailscaleStatusCard(state: String,busy: Boolean,peers: List<TailscalePeerStatus> = emptyList(),networkError: String="",onSignIn: ()->Unit) {
    SettingsSection("Built-in Tailscale") {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(tailscaleStatusLabel(state),style=SettingsType.row)
                Text(if(state=="Running") "Built-in Tailscale is ready" else if(state=="NeedsMachineAuth") "Approve this device in your Tailscale admin console" else if(state=="Off") "Optional. Not needed when using the phone’s network or VPN." else "Sign in securely in your browser",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(state=="Running") ChromeSymbol(ChromeIcon.CHECK,Modifier.size(22.dp).testTag("Tailscale connected icon"),color=MaterialTheme.colorScheme.primary)
            else if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
            else TextButton(onClick=onSignIn) {Text(if(state=="Off") "Set up" else "Sign in",style=SettingsType.detail)}
        }
        if(state=="Running" && peers.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
            Text("Active connections",Modifier.padding(start=16.dp,top=12.dp,bottom=4.dp),style=SettingsType.section,color=MaterialTheme.colorScheme.onSurfaceVariant)
            peers.forEach {peer->
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    ServerGlyph(ServerIcon.SERVER,Modifier.size(18.dp))
                    Text(peer.name,Modifier.weight(1f),style=SettingsType.row,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.09f)) {
                        Text(peer.route,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if(networkError.isNotBlank()) Text(networkError,Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun NetworkPrompts() {
    val status by NetworkSettings.status.collectAsState();val scope=rememberCoroutineScope();val context=LocalContext.current
    val host=status.optJSONArray("hosts")?.optJSONObject(0)
    var hidden by remember {mutableStateOf("")}
    val signature=host?.toString().orEmpty()
    if(host!=null && hidden!=signature) AlertDialog(onDismissRequest={hidden=signature},title={Text(if(host.optString("previous").isEmpty()) "Trust SSH host?" else "SSH host key changed")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(host.optString("host"));Text(host.optString("type"),style=SettingsType.detail);Text(host.optString("fingerprint"),style=SettingsType.detail)
            if(host.optString("previous").isNotEmpty()) Text("Previous: ${host.optString("previous")}",style=SettingsType.detail)
            Text("Verify this fingerprint with the host before trusting it.",style=SettingsType.detail)
        }
    },confirmButton={TextButton(onClick={scope.launch {NetworkSettings.trust(host.getString("host"),host.getString("fingerprint"))}}) {Text("Trust host")}},dismissButton={TextButton(onClick={hidden=signature}) {Text("Cancel")}})
    else {
        val banners=status.optJSONObject("banners");val endpoint=banners?.keys()?.asSequence()?.firstOrNull();val message=endpoint?.let {banners.optString(it)}.orEmpty()
        if(message.isNotBlank() && hidden!=message) AlertDialog(onDismissRequest={hidden=message},title={Text("SSH sign-in")},text={Text(message)},confirmButton={
            TextButton(onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SSH sign-in",message))}) {Text("Copy message")}
        },dismissButton={
            val link=Regex("https?://[^\\s<>]+").find(message)?.value
            if(link!=null) TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))}) {Text("Open browser")}
            else TextButton(onClick={hidden=message}) {Text("Close")}
        })
    }
}
