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
import org.json.JSONObject
import androidx.core.content.edit

class RexModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("rex", 0)
    val savedEndpoint: String = preferences.getString("endpoint", "https://rex.taildaac1.ts.net")!!
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState
    var controlModifier by mutableStateOf(false)
        private set
    var altModifier by mutableStateOf(false)
        private set
    private val navigation = WorkspaceNavigation()
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
                            mutableState.value = next
                        } catch (error: Exception) {
                            mutableState.value = mutableState.value.copy(error = "Could not read terminal state: ${error.message}")
                        }
                    }
                    delay(60)
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
        if (error.isNotEmpty()) mutableState.value = mutableState.value.copy(error = error)
    }
    fun connect(server: String) {
        val value = server.trim()
        preferences.edit { putString("endpoint", value) }
        command("connect", "server" to value)
    }
    fun disconnect() = command("disconnect")
    fun preview() = command("preview")
    fun selectSession(id: String) {
        if (id != state.value.selectedSession) command("session", "id" to id)
    }
    fun selectBlock(id: String) {
        val current = state.value
        if (id == current.selectedBlock) return
        navigation.remember(current.selectedSession, current.windows, id)
        command("block", "id" to id)
    }
    fun selectWindow(id: String) {
        val current = state.value
        current.windows.firstOrNull { it.id == id }?.let { window ->
            navigation.pane(current.selectedSession, window)?.let(::selectBlock)
        }
    }
    fun openPreferredTerminal() {
        val current = state.value
        if (current.connected && current.selectedSession.isNotBlank() && current.selectedBlock.isBlank()) {
            navigation.pane(current.selectedSession, current.windows)?.let(::selectBlock)
        }
    }
    fun refresh() = command("refresh")
    fun takeControl() = command("take")
    fun releaseControl() { controlModifier = false; altModifier = false; command("release") }
    fun pause() {
        foreground = false; resizeJob?.cancel(); controlModifier = false; altModifier = false
        mutableState.value = state.value.copy(controlled = false, phase = if (state.value.connected) "paused" else state.value.phase)
        command("pause")
    }
    fun resume() { foreground = true; command("resume") }
    fun toggleControl() { controlModifier = !controlModifier }
    fun toggleAlt() { altModifier = !altModifier }
    fun type(text: String) {
        if (!state.value.controlled || text.isEmpty()) return
        val input = TerminalInput.modified(text, controlModifier, altModifier)
        controlModifier = false; altModifier = false
        command("input", "text" to input)
    }
    fun key(name: String) = type(TerminalInput.key(name))
    fun paste(text: String) { if (state.value.controlled) command("paste", "text" to text) }
    fun resize(columns: Int, rows: Int) {
        val size = columns.coerceIn(1, 512) to rows.coerceIn(1, 256)
        lastSize = size
        resizeJob?.cancel()
        if (!state.value.controlled) return
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
