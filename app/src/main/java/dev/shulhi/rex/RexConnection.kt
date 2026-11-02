package dev.shulhi.rex

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.content.edit

internal class RexConnection(application: Application, private val scope: CoroutineScope, val savedEndpoint: String, private val preferences: SharedPreferences = application.getSharedPreferences("rex",0)) {
    var lastSessionId by mutableStateOf(preferences.getString("last_session:$savedEndpoint","").orEmpty())
        private set
    var paneFrames by mutableStateOf<Map<String,TerminalScreen>>(emptyMap())
        private set
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState
    private val mutableChrome=MutableStateFlow(AppState())
    val chromeState: StateFlow<AppState> = mutableChrome
    private val mutableTerminal=MutableStateFlow<TerminalScreen?>(null)
    val terminal: StateFlow<TerminalScreen?> = mutableTerminal
    var screenDimensions by mutableStateOf<Pair<Int,Int>?>(null)
        private set
    var keyboardRequest by mutableStateOf("")
        private set
    var hasScreen by mutableStateOf(false)
        private set
    var controlModifier by mutableStateOf(false)
        private set
    var altModifier by mutableStateOf(false)
        private set
    private var shiftState by mutableStateOf(ShiftKeyState())
    val shiftModifier: Boolean get()=shiftState.active
    var navigationRequest by mutableStateOf(NavigationRequest())
        private set
    private val navigation = WorkspaceNavigation()
    private val activation=PaneActivation()
    private var navigationSerial = 0L
    private var keyEpoch by mutableStateOf(0L)
    fun cancelKeyRepeats() {keyEpoch++}
    fun keyTarget(): String = "$keyEpoch:$navigationSerial:${state.value.selectedSession}:${state.value.selectedBlock}"
    fun keyForTarget(name: String,target: String) {if(target==keyTarget()) key(name)}
    fun activity(open: Boolean) = command("activity","open" to open)
    private var seen=runCatching {JSONObject(preferences.getString("activity_seen:$savedEndpoint","{}").orEmpty())}.getOrDefault(JSONObject())
    fun isActivitySeen(record: ActivityRecord)=record.attentionKey.isNotBlank() && seen.optString(record.attentionKey)==record.attentionVersion
    fun markActivitySeen(records: List<ActivityRecord>) {
        for(record in records) if(record.attentionKey.isNotBlank() && record.attentionVersion.isNotBlank()) seen.put(record.attentionKey,record.attentionVersion)
        if(seen.length()>2048) {val keys=seen.keys().asSequence().toList();keys.take(keys.size-2048).forEach {seen.remove(it)}}
        preferences.edit {putString("activity_seen:$savedEndpoint",seen.toString())};command("activity_seen","items" to seen)
    }
    private var handle = 0L
    private var resizeJob: Job? = null
    private var lastSize = 0 to 0
    @Volatile private var foreground = true
    @Volatile private var closed = false
    @Volatile var visible = true
        set(value) {if(field!=value){field=value;if(!value) {resetModifiers();cancelKeyRepeats();resizeJob?.cancel()} else activation.navigate();command("visibility","visible" to value)}}
    private var pollJob: Job? = null

