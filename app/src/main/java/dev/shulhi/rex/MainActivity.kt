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
    var fontSize by rememberSaveable { mutableIntStateOf(14) }
    var picker by remember { mutableStateOf("") }
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
    LaunchedEffect(state.connected, state.selectedSession, state.windows) { model.openPreferredTerminal() }
    LaunchedEffect(state.controlled) {
        if (state.controlled) { model.applySize(); terminalView?.keyboard() }
    }
    LaunchedEffect(state.error) { errorExpanded = false }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // Navigation stays available with the IME open; no separate title/status row.
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { picker = "sessions" }
                        .semantics { contentDescription = "Switch session" }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(if (state.connected) Leaf else MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(7.dp))
                    Text(currentSession?.label ?: if (state.phase == "preview") "Preview" else "Sessions", Modifier.weight(1f, fill = false),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                if (currentWindow != null) {
                    CompactAction("Panes ${if (paneIndex >= 0) paneIndex + 1 else "–"}/${currentWindow.blocks.size} ▾",
                        enabled = state.connected, description = "Switch pane", onClick = { picker = "panes" })
                }
                if (screen != null && state.phase != "preview") {
                    CompactAction(if (state.controlled) "Release" else "Control", enabled = state.connected,
                        selected = state.controlled, description = if (state.controlled) "Release terminal control" else "Take terminal control") {
                        if (state.controlled) model.releaseControl() else model.takeControl()
                    }
                }
                Box {
                    CompactAction("⋮", description = "Terminal menu", onClick = { menuVisible = true })
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        Text(phaseLabel + (screen?.let { " · ${it.columns} × ${it.rows}" } ?: ""),
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.notice.isNotBlank()) Text(state.notice, Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Server connection") }, onClick = { menuVisible = false; serverVisible = !serverVisible })
                        DropdownMenuItem(text = { Text("Refresh sessions and tabs") }, enabled = state.connected,
                            onClick = { menuVisible = false; model.refresh() })
                        DropdownMenuItem(text = { Text("Font size · $fontSize") }, enabled = screen != null,
                            onClick = { fontSize = when (fontSize) { 12 -> 14; 14 -> 16; 16 -> 18; else -> 12 } })
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
            if (state.windows.isNotEmpty()) {
                WindowTabs(state.windows, currentWindow?.id.orEmpty(), state.connected, model::selectWindow)
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
            if (state.error.isNotBlank()) {
                Text(state.error, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { errorExpanded = !errorExpanded }.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp,
                    maxLines = if (errorExpanded) 6 else 1, overflow = TextOverflow.Ellipsis)
            } else if (state.phase == "connecting" || state.phase == "reconnecting") {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            if (screen != null) {
                AndroidView(
                    factory = { ctx -> TerminalView(ctx).also { terminalView = it } },
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color(screen.background)),
                    update = { view ->
                        view.onInput = model::type
                        view.onDimensions = model::resize
                        view.controlled = state.controlled
                        view.textSp = fontSize.toFloat()
                        view.screen = screen
                    },
                )
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
                    CompactAction("⌨", state.controlled, description = "Show keyboard") { terminalView?.keyboard() }
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
                }
            }
        }
    }
}

@Composable private fun WindowTabs(windows: List<WindowItem>, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val scroll = rememberScrollState()
    val positions = remember { mutableStateMapOf<String, IntRange>() }
    LaunchedEffect(selectedId, positions[selectedId]) {
        positions[selectedId]?.let { bounds ->
            val visibleWidth = scroll.viewportSize
            if (bounds.first < scroll.value) scroll.animateScrollTo(bounds.first)
            else if (bounds.last > scroll.value + visibleWidth) scroll.animateScrollTo(bounds.last - visibleWidth)
        }
    }
    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surface).horizontalScroll(scroll)) {
        windows.forEachIndexed { index, window ->
            val active = window.id == selectedId
            Column(Modifier.widthIn(min = 72.dp, max = 180.dp).width(IntrinsicSize.Min).fillMaxHeight()
                .onGloballyPositioned { coordinates ->
                    val left = coordinates.positionInParent().x.roundToInt()
                    positions[window.id] = left until left + coordinates.size.width
                }
                .clickable(enabled = enabled && window.blocks.isNotEmpty(), role = Role.Tab) { onSelect(window.id) }
                .semantics { selected = active; contentDescription = "Tab ${window.label.ifBlank { "${index + 1}" }}" }
                .background(if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                verticalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.height(2.dp))
                Text(window.label.ifBlank { "Tab ${index + 1}" }, Modifier.padding(horizontal = 14.dp),
                    color = if (active) Leaf else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Leaf else Color.Transparent))
            }
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
