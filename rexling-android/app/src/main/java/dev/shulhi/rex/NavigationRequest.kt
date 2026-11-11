package dev.shulhi.rex

/** Keep the last user intent visible until that terminal is actually ready. */
data class NavigationRequest(val session: String? = null, val block: String? = null, val id: Long = 0) {
    val pending: Boolean get() = session != null || block != null
    fun matchesTarget(next: AppState): Boolean = next.connected && next.navigationId==id &&
        (session==null || next.selectedSession==session) && (block==null || next.selectedBlock==block) && next.screen!=null
    fun acknowledge(next: AppState): NavigationRequest {
        if (next.phase == "disconnected") return NavigationRequest()
        if (next.navigationId != id) return this
        // Errors can belong to an earlier RPC. Keep the user's current intent
        // until the selected target actually acknowledges it or is explicitly replaced.
        return copy(
            session = session?.takeUnless { next.selectedSession == it },
            block = block?.takeUnless { next.selectedBlock == it && next.screen != null },
        )
    }
}
