package dev.shulhi.rex

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun openPortside(context: Context): Boolean {
    val intent=context.packageManager.getLaunchIntentForPackage("app.portside.android") ?: return false
    return runCatching {context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true}.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onPortside: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Preferences",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,"Close preferences",onClick=onClose)
            }
            for((label,action) in listOf("Appearance" to onAppearance,"Font size" to onFont,"Background alerts" to onAlerts,"Portside · Image uploads" to onPortside,"About Rexlink" to onAbout)) {
                TextButton(onClick=action,modifier=Modifier.fillMaxWidth()) {Text(label,Modifier.fillMaxWidth())}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortsideSettingsSheet(model: RexModel,network: (()->Unit)->Unit,onClose: ()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Portside",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,"Close Portside settings",onClick=onClose)
            }
            Text("Upload images to the machine running Codex or Claude, then paste their server paths automatically.",fontSize=13.sp)
            Text("Portside checks your Tailscale identity. Keep Tailscale connected with the same account as the server.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(model.servers.isEmpty()) Text("Add a Rex server first.")
            model.servers.forEach {server->
                val config=model.portside[server.id]
                Row(Modifier.fillMaxWidth().clickable {editing=server}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(server.label,fontWeight=FontWeight.SemiBold)
                        Text(config?.endpoint ?: "Set up image uploads",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!config?.folder.isNullOrBlank()) Text(config!!.folder,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChromeSymbol(ChromeIcon.RIGHT)
                }
            }
            OutlinedButton(onClick={if(!openPortside(context)) Toast.makeText(context,"Portside is not installed on this phone",Toast.LENGTH_LONG).show()}) {Text("Open Portside")}
        }
    }
    editing?.let {server->PortsideEditor(server,model.portside[server.id],network,onSave={url,folder->model.savePortside(server.id,url,folder)},onClose={editing=null})}
}

@Composable internal fun PortsideEditor(server: ServerProfile,saved: PortsideConfig?,network: (()->Unit)->Unit,onSave: (String,String)->String?,onClose: ()->Unit) {
    var endpoint by remember {mutableStateOf(saved?.endpoint ?: runCatching {"http://${java.net.URI(server.endpoint).host.let {if(':' in it && !it.startsWith('[')) "[$it]" else it}}:9417"}.getOrDefault(""))}
    var folder by remember {mutableStateOf(saved?.folder.orEmpty())}
    var error by remember {mutableStateOf<String?>(null)}
    var checked by remember {mutableStateOf("")}
    var checking by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest=onClose,title={Text("Image uploads · ${server.label}")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(endpoint,{endpoint=it;error=null;checked=""},Modifier.fillMaxWidth(),singleLine=true,enabled=!checking,label={Text("Portside server URL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
            OutlinedTextField(folder,{folder=it;error=null;checked=""},Modifier.fillMaxWidth(),singleLine=true,enabled=!checking,label={Text("Upload folder (optional)")},placeholder={Text("project/screenshots")})
            Text("Use Portside on the same machine/filesystem as this Rex server. Enable an upload directory in Portside.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled=!checking,onClick={
                val config=runCatching {PortsideConfig.create(endpoint,folder)}.getOrElse {error=it.message;return@TextButton}
                network {scope.launch {
                    checking=true;error=null;checked=""
                    try {val info=PortsideClient().info(config);if(config.endpoint==endpoint.trim().removeSuffix("/") && config.folder==folder.trim()) checked="Connected · up to ${info.maxBytes/(1024*1024)} MiB\n${info.directory}"}
                    catch(e: CancellationException) {throw e} catch(e: Exception) {error=e.message ?: "Could not connect"} finally {checking=false}
                }}
            }) {Text(if(checking) "Checking…" else "Check connection")}
            if(checked.isNotEmpty()) Text(checked,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            error?.let {Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
            if(saved!=null) TextButton(onClick={error=onSave("","");if(error==null) onClose()}) {Text("Disable image uploads")}
        }
    },confirmButton={TextButton(enabled=endpoint.isNotBlank(),onClick={error=onSave(endpoint,folder);if(error==null) onClose()}) {Text("Save")}},dismissButton={TextButton(onClick=onClose) {Text("Cancel")}})
}

@Composable internal fun ImageUploadOverlay(status: ImageUploadStatus,canInsert: Boolean,onInsert: ()->Unit,onRetry: ()->Unit,onCopy: ()->Unit,onCancel: ()->Unit,modifier: Modifier=Modifier,retryable: Boolean=true) {
    Surface(modifier,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant,shadowElevation=3.dp) {
        Column(Modifier.widthIn(max=380.dp).padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(when(status.phase) {ImagePhase.PREPARING->"Preparing image…";ImagePhase.UPLOADING->"Uploading image… ${(status.progress*100).toInt()}%";ImagePhase.READY->"Image ready · ${status.target.label}";ImagePhase.FAILED->"Image upload failed"},fontSize=13.sp,fontWeight=FontWeight.Medium)
            if(status.phase==ImagePhase.UPLOADING) LinearProgressIndicator(progress={status.progress},modifier=Modifier.fillMaxWidth())
            if(status.phase==ImagePhase.FAILED) Text(status.error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
            if(status.phase==ImagePhase.READY && !canInsert) Text("Return to the original pane and take control to insert.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                if(status.phase==ImagePhase.READY) {TextButton(onClick=onInsert,enabled=canInsert) {Text("Insert")};TextButton(onClick=onCopy) {Text("Copy path")}}
                if(status.phase==ImagePhase.FAILED && retryable) TextButton(onClick=onRetry) {Text("Retry")}
                TextButton(onClick=onCancel) {Text(if(status.phase==ImagePhase.READY) "Dismiss" else "Cancel")}
            }
        }
    }
}
