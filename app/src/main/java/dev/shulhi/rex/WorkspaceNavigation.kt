package dev.shulhi.rex

/** In-memory history; closed tabs/panes fall back to an existing terminal. */
class WorkspaceNavigation {
    private val windows = mutableMapOf<String, String>()
    private val panes = mutableMapOf<Pair<String, String>, String>()

    fun neighbourSession(available: List<SessionItem>, session: String, step: Int): String? {
        if (step != -1 && step != 1) return null
        val index = available.indexOfFirst { it.id == session }
        return if (index < 0) null else available.getOrNull(index + step)?.id
    }

    fun neighbour(available: List<WindowItem>, block: String, step: Int): String? {
        if (step != -1 && step != 1) return null
        val window = available.firstOrNull { it.blocks.any { pane -> pane.id == block } } ?: return null
        val index = window.blocks.indexOfFirst { it.id == block }
        return window.blocks.getOrNull(index + step)?.id
    }

    fun remember(session: String, available: List<WindowItem>, block: String) {
        val window = available.firstOrNull { it.blocks.any { pane -> pane.id == block } } ?: return
        windows[session] = window.id
        panes[session to window.id] = block
    }

    fun pane(session: String, window: WindowItem): String? =
        window.blocks.firstOrNull { it.id == panes[session to window.id] }?.id ?: window.blocks.firstOrNull()?.id

    fun pane(session: String, available: List<WindowItem>): String? {
        val window = available.firstOrNull { it.id == windows[session] && it.blocks.isNotEmpty() }
            ?: available.firstOrNull { it.blocks.isNotEmpty() } ?: return null
        return pane(session, window)
    }
}
