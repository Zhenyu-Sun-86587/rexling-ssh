package dev.shulhi.rex

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.content.edit

class RexModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("rex", 0)
    val savedEndpoint: String = preferences.getString("endpoint", "https://rex.taildaac1.ts.net")!!
    var appearance by mutableStateOf(runCatching { Appearance.valueOf(preferences.getString("appearance","SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM))
        private set
    var lastSessionId by mutableStateOf(preferences.getString("last_session:$savedEndpoint","").orEmpty())
        private set
    var paneFrames by mutableStateOf<Map<String,TerminalScreen>>(emptyMap())
        private set
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState
    var controlModifier by mutableStateOf(false)
        private set
    var altModifier by mutableStateOf(false)
        private set
    var navigationRequest by mutableStateOf(NavigationRequest())
        private set
    private val navigation = WorkspaceNavigation()
    private var navigationSerial = 0L
    private var handle = 0L
    private var resizeJob: Job? = null
    private var lastSize = 0 to 0
    @Volatile private var foreground = true

    init {
        try {
            handle = NativeBridge.create()
            check(handle != 0L) { "Could not start native terminal" }
            viewModelScope.launch(Dispatchers.IO) {
                var revision = 0L
                while (true) {
                    if (!foreground) { delay(500); continue }
                    val json = NativeBridge.poll(handle, revision)
                    if (json.isNotEmpty()) {
                        try {
                            val next = AppState.parse(json)
                            revision = next.revision
                            withContext(Dispatchers.Main.immediate) {
                                navigationRequest = navigationRequest.acknowledge(next)
                                val frames=paneFrames.toMutableMap()
                                frames.putAll(next.previewFrames)
                                if(next.screen!=null && next.selectedBlock.isNotBlank()) frames[next.selectedBlock]=next.screen
                                while(frames.size>16) frames.remove(frames.keys.first())
                                paneFrames=frames
                                if(next.selectedSession.isNotBlank() && next.selectedSession!=state.value.selectedSession) {
                                    val old=state.value.selectedSession
                                    if(old.isNotBlank() && !navigationRequest.pending) {
                                        lastSessionId=old
                                        preferences.edit {putString("last_session:${next.endpoint}",old)}
                                    }
                                    preferences.edit {putString("current_session:${next.endpoint}",next.selectedSession)}
                                }
                                mutableState.value = next
                            }
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                navigationRequest = NavigationRequest()
                                mutableState.value = mutableState.value.copy(error = "Could not read terminal state: ${error.message}")
                            }
                        }
                    }
                    delay(if (navigationRequest.pending) 16 else 60)
                }
            }
        } catch (error: Throwable) {
            mutableState.value = AppState(phase = "error", error = "Native terminal could not start: ${error.message}")
        }
    }
    private fun command(type: String, vararg args: Pair<String, Any>) {
        if (handle == 0L) return
        val request = JSONObject().put("type", type)
        args.forEach { (key, value) -> request.put(key, value) }
        val error = NativeBridge.command(handle, request.toString())
        if (error.isNotEmpty()) {
            navigationRequest = NavigationRequest()
            mutableState.value = mutableState.value.copy(error = error)
        }
    }
    fun connect(server: String) {
        val value = server.trim()
        navigationRequest = NavigationRequest()
        paneFrames=emptyMap()
        lastSessionId=preferences.getString("last_session:$value","").orEmpty()
        preferences.edit { putString("endpoint", value) }
        command("connect", "server" to value)
    }
    fun disconnect() { navigationRequest = NavigationRequest(); command("disconnect") }
    fun preview() = command("preview")
    fun selectSession(id: String) {
        if (id == (navigationRequest.session ?: state.value.selectedSession)) return
        val old=navigationRequest.session ?: state.value.selectedSession
        if(old.isNotBlank() && old!=id) {
            lastSessionId=old
            preferences.edit { putString("last_session:${state.value.endpoint}",old) }
        }
        navigationRequest = NavigationRequest(session = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false
        command("session", "id" to id, "navigationId" to navigationRequest.id)
    }
    fun selectBlock(id: String) {
        val current = state.value
        if (navigationRequest.session != null && navigationRequest.session != current.selectedSession) return
        if (id == navigationRequest.block || (id == current.selectedBlock && !navigationRequest.pending)) return
        if (current.windows.none { window -> window.blocks.any { it.id == id } }) return
        navigationRequest = NavigationRequest(block = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false
        navigation.remember(current.selectedSession, current.windows, id)
        command("block", "id" to id, "navigationId" to navigationRequest.id)
    }
    fun selectWindow(id: String) {
        val current = state.value
        current.windows.firstOrNull { it.id == id }?.let { window ->
            navigation.pane(current.selectedSession, window)?.let(::selectBlock)
        }
    }
    fun rememberCurrentTerminal() {
        val current = state.value
        if (!navigationRequest.pending) navigation.remember(current.selectedSession, current.windows, current.selectedBlock)
    }
    fun openPreferredTerminal() {
        val current = state.value
        if(!navigationRequest.pending && current.connected && current.selectedSession.isBlank()) {
            val saved=preferences.getString("current_session:${current.endpoint}","")
            (current.sessions.firstOrNull { it.id==saved } ?: current.sessions.firstOrNull())?.let { selectSession(it.id) }
        } else if (!navigationRequest.pending && current.connected && current.selectedSession.isNotBlank() && current.selectedBlock.isBlank()) {
            navigation.pane(current.selectedSession, current.windows)?.let(::selectBlock)
        }
    }
    fun swipePane(step: Int) {
        val current = state.value
        if (current.connected && !current.creating) navigation.neighbour(current.windows, navigationRequest.block ?: current.selectedBlock, step)?.let(::selectBlock)
    }
    fun swipeSession(step: Int) {
        val current = state.value
        if (current.connected && !current.creating) navigation.neighbourSession(current.sessions,
            navigationRequest.session ?: current.selectedSession, step)?.let(::selectSession)
    }
    fun updateTerminalTheme(palette: TerminalPalette) {
        fun hex(color: Int) = "#%06x".format(java.util.Locale.ROOT, color and 0xffffff)
        command("theme", "args" to JSONObject()
            .put("scheme", if(palette.dark) "dark" else "light")
            .put("background", hex(palette.background)).put("foreground", hex(palette.foreground))
            .put("cursor", hex(palette.accent))
            .put("palette", org.json.JSONArray(palette.ansi.mapIndexed { index, color -> "$index=${hex(color)}" })))
    }
    fun changeAppearance(value: Appearance) { appearance=value;preferences.edit { putString("appearance",value.name) } }
    fun jumpLastSession() { if(state.value.sessions.any { it.id==lastSessionId }) selectSession(lastSessionId) }
    fun refreshSessionDetails() { if(state.value.connected) command("sessions_info") }
    fun prefetchPanes(ids: List<String>) {
        if(state.value.connected && !state.value.creating) command("peek","session" to state.value.selectedSession,"ids" to org.json.JSONArray(ids))
    }
    fun createSession(label: String) { if(state.value.connected && !state.value.creating && !navigationRequest.pending) command("new_session","label" to label) }
    fun createTab() {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedSession.isNotBlank()) command("new_tab", "session" to current.selectedSession)
    }
    fun createPane() {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedBlock.isNotBlank()) command("new_pane", "session" to current.selectedSession, "anchor" to current.selectedBlock)
    }
    fun refresh() = command("refresh")
    fun takeControl() = command("take")
    fun releaseControl() { controlModifier = false; altModifier = false; command("release") }
    fun pause() {
        navigationRequest = NavigationRequest()
        foreground = false; resizeJob?.cancel(); controlModifier = false; altModifier = false
        mutableState.value = state.value.copy(controlled = false, phase = if (state.value.connected) "paused" else state.value.phase)
        command("pause")
    }
    fun resume() { foreground = true; command("resume") }
    fun toggleControl() { controlModifier = !controlModifier }
    fun toggleAlt() { altModifier = !altModifier }
    fun type(text: String) {
        if (!state.value.controlled || navigationRequest.pending || text.isEmpty()) return
        val input = TerminalInput.modified(text, controlModifier, altModifier)
        controlModifier = false; altModifier = false
        command("input", "text" to input)
    }
    fun key(name: String) = type(TerminalInput.key(name))
    fun paste(text: String) { if (state.value.controlled && !navigationRequest.pending) command("paste", "text" to text) }
    fun resize(columns: Int, rows: Int) {
        val size = columns.coerceIn(1, 512) to rows.coerceIn(1, 256)
        lastSize = size
        resizeJob?.cancel()
        if (!state.value.controlled || navigationRequest.pending) return
        resizeJob = viewModelScope.launch {
            delay(150)
            command("resize", "columns" to size.first, "rows" to size.second)
        }
    }
    fun applySize() = resize(lastSize.first.coerceAtLeast(1), lastSize.second.coerceAtLeast(1))
    override fun onCleared() {
        if (handle != 0L) NativeBridge.destroy(handle)
        handle = 0
    }
}
