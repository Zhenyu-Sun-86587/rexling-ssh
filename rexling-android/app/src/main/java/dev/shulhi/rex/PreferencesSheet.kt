package dev.shulhi.rex

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PreferencesSheet(onAppearance: ()->Unit,onFont: ()->Unit,onAlerts: ()->Unit,onAbout: ()->Unit,onClose: ()->Unit,
    shortcutGesture: ControlShortcutGesture=ControlShortcutGesture.HOLD,onShortcutGesture: (ControlShortcutGesture)->Unit={},onNeovim: ()->Unit={},
    arrowJoystickEnabled: Boolean=true,onArrowJoystick: (Boolean)->Unit={},enterSubmits: Boolean=false,onEnterSubmits: (Boolean)->Unit={},toolbarWithKeyboardOnly: Boolean=false,onToolbarWithKeyboardOnly: (Boolean)->Unit={},codexTitleAttention: Boolean=false,onCodexTitleAttention: (Boolean)->Unit={},
    minimizeFloatingPanes: Boolean=false,onMinimizeFloatingPanes: (Boolean)->Unit={},onNetwork: ()->Unit={},
    keepScreenAwake: KeepScreenAwake=KeepScreenAwake.SYSTEM,onKeepScreenAwake: (KeepScreenAwake)->Unit={},onToolbar: ()->Unit={},onControlItems: ()->Unit={},onSshKeys: ()->Unit={},hazeState: dev.chrisbanes.haze.HazeState?=null) {
    // Built outside the sheet on purpose: the frost redraws when its host window draws, and inside the
    // sheet that host is the sheet's own window, which then redraws itself every frame while open.
    val frost=Modifier.drawerFrost(hazeState,BottomSheetDefaults.ExpandedShape)
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,tonalElevation=0.dp,dragHandle=null,contentWindowInsets={WindowInsets(0,0,0,0)}) {
        Column(frost.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            DrawerHandle()
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Preferences",Modifier.weight(1f),style=SettingsType.row,fontWeight=FontWeight.Bold)
                ChromeButton(ChromeIcon.CLOSE,"Close preferences",onClick=onClose)
            }
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SettingsSection("Display",compact=true) {
                SettingsRow("Appearance","Themes and light or dark mode",SettingsGlyph.APPEARANCE,onAppearance)
                SettingsDivider()
                SettingsRow("Font size","Terminal text size",SettingsGlyph.FONT,onFont)
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp)) {
                    Text("Keep screen awake",style=SettingsType.row)
                    Text("Only while Rexling is in the foreground. You can still lock the phone.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    KeepScreenAwake.entries.forEach {mode->
                        Row(Modifier.fillMaxWidth().selectable(mode==keepScreenAwake,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={onKeepScreenAwake(mode)}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=mode==keepScreenAwake,onClick=null)
                            Column(Modifier.padding(start=8.dp)) {
                                Text(mode.label,style=SettingsType.row)
                                Text(mode.detail,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            SettingsSection("Panes",compact=true) {
                Row(Modifier.fillMaxWidth().toggleable(minimizeFloatingPanes,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onMinimizeFloatingPanes)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Minimize floating panes",style=SettingsType.row)
                        Text("Open floating panes from a corner dock. Swipe between tiled panes only.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=minimizeFloatingPanes,onCheckedChange=null)
                }
            }
            SettingsSection("Keyboard",compact=true) {
                SettingsRow("Keyboard toolbar","Choose and arrange up to seven buttons",SettingsGlyph.COMMAND,onToolbar)
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(toolbarWithKeyboardOnly,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onToolbarWithKeyboardOnly)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Show toolbar only with keyboard",style=SettingsType.row)
                        Text(if(toolbarWithKeyboardOnly) "More room for the terminal. Tap the terminal or the keyboard button beside Tabs to type." else "The toolbar stays visible when the keyboard is closed",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=toolbarWithKeyboardOnly,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(arrowJoystickEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onArrowJoystick)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Use joystick for arrows",style=SettingsType.row)
                        Text(if(arrowJoystickEnabled) "Hold and slide; double-tap for arrow buttons" else "Tap for arrow buttons",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=arrowJoystickEnabled,onCheckedChange=null)
                }
                SettingsDivider()
                Row(Modifier.fillMaxWidth().toggleable(enterSubmits,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onEnterSubmits)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Keyboard Enter sends",style=SettingsType.row)
                        Text(if(enterSubmits) "Submits, like a hardware Enter. Hold toolbar Shift for a newline." else "A newline. Programs such as Claude Code insert a line instead of submitting.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=enterSubmits,onCheckedChange=null)
                }
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
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
                SettingsRow("Ctrl shortcut buttons","Customize keys and key sequences",SettingsGlyph.COMMAND,onControlItems)
                SettingsDivider()
                SettingsRow("Neovim shortcuts","Customize the floating key menu",SettingsGlyph.COMMAND,onNeovim)
            }
            SettingsSection("Connections",compact=true) {
                SettingsRow("Network","Built-in Tailscale and control server",SettingsGlyph.NETWORK,onNetwork)
                SettingsDivider()
                SettingsRow("SSH keys","Save and reuse private keys across servers",SettingsGlyph.KEY,onSshKeys)
                SettingsDivider()
                SettingsRow("Background alerts","Task completions and attention alerts",SettingsGlyph.ALERTS,onAlerts)
            }
            SettingsSection("Port forwarding",compact=true) {
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)) {
                    Text("When the phone port is in use",style=SettingsType.row)
                    Text("Prefer the remote port number. Only Rexling forwards can be replaced.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    PortConflictPolicy.entries.forEach {choice->
                        Row(Modifier.fillMaxWidth().selectable(PortForwards.policy==choice,role=androidx.compose.ui.semantics.Role.RadioButton,onClick={PortForwards.changePolicy(choice)}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=PortForwards.policy==choice,onClick=null)
                            Text(choice.label,Modifier.padding(start=8.dp),style=SettingsType.row)
                        }
                    }
                }
            }
            SettingsSection("Experimental",compact=true) {
                Row(Modifier.fillMaxWidth().toggleable(value=codexTitleAttention,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onCodexTitleAttention)
                    .padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Codex title attention",style=SettingsType.row)
                        Text("Use Codex’s explicit Action Required title when its status stays Working. Depends on Codex’s title format.",style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=codexTitleAttention,onCheckedChange=null)
                }
            }
            SettingsSection("App",compact=true) {
                SettingsRow("About Rexling","Version, build and project information",SettingsGlyph.INFO,onAbout)
            }
        }
        }
    }
}
