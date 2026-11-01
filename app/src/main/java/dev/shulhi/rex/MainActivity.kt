package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private val model: RexModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { RexTheme { RexApp(model) } }
    }
    override fun onStart() { super.onStart(); model.resume() }
    override fun onStop() { model.pause(); super.onStop() }
}

private val Leaf = Color(0xffb9e381)
private val Ink = Color(0xff101411)
@Composable private fun RexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Leaf, onPrimary = Ink, secondary = Color(0xffb5c7a9),
        background = Ink, surface = Color(0xff171d18), onSurface = Color(0xffe2e9de),
        surfaceVariant = Color(0xff263025), onSurfaceVariant = Color(0xffb9c4b4),
        outline = Color(0xff4b5847), error = Color(0xffffb4a9),
    ), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexApp(model: RexModel) {
    val state by model.state.collectAsState()
    var endpoint by rememberSaveable { mutableStateOf(model.savedEndpoint) }
    var serverVisible by rememberSaveable { mutableStateOf(true) }
    var fontSize by rememberSaveable { mutableIntStateOf(10) }
    var picker by remember { mutableStateOf("") }
    var addVisible by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var errorExpanded by remember { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val context = LocalContext.current
    val screen = state.screen
    val currentSession = state.sessions.firstOrNull { it.id == state.selectedSession }
    val currentWindow = state.windows.firstOrNull { window -> window.blocks.any { it.id == state.selectedBlock } }
        ?: state.windows.firstOrNull { it.blocks.isNotEmpty() }
    val paneIndex = currentWindow?.blocks?.indexOfFirst { it.id == state.selectedBlock } ?: -1
    val phaseLabel = when (state.phase) {
        "connected" -> if (state.controlled) "In control" else "Viewing"
        "connecting" -> "Connecting…"; "reconnecting" -> "Reconnecting…"
        "paused" -> "Paused"; "preview" -> "Offline preview"; "error" -> "Connection issue"
        else -> "Disconnected"
    }
    LaunchedEffect(state.phase) { if (state.connected || state.phase == "preview") serverVisible = false }
    LaunchedEffect(state.connected, state.selectedSession, state.selectedBlock, state.windows) {
        model.rememberCurrentTerminal()
        model.openPreferredTerminal()
    }
    LaunchedEffect(state.controlled) {
        if (state.controlled) { model.applySize(); terminalView?.keyboard() }
    }
    LaunchedEffect(state.error) { errorExpanded = false }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderAction("‹", "Switch session") { picker = "sessions" }
                if (state.windows.isNotEmpty()) {
                    WorkspaceTabs(state.windows, currentWindow?.id.orEmpty(), currentSession?.label.orEmpty(),
                        state.connected && !state.creating, Modifier.weight(1f), model::selectWindow, { picker = "panes" }, { picker = "sessions" })
                } else {
                    Row(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) { picker = "sessions" }
                        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(currentSession?.label ?: if (state.phase == "preview") "Preview" else "Sessions",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    }
                }
                Box {
                    HeaderAction("+", "Add terminal", enabled = state.connected && state.selectedSession.isNotBlank() && !state.creating) { addVisible = true }
                    DropdownMenu(expanded = addVisible, onDismissRequest = { addVisible = false }) {
                        DropdownMenuItem(text = { Text("New tab") }, onClick = { addVisible = false; model.createTab() })
                        DropdownMenuItem(text = { Text("New pane") }, enabled = state.selectedBlock.isNotBlank(),
                            onClick = { addVisible = false; model.createPane() })
                    }
                }
                Box {
                    HeaderAction("⋮", "Terminal menu") { menuVisible = true }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        Text(phaseLabel + (screen?.let { " · ${it.columns} × ${it.rows}" } ?: ""),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.notice.isNotBlank()) Text(state.notice, Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (screen != null && state.phase != "preview") DropdownMenuItem(
                            text = { Text(if (state.controlled) "Release terminal control" else "Take terminal control") },
                            enabled = state.connected && !state.creating,
                            onClick = { menuVisible = false; if (state.controlled) model.releaseControl() else model.takeControl() })
                        DropdownMenuItem(text = { Text("Switch pane") }, enabled = currentWindow != null && state.connected,
                            onClick = { menuVisible = false; picker = "panes" })
                        DropdownMenuItem(text = { Text("Server connection") }, onClick = { menuVisible = false; serverVisible = !serverVisible })
                        DropdownMenuItem(text = { Text("Refresh sessions and tabs") }, enabled = state.connected,
                            onClick = { menuVisible = false; model.refresh() })
                        DropdownMenuItem(text = { Text("Font size · $fontSize") }, enabled = screen != null,
                            onClick = { fontSize = when (fontSize) { 8 -> 10; 10 -> 12; 12 -> 14; 14 -> 16; 16 -> 18; else -> 8 } })
                        DropdownMenuItem(text = { Text("Copy viewport") }, enabled = screen != null, onClick = {
                            menuVisible = false
                            screen?.let { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Rex terminal", it.plainText())) }
                        })
                        DropdownMenuItem(text = { Text("Paste") }, enabled = state.controlled, onClick = {
                            menuVisible = false
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                                ?.getItemAt(0)?.coerceToText(context)?.toString()?.let(model::paste)
                        })
                    }
                }
            }
            if (serverVisible || (state.phase == "disconnected" && screen == null)) {
                Surface(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect over Tailscale", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Rex server URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { model.connect(endpoint) }, enabled = state.phase != "connecting") { Text(if (state.phase == "reconnecting") "Retry now" else "Connect") }
                            if (state.phase != "disconnected" && state.phase != "preview") TextButton(onClick = { model.disconnect() }) { Text("Disconnect") }
                            else TextButton(onClick = { model.preview() }) { Text("Try preview") }
                            if (screen != null) TextButton(onClick = { serverVisible = false }) { Text("Hide") }
                        }
                        if (state.identity.isNotBlank()) Text(state.identity, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.creating) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            if (state.error.isNotBlank()) {
                Text(state.error, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { errorExpanded = !errorExpanded }.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp,
                    maxLines = if (errorExpanded) 6 else 1, overflow = TextOverflow.Ellipsis)
            } else if (state.phase == "connecting" || state.phase == "reconnecting") {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            if (screen != null) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(screen.background))) {
                    AndroidView(
                        factory = { ctx -> TerminalView(ctx).also { terminalView = it } },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        update = { view ->
                            view.onInput = model::type
                            view.onPaneSwipe = model::swipePane
                            view.onDimensions = model::resize
                            view.controlled = state.controlled
                            view.textSp = fontSize.toFloat()
                            view.screen = screen
                        },
                    )
                    if (currentWindow != null && currentWindow.blocks.size > 1 && paneIndex >= 0) {
                        PaneIndicator(paneIndex, currentWindow.blocks.size, state.connected && !state.creating,
                            Modifier.align(Alignment.TopEnd).padding(8.dp)) { picker = "panes" }
                    }
                }
                Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CompactAction("Esc", state.controlled) { model.key("esc") }
                    CompactAction("Ctrl", state.controlled, model.controlModifier) { model.toggleControl() }
                    CompactAction("Alt", state.controlled, model.altModifier) { model.toggleAlt() }
                    CompactAction("Tab", state.controlled) { model.key("tab") }
                    CompactAction("←", state.controlled, description = "Left arrow") { model.key("left") }
                    CompactAction("↓", state.controlled, description = "Down arrow") { model.key("down") }
                    CompactAction("↑", state.controlled, description = "Up arrow") { model.key("up") }
                    CompactAction("→", state.controlled, description = "Right arrow") { model.key("right") }
                    CompactAction(if (state.controlled) "⌨" else "Type", state.connected && !state.creating,
                        selected = state.controlled, description = if (state.controlled) "Show keyboard" else "Take terminal control") {
                        if (state.controlled) terminalView?.keyboard() else model.takeControl()
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
                    val title = when {
                        state.selectedBlock.isNotBlank() -> "Opening terminal…"
                        state.selectedSession.isNotBlank() -> "No terminal panes in this session"
                        state.sessions.isNotEmpty() -> "Choose a session above"
                        state.connected -> "No sessions yet"
                        else -> "Pick up where you left off."
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(if (state.connected) "Use the top bar to switch sessions, tabs and panes." else "Connect to your Rex server, or try the offline preview.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (picker.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { picker = "" }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
                Text(if (picker == "sessions") "Sessions" else "Panes · ${currentWindow?.label.orEmpty()}",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                if (picker == "sessions") {
                    state.sessions.forEach { session ->
                        PickerItem(session.label, session.id == state.selectedSession, state.connected) {
                            picker = ""; model.selectSession(session.id)
                        }
                    }
                    if (state.sessions.isEmpty()) Text("Connect to load your sessions.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { picker = ""; serverVisible = true }) { Text("Server connection") }
                } else {
                    currentWindow?.blocks?.forEachIndexed { index, block ->
                        PickerItem("${index + 1} · ${block.label}", block.id == state.selectedBlock, state.connected) {
                            picker = ""; model.selectBlock(block.id)
                        }
                    }
                    TextButton(onClick = { picker = ""; model.createPane() }, enabled = state.connected && !state.creating && state.selectedBlock.isNotBlank()) { Text("New pane") }
                }
            }
        }
    }
}

@Composable private fun WorkspaceTabs(windows: List<WindowItem>, selectedId: String, session: String, enabled: Boolean,
    modifier: Modifier, onSelect: (String) -> Unit, onPanes: () -> Unit, onSessions: () -> Unit) {
    val scroll = rememberScrollState()
    val positions = remember { mutableStateMapOf<String, IntRange>() }
    LaunchedEffect(selectedId, positions[selectedId], scroll.viewportSize) {
        positions[selectedId]?.let { bounds ->
            val visibleWidth = scroll.viewportSize
            if (bounds.first < scroll.value) scroll.animateScrollTo(bounds.first)
            else if (bounds.last > scroll.value + visibleWidth) scroll.animateScrollTo(bounds.last - visibleWidth)
        }
    }
    Row(modifier.fillMaxHeight().clipToBounds().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        windows.forEachIndexed { index, window ->
            val active = window.id == selectedId
            val title = window.label.takeUnless { it.isBlank() || it.startsWith("Window ") }
                ?: window.blocks.firstOrNull()?.label?.takeIf { it.isNotBlank() } ?: "Terminal ${index + 1}"
            Row(Modifier.widthIn(min = 104.dp, max = 174.dp).width(IntrinsicSize.Min).height(40.dp)
                .onGloballyPositioned { coordinates ->
                    val left = coordinates.positionInParent().x.roundToInt()
                    positions[window.id] = left until left + coordinates.size.width
                }
                .clip(RoundedCornerShape(9.dp))
                .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                .clickable(enabled = enabled, role = Role.Tab) { if (active) onPanes() else onSelect(window.id) }
                .semantics { selected = active; contentDescription = "Tab $title" }
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Text(">_", color = Leaf, fontSize = 11.sp)
                    Spacer(Modifier.width(7.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = if (active) Leaf else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (active) "$session ▾" else "${window.blocks.size} panes",
                        Modifier.clickable(enabled = active, role = Role.Button, onClick = onSessions), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp,
                        lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable private fun HeaderAction(symbol: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(36.dp, 42.dp).padding(2.dp).clip(RoundedCornerShape(9.dp))
        .background(MaterialTheme.colorScheme.surface)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(symbol, fontSize = 23.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

@Composable private fun PaneIndicator(index: Int, count: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val start = (index - 3).coerceIn(0, (count - 7).coerceAtLeast(0))
    Row(modifier.clip(RoundedCornerShape(7.dp)).background(Ink.copy(alpha = 0.9f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Pane ${index + 1} of $count. Switch pane" }
        .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${index + 1}/$count", Modifier.padding(end = 4.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
        repeat(minOf(count, 7)) { offset ->
            Box(Modifier.size(7.dp, 16.dp).background(if (start + offset == index) Leaf else Color(0xff465046), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable private fun PickerItem(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { selected = active }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (active) Leaf else MaterialTheme.colorScheme.onSurface)
        if (active) Text(" ✓", color = Leaf)
    }
}

@Composable private fun CompactAction(label: String, enabled: Boolean = true, selected: Boolean = false,
    description: String = label, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 34.dp).fillMaxHeight().padding(vertical = 3.dp).clip(RoundedCornerShape(5.dp))
        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; this.selected = selected }
        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, maxLines = 1,
            color = if (!enabled) MaterialTheme.colorScheme.outline else if (selected) Leaf else MaterialTheme.colorScheme.onSurface)
    }
}
