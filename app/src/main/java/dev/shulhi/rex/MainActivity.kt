package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

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

@Composable private fun RexApp(model: RexModel) {
    val state by model.state.collectAsState()
    var endpoint by rememberSaveable { mutableStateOf(model.savedEndpoint) }
    var serverVisible by rememberSaveable { mutableStateOf(true) }
    var fontSize by rememberSaveable { mutableIntStateOf(14) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(state.phase) { if (state.phase == "connected" || state.phase == "preview") serverVisible = false }
    LaunchedEffect(state.controlled) {
        if (state.controlled) { model.applySize(); terminalView?.keyboard() }
    }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(9.dp), color = Leaf) {
                    Text(">_", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rex", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val label = when (state.phase) {
                        "connected" -> if (state.controlled) "In control" else "Connected"
                        "connecting" -> "Connecting…"; "reconnecting" -> "Reconnecting…"
                        "paused" -> "Paused"; "preview" -> "Offline preview"; "error" -> "Connection issue"
                        else -> "Your terminals, anywhere"
                    }
                    Text(label, color = if (state.connected) Leaf else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                TextButton(onClick = { serverVisible = !serverVisible }) { Text(if (serverVisible) "Hide" else "Server") }
            }
            if (serverVisible || (state.phase == "disconnected" && state.screen == null)) {
                Surface(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connect to your workspace", style = MaterialTheme.typography.titleMedium)
                        Text("Keep Tailscale connected on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Rex server URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            placeholder = { Text("https://rex.example.ts.net") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { model.connect(endpoint) }, enabled = state.phase != "connecting") { Text(if (state.phase == "reconnecting") "Retry now" else "Connect") }
                            if (state.phase != "disconnected" && state.phase != "preview") TextButton(onClick = { model.disconnect() }) { Text("Disconnect") }
                            else TextButton(onClick = { model.preview() }) { Text("Try preview") }
                        }
                        if (state.identity.isNotBlank()) Text(state.identity, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.error.isNotBlank()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(state.error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
            }
            if (state.sessions.isNotEmpty() && !(keyboardVisible && state.screen != null)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.sessions.forEach { session -> FilterChip(selected = state.selectedSession == session.id,
                        onClick = { model.selectSession(session.id) }, enabled = state.connected,
                        label = { Text(session.label) }) }
                    TextButton(onClick = { model.refresh() }, enabled = state.connected) { Text("Refresh") }
                }
            }
            val blocks = state.windows.flatMap { window -> window.blocks.map { window.label to it } }
            if (blocks.isNotEmpty() && !(keyboardVisible && state.screen != null)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    blocks.forEach { (window, block) -> InputChip(selected = state.selectedBlock == block.id,
                        onClick = { model.selectBlock(block.id) }, enabled = state.connected,
                        label = { Text(if (blocks.size == 1) block.label else "$window · ${block.label}", maxLines = 1) }) }
                }
            }
            val screen = state.screen
            if (screen != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${screen.columns} × ${screen.rows}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { fontSize = when (fontSize) { 12 -> 14; 14 -> 16; 16 -> 18; else -> 12 } }) { Text("Aa") }
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rex terminal", screen.plainText()))
                    }) { Text("Copy") }
                    if (state.phase != "preview") {
                        if (state.controlled) TextButton(onClick = { model.releaseControl() }) { Text("Release") }
                        else FilledTonalButton(onClick = { model.takeControl() }, enabled = state.connected) { Text("Take control") }
                    }
                }
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
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeyButton("Esc", state.controlled) { model.key("esc") }
                    FilterChip(selected = model.controlModifier, onClick = model::toggleControl, enabled = state.controlled, label = { Text("Ctrl") })
                    FilterChip(selected = model.altModifier, onClick = model::toggleAlt, enabled = state.controlled, label = { Text("Alt") })
                    KeyButton("Tab", state.controlled) { model.key("tab") }
                    KeyButton("←", state.controlled) { model.key("left") }
                    KeyButton("↓", state.controlled) { model.key("down") }
                    KeyButton("↑", state.controlled) { model.key("up") }
                    KeyButton("→", state.controlled) { model.key("right") }
                    KeyButton("Paste", state.controlled) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(model::paste)
                    }
                    KeyButton("Keyboard", state.controlled) { terminalView?.keyboard() }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
                    if (state.phase == "connecting" || state.phase == "reconnecting" || state.selectedBlock.isNotBlank()) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(16.dp))
                    }
                    val title = when {
                        state.selectedBlock.isNotBlank() -> "Opening terminal…"
                        state.selectedSession.isNotBlank() -> "Choose a terminal"
                        state.sessions.isNotEmpty() -> "Choose a session"
                        state.connected -> "No sessions yet"
                        else -> "Pick up where you left off."
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Text(if (state.connected) "Your existing Rex terminals appear above." else "Connect directly to your Rex server, or open the offline preview.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.notice.isNotBlank()) Text(state.notice, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), maxLines = 1,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun KeyButton(label: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
}
