package dev.shulhi.rex

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.selection.toggleable
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
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onPortside: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onNeovim: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={}) {
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            SettingsHeader("Preferences","Close preferences",onClose)
            SettingsSection("Display") {
                SettingsRow("Appearance","Themes and light or dark mode",SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow("Font size","Terminal text size",SettingsGlyph.FONT,onFont)
            }
            SettingsSection("Keyboard") {
                Row(Modifier.fillMaxWidth().toggleable(arrowJoystickEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onArrowJoystick)
                    .padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Use joystick for arrows",style=SettingsType.row)
                        Text(if(arrowJoystickEnabled) "Hold and slide; double-tap for arrow buttons" else "Tap for arrow buttons",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=arrowJoystickEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Ctrl shortcuts",style=SettingsType.row)
                    Text("Open the ^ shortcut list with",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ControlShortcutGesture.entries.forEach {gesture->
                            FilterChip(selected=shortcutGesture==gesture,onClick={onShortcutGesture(gesture)},
                                label={Text(if(gesture==ControlShortcutGesture.HOLD) "Hold" else "Double tap",style=SettingsType.row)})
                        }
                    }
                }
                SettingsDivider()
                SettingsRow("Neovim shortcuts","Customize the floating key menu",SettingsGlyph.COMMAND,onNeovim)
            }
            SettingsSection("Connections") {
                SettingsRow("Background alerts","Know when a task needs your attention",SettingsGlyph.ALERTS,onAlerts)
                SettingsDivider()
                SettingsRow("Portside","Image uploads to your remote machine",SettingsGlyph.UPLOAD,onPortside)
            }
            SettingsSection("App") {
                SettingsRow("About Rexlink","Version, build and project information",SettingsGlyph.INFO,onAbout)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PortsideSettingsSheet(model: RexModel,network: (()->Unit)->Unit,onClose: ()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var editing by remember {mutableStateOf<ServerProfile?>(null)}
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        PortsideSettingsContent(model.servers,model.portside,{editing=it},{
            if(!openPortside(context)) Toast.makeText(context,"Portside is not installed on this phone",Toast.LENGTH_LONG).show()
        },onClose)
    }
    editing?.let {server->PortsideEditor(server,model.portside[server.id],network,onSave={url,folder,enabled->model.savePortside(server.id,url,folder,enabled)},onClose={editing=null})}
}

@Composable internal fun PortsideSettingsContent(servers: List<ServerProfile>,configs: Map<String,PortsideConfig>,onEdit: (ServerProfile)->Unit,onOpen: ()->Unit,onClose: ()->Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=680.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        SettingsHeader("Portside","Close Portside settings",onClose,subtitle="Image uploads")
        Text("Send images to Codex or Claude. Rexlink uploads the image and inserts its path into your pane.",style=SettingsType.body,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSection("Rex connections") {
            if(servers.isEmpty()) Text("Add a Rex server to set up image uploads.",Modifier.padding(16.dp),style=SettingsType.body)
            servers.forEachIndexed {index,server->
                if(index>0) SettingsDivider()
                val config=configs[server.id]
                SettingsRow(server.label,"Rex: ${server.endpoint}\n"+(config?.let {"Uploads: ${it.endpoint}"} ?: "Choose a Portside upload server"),SettingsGlyph.SERVER,{onEdit(server)},badge=when {config==null->"Set up";config.enabled->"On";else->"Off"})
            }
        }
        Text("Portside can use a different Tailscale address from Rex, even on the same machine. Choose a connection to configure its upload server.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSection("Portside app") {
            SettingsRow("Open Portside","Manage hosting and upload folders",SettingsGlyph.UPLOAD,onOpen)
        }
        Text("Keep Tailscale connected. Portside uses your Tailscale identity to authorize uploads.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun PortsideEditor(server: ServerProfile,saved: PortsideConfig?,network: (()->Unit)->Unit,onSave: (String,String,Boolean)->String?,onClose: ()->Unit) {
    var endpoint by remember {mutableStateOf(saved?.endpoint.orEmpty())}
    var folder by remember {mutableStateOf(saved?.folder.orEmpty())}
    var enabled by remember {mutableStateOf(saved?.enabled ?: true)}
    var error by remember {mutableStateOf<String?>(null)}
    var checked by remember {mutableStateOf("")}
    var checking by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface,title={
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("Image uploads",style=SettingsType.title)
            Text(server.label,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)) {
                Row(Modifier.fillMaxWidth().toggleable(value=enabled,enabled=!checking,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={enabled=it;error=null;checked=""}).padding(start=14.dp,end=10.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("Use image uploads",style=SettingsType.row)
                        Text(if(enabled) "Send images through Portside" else "Off · settings are kept",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=enabled,onCheckedChange=null,enabled=!checking)
                }
            }
            OutlinedTextField(endpoint,{endpoint=it;error=null;checked=""},Modifier.fillMaxWidth(),singleLine=true,enabled=enabled && !checking,textStyle=SettingsType.body,label={Text("Portside address",style=SettingsType.detail)},placeholder={Text("mac.tailnet.ts.net:9417",style=SettingsType.body)},shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
            Text("Use Portside’s Tailscale address, which may differ from Rex. A hostname alone uses port 9417.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(folder,{folder=it;error=null;checked=""},Modifier.fillMaxWidth(),singleLine=true,enabled=enabled && !checking,textStyle=SettingsType.body,label={Text("Upload folder (optional)",style=SettingsType.detail)},placeholder={Text("project/screenshots",style=SettingsType.body)},shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            Text("A subfolder inside Portside’s upload directory. Leave blank to use the default.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled=enabled && endpoint.isNotBlank() && !checking,modifier=Modifier.fillMaxWidth(),onClick={
                val config=runCatching {PortsideConfig.create(endpoint,folder)}.getOrElse {error=it.message;return@OutlinedButton}
                network {scope.launch {
                    checking=true;error=null;checked=""
                    try {val info=PortsideClient().info(config);if(runCatching {PortsideConfig.create(endpoint,folder)}.getOrNull()==config) checked="Connected · up to ${info.maxBytes/(1024*1024)} MiB\n${info.directory}"}
                    catch(e: CancellationException) {throw e} catch(e: Exception) {error=e.message ?: "Could not connect"} finally {checking=false}
                }}
            }) {
                if(checking) {CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                Text(if(checking) "Checking…" else "Check connection",style=SettingsType.row)
            }
            if(checked.isNotEmpty()) Text(checked,style=SettingsType.detail,color=MaterialTheme.colorScheme.primary)
            error?.let {Text(it,style=SettingsType.detail,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={Button(enabled=!checking && (!enabled || endpoint.isNotBlank()),onClick={error=onSave(endpoint,folder,enabled);if(error==null) onClose()}) {Text("Save",style=SettingsType.row)}},dismissButton={TextButton(onClick=onClose) {Text("Cancel",style=SettingsType.row)}})
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
