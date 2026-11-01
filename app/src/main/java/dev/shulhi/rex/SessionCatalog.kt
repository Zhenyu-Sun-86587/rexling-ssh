package dev.shulhi.rex

/** IDs are local to a server. Only actual session IDs are sent over the wire. */
internal data class SessionTarget(val server: ServerProfile,val session: SessionItem,val detail: SessionDetail?=null) {
    val key: String get()=sessionKey(server.id,session.id)
    val stripItem: SessionItem get()=SessionItem(key,"${server.label} · ${session.label}")
}
internal fun sessionKey(serverId: String,sessionId: String): String="${serverId.length}:$serverId$sessionId"

internal object SessionCatalog {
    fun collect(servers: List<ServerProfile>,states: Map<String,AppState>): List<SessionTarget> = servers.flatMap {server->
        val state=states[server.id]
        if(state?.connected!=true) emptyList() else state.sessions.map {session->
            val detail=if(session.id==state.selectedSession)
                SessionDetail("this device",state.windows.size,state.windows.sumOf {it.blocks.size}) else state.sessionDetails[session.id]
            SessionTarget(server,session,detail)
        }
    }
    fun strip(targets: List<SessionTarget>,active: SessionTarget): List<SessionItem> =
        (if(targets.none {it.key==active.key}) listOf(active.stripItem) else emptyList())+targets.map {it.stripItem}
}