    init {
        try {
            handle = NativeBridge.create()
            check(handle != 0L) { "Could not start native terminal" }
            command("activity_seen","items" to seen)
            pollJob = scope.launch(Dispatchers.IO) {
                val decoder=TerminalUpdates()
                while (!closed) {
                    if (!foreground) { delay(500); continue }
                    val update=NativeBridge.awaitUpdate(handle,decoder.revision,250)
                    if(update<0) break
                    if(update>decoder.revision) {
                        try {
                            android.os.Trace.beginSection("Rexlet.decodeUpdate")
                            val next=try {decoder.decode(NativeBridge.readUpdate(handle,decoder.revision))} finally {android.os.Trace.endSection()}
                            withContext(Dispatchers.Main.immediate) {
                                if(visible) awaitDisplayFrame()
                                navigationRequest = navigationRequest.acknowledge(next)
                                val frames=paneFrames.toMutableMap()
                                frames.putAll(next.previewFrames)
                                val old=state.value
                                if(old.selectedBlock!=next.selectedBlock && old.screen!=null && old.selectedBlock.isNotBlank()) frames[old.selectedBlock]=old.screen
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
                                if(old.controlled!=next.controlled || old.selectedBlock!=next.selectedBlock || old.phase!=next.phase) cancelKeyRepeats()
                                if(!next.controlled || old.selectedBlock!=next.selectedBlock) resetModifiers()
                                mutableState.value = next
                                mutableTerminal.value=next.screen
                                hasScreen=next.screen!=null
                                screenDimensions=next.screen?.let {it.columns to it.rows}
                                mutableChrome.value=next.copy(revision=0,screen=null,previewFrames=emptyMap())
                                rememberCurrentTerminal(); openPreferredTerminal();claimActivePane()
                            }
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main.immediate) {
                                navigationRequest = NavigationRequest()
                                setState(state.value.copy(error = "Could not read terminal state: ${error.message}"))
                            }
                        }
                    }

                }
            }
        } catch (error: Throwable) {
            setState(AppState(phase = "error", error = "Native terminal could not start: ${error.message}"))
        }
    }
    private fun setState(next: AppState) {mutableState.value=next;mutableChrome.value=next.copy(revision=0,screen=null,previewFrames=emptyMap())}
    private fun command(type: String, vararg args: Pair<String, Any>) {
        if (handle == 0L) return
        val request = JSONObject().put("type", type)
        args.forEach { (key, value) -> request.put(key, value) }
        val error = NativeBridge.command(handle, request.toString())
        if (error.isNotEmpty()) {
            navigationRequest = NavigationRequest()
            setState(state.value.copy(error=error))
        }
    }
    private fun resetModifiers() {controlModifier=false;altModifier=false;shiftState=ShiftKeyState()}
    fun connect(server: String) {
        cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        resetModifiers()
        val value = server.trim()
        navigationRequest = NavigationRequest()
        keyboardRequest=""
        paneFrames=emptyMap()
        lastSessionId=preferences.getString("last_session:$value","").orEmpty()
        preferences.edit { putString("endpoint", value) }
        command("connect", "server" to value)
    }
    fun disconnect() { cancelKeyRepeats();resetModifiers(); navigationRequest = NavigationRequest(); command("disconnect") }
    fun preview() = command("preview")
    fun selectSession(id: String) {
        if (id == (navigationRequest.session ?: state.value.selectedSession)) return
        val old=navigationRequest.session ?: state.value.selectedSession
        if(old.isNotBlank() && old!=id) {
            lastSessionId=old
            preferences.edit { putString("last_session:${state.value.endpoint}",old) }
        }
        cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        navigationRequest = NavigationRequest(session = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        command("session", "id" to id, "navigationId" to navigationRequest.id)
    }
    fun selectBlock(id: String) {
        val current = state.value
        if (navigationRequest.session != null && navigationRequest.session != current.selectedSession) return
        if (id == navigationRequest.block || (id == current.selectedBlock && !navigationRequest.pending)) return
        if (current.windows.none { window -> window.blocks.any { it.id == id } }) return
        cancelKeyRepeats();resizeJob?.cancel();activation.navigate()
        navigationRequest = NavigationRequest(block = id, id = ++navigationSerial)
        controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        navigation.remember(current.selectedSession, current.windows, id)
        command("block", "id" to id, "navigationId" to navigationRequest.id)
    }
    fun openActivity(session: String,block: String) {
        val current=state.value
        if(!visible || !current.connected || current.creating) return
        if(current.selectedSession.isNotBlank() && current.selectedSession!=session) {
            lastSessionId=current.selectedSession;preferences.edit {putString("last_session:${current.endpoint}",lastSessionId)}
        }
        cancelKeyRepeats();resizeJob?.cancel();activation.navigate();resetModifiers();keyboardRequest=""
        navigationRequest=NavigationRequest(session=session,block=block,id=++navigationSerial)
        command("session","id" to session,"block" to block,"navigationId" to navigationRequest.id)
    }
    fun tabPrograms(window: WindowItem): List<ProgramIdentity> = window.blocks.map {state.value.programs[it.id] ?: ProgramIdentity.Terminal}
    fun tabPaneIndex(window: WindowItem): Int = window.blocks.indexOfFirst {it.id==navigation.pane(state.value.selectedSession,window)}.coerceAtLeast(0)
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
        if(current.creating) return
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
    fun jumpLastSession() { if(state.value.sessions.any { it.id==lastSessionId }) selectSession(lastSessionId) }
    fun refreshSessionDetails() { if(state.value.connected) command("sessions_info") }
    fun prefetchPanes(ids: List<String>) {
        if(state.value.connected && !state.value.creating) command("peek","session" to state.value.selectedSession,"ids" to org.json.JSONArray(ids))
    }
    fun closeSession(id: String): Boolean {
        val current=state.value
        if(!current.connected || current.creating || navigationRequest.pending || current.sessions.none {it.id==id}) return false
        command("close_session","id" to id);return true
    }
    fun createSession(label: String) { if(state.value.connected && !state.value.creating && !navigationRequest.pending) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();command("new_session","label" to label)} }
    fun createTab() {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedSession.isNotBlank()) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();command("new_tab", "session" to current.selectedSession)}
    }
    fun createPane() {
        val current = state.value
        if (current.connected && !current.creating && !navigationRequest.pending && current.selectedBlock.isNotBlank()) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();command("new_pane", "session" to current.selectedSession, "anchor" to current.selectedBlock)}
    }
    fun closePane(session: String,id: String) {
        val current=state.value
        if(current.connected && !current.creating && !navigationRequest.pending && session==current.selectedSession) {
            if(id==current.selectedBlock) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();controlModifier=false;altModifier=false;shiftState=ShiftKeyState();keyboardRequest=""}
            command("close_pane","session" to session,"id" to id)
        }
    }
    fun closeTab(session: String,id: String,blocks: List<String>) {
        val current=state.value
        if(current.connected && !current.creating && !navigationRequest.pending && session==current.selectedSession) {
            if(current.selectedBlock in blocks) {activation.navigate();resizeJob?.cancel();cancelKeyRepeats();controlModifier=false;altModifier=false;shiftState=ShiftKeyState();keyboardRequest=""}
            command("close_tab","session" to session,"id" to id,"blocks" to org.json.JSONArray(blocks))
        }
    }
    fun refresh() = command("refresh")
    fun claimActivePane() {
        val current=state.value
        if(activation.shouldTake(current,navigationRequest,visible && foreground,lastSize)) sendTake(current)
    }
    private fun sendTake(current: AppState) = command("take","session" to current.selectedSession,"block" to current.selectedBlock,
        "navigationId" to current.navigationId,"columns" to lastSize.first,"rows" to lastSize.second)
    fun takeControl() {
        val current=state.value
        if(visible && current.connected && !current.creating && !navigationRequest.pending && current.selectedBlock.isNotBlank()) {
            keyboardRequest=current.selectedBlock;sendTake(current)
        }
    }
    fun keyboardShown() {keyboardRequest=""}
    fun wheel(lines: Int,col: Int,row: Int,cw: Int,ch: Int) {
        val current=state.value
        if(visible && current.connected && !current.creating && !navigationRequest.pending && current.screen!=null)
            command("wheel","session" to current.selectedSession,"block" to current.selectedBlock,"lines" to lines,"col" to col,"row" to row,"cw" to cw,"ch" to ch,"columns" to lastSize.first,"rows" to lastSize.second)
    }
    fun releaseControl() { activation.release();resizeJob?.cancel();cancelKeyRepeats();controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""; if(state.value.selectedBlock.isNotBlank()) command("release") }
    fun pause() {
        cancelKeyRepeats()
        navigationRequest = NavigationRequest()
        foreground = false; resizeJob?.cancel(); controlModifier = false; altModifier = false;shiftState=ShiftKeyState();keyboardRequest=""
        setState(state.value.copy(controlled = false, phase = if (state.value.connected) "paused" else state.value.phase))
        command("pause")
    }
    fun resume() { if(!foreground) activation.resume();foreground = true; command("resume") }
    fun toggleControl() { controlModifier = !controlModifier }
    fun toggleAlt() { altModifier = !altModifier }
    fun toggleShift() {shiftState=ShiftKeyState(latched=!shiftState.active)}
    fun pressShift() {shiftState=shiftState.press()}
    fun releaseShift(cancelled: Boolean) {shiftState=shiftState.release(cancelled)}
    fun controlShortcut(letter: String) {
        if(letter !in controlShortcuts || !visible || !state.value.controlled || state.value.creating || navigationRequest.pending) return
        resetModifiers();type(TerminalInput.modified(letter,control=true,alt=false))
    }
    fun type(text: String) {
        if (!visible || !state.value.controlled || state.value.creating || navigationRequest.pending || text.isEmpty()) return
        val input = TerminalInput.modified(text, controlModifier, altModifier, shiftModifier)
        controlModifier = false; altModifier = false;shiftState=shiftState.consume();keyboardRequest=""
        command("input", "text" to input)
    }
    fun scroll(lines: Int) {if(!navigationRequest.pending && state.value.screen!=null) command("scroll","block" to state.value.selectedBlock,"lines" to lines)}
    fun scrollToBottom() {if(!navigationRequest.pending && state.value.screen!=null) command("scroll","block" to state.value.selectedBlock,"bottom" to true)}
    fun key(name: String) = type(TerminalInput.key(name))
    fun paste(text: String) { if (visible && state.value.controlled && !navigationRequest.pending) command("paste", "text" to text) }
    fun resize(columns: Int, rows: Int) {
        val size = columns.coerceIn(1, 512) to rows.coerceIn(1, 256)
        lastSize = size
        resizeJob?.cancel()
        claimActivePane()
        val current=state.value
        if (!visible || !foreground || !current.controlled || navigationRequest.pending) return
        resizeJob = scope.launch {
            delay(150)
            command("resize", "session" to current.selectedSession,"block" to current.selectedBlock,"navigationId" to current.navigationId,
                "columns" to size.first, "rows" to size.second)
        }
    }
    fun applySize() = resize(lastSize.first.coerceAtLeast(1), lastSize.second.coerceAtLeast(1))
    fun close() {
        closed=true;pollJob?.cancel();resizeJob?.cancel()
        if (handle != 0L) NativeBridge.destroy(handle)
        handle = 0
    }
}
