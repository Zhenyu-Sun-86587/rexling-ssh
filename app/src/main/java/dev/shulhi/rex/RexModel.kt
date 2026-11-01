package dev.shulhi.rex

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RexModel @JvmOverloads constructor(application: Application, preferenceName: String="rex") : AndroidViewModel(application) {
    private val preferences=application.getSharedPreferences(preferenceName,0)
    var appearance by mutableStateOf(runCatching {Appearance.valueOf(preferences.getString("appearance","SYSTEM")!!)}.getOrDefault(Appearance.SYSTEM))
        private set
    var servers by mutableStateOf(SavedServers.decode(preferences.getString("servers",null),preferences.getString("endpoint","https://rex.taildaac1.ts.net")!!))
        private set
    var activeServerId by mutableStateOf(preferences.getString("active_server",null)?.takeIf {id->servers.any {it.id==id}} ?: servers.firstOrNull()?.id.orEmpty())
        private set
    var serverStatuses by mutableStateOf<Map<String,ServerStatus>>(emptyMap())
        private set
    internal var sessionTargets by mutableStateOf<List<SessionTarget>>(emptyList())
        private set
    internal var lastSessionKey by mutableStateOf(preferences.getString("last_global_session","").orEmpty())
        private set
    private val connections=mutableMapOf<String,RexConnection>()
    private val observers=mutableMapOf<String,Job>()
    private var desired=preferences.getStringSet("connected_servers",emptySet()).orEmpty().filter {id->servers.any {it.id==id}}.toSet()
    private var palette: TerminalPalette?=null
    private var foreground=true
    internal var activeClient by mutableStateOf(connection(activeServer))
        private set
    val activeServer: ServerProfile get()=servers.firstOrNull {it.id==activeServerId} ?: ServerProfile.Empty
    val connectedCount: Int get()=serverStatuses.values.count {it.connected}

    init {desired.toList().forEach {id->connectServer(id,select=false)}}

    private fun connection(profile: ServerProfile): RexConnection = connections.getOrPut(profile.id) {
        RexConnection(getApplication(),viewModelScope,profile.endpoint,preferences).also {client->
            client.visible=profile.id==activeServerId
            palette?.let(client::updateTerminalTheme)
            if(!foreground) client.pause()
            observers[profile.id]=viewModelScope.launch {
                client.chromeState.collect {state->
                    val next=ServerStatus(state.phase,state.error)
                    if(serverStatuses[profile.id]!=next) serverStatuses=serverStatuses+(profile.id to next)
                    updateSessionTargets()
                }
            }
        }
    }
    private fun updateSessionTargets() {
        val next=SessionCatalog.collect(servers,connections.mapValues {it.value.state.value})
        if(next!=sessionTargets) sessionTargets=next
    }
    private fun rememberLastSession() {
        val session=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
        if(session.isNotBlank()) {
            lastSessionKey=sessionKey(activeServerId,session)
            preferences.edit {putString("last_global_session",lastSessionKey)}
        }
    }
    internal fun selectSessionTarget(key: String) {
        val target=sessionTargets.firstOrNull {it.key==key} ?: return
        if(activeServerId==target.server.id) {
            val current=activeClient.navigationRequest.session ?: activeClient.state.value.selectedSession
            if(current==target.session.id) return
            rememberLastSession()
        }
        connectServer(target.server.id)
        activeClient.selectSession(target.session.id)
    }
    internal fun jumpLastSession() {
        if(sessionTargets.any {it.key==lastSessionKey}) selectSessionTarget(lastSessionKey)
        else activeClient.jumpLastSession()
    }
    internal fun refreshSessionDetails() {connections.values.filter {it.state.value.connected}.forEach {it.refreshSessionDetails()}}
    private fun persist() {updateSessionTargets();preferences.edit {
        putString("servers",SavedServers.encode(servers));putString("active_server",activeServerId)
        putStringSet("connected_servers",desired)
    }}
    fun connectServer(id: String,select: Boolean=true) {
        val profile=servers.firstOrNull {it.id==id} ?: return
        val client=connection(profile)
        if(select && activeServerId!=id) {
            rememberLastSession()
            activeClient.releaseControl();activeClient.visible=false
            client.visible=true;activeServerId=id;activeClient=client
        }
        desired=desired+id;persist()
        if(client.state.value.phase in listOf("disconnected","error","preview")) client.connect(profile.endpoint)
        else if(foreground && client.state.value.phase=="paused") client.resume()
    }
    /** The legacy URL form also adds a new host without disconnecting existing ones. */
    fun connect(endpoint: String): String? = runCatching {
        val normalized=ServerProfile.normalizeEndpoint(endpoint)
        val existing=servers.firstOrNull {it.endpoint==normalized}
        if(existing!=null) connectServer(existing.id)
        else saveServer(null,"",normalized)?.let {throw IllegalArgumentException(it)}
    }.exceptionOrNull()?.message

    fun saveServer(id: String?,label: String,endpoint: String): String? = runCatching {
        val profile=ServerProfile.create(label,endpoint,id ?: java.util.UUID.randomUUID().toString())
        require(servers.none {it.id!=profile.id && it.endpoint==profile.endpoint}) {"That server is already saved"}
        val old=servers.firstOrNull {it.id==profile.id}
        if(old!=null && old.endpoint!=profile.endpoint) {
            connections.remove(old.id)?.let {it.releaseControl();it.disconnect();it.close()}
            observers.remove(old.id)?.cancel();serverStatuses=serverStatuses-old.id
        }
        servers=if(old==null) servers+profile else servers.map {if(it.id==profile.id) profile else it}
        persist()
        val client=connection(profile)
        if(activeServerId==profile.id) {activeClient=client;client.visible=true}
        connectServer(profile.id)
    }.exceptionOrNull()?.message

    fun disconnectServer(id: String) {
        desired=desired-id;persist()
        connections[id]?.let {it.releaseControl();it.disconnect()}
    }
    fun forgetServer(id: String) {
        disconnectServer(id)
        observers.remove(id)?.cancel();connections.remove(id)?.close()
        servers=servers.filterNot {it.id==id};serverStatuses=serverStatuses-id
        if(activeServerId==id) {
            activeServerId=servers.firstOrNull()?.id.orEmpty()
            activeClient=connection(activeServer);activeClient.visible=true
        }
        persist()
    }
    fun previewServer(id: String) {
        if(id!=activeServerId) return
        desired=desired-id;persist();activeClient.preview()
    }
    fun updateTerminalTheme(value: TerminalPalette) {palette=value;connections.values.forEach {it.updateTerminalTheme(value)}}
    fun changeAppearance(value: Appearance) {appearance=value;preferences.edit {putString("appearance",value.name)}}
    fun pause() {foreground=false;connections.values.forEach {it.pause()}}
    fun resume() {foreground=true;connections.values.forEach {it.resume()}}
    override fun onCleared() {observers.values.forEach {it.cancel()};connections.values.forEach {it.close()}}
}
