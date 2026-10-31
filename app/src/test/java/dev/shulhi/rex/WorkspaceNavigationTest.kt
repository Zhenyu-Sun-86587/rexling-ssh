package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class WorkspaceNavigationTest {
    private val first = WindowItem("one", "Editor", listOf(BlockItem("a", "Editor"), BlockItem("b", "Shell")))
    private val second = WindowItem("two", "Logs", listOf(BlockItem("c", "Logs")))

    @Test fun switchingTabsAndSessionsRestoresTheirOwnPane() {
        val navigation = WorkspaceNavigation()
        navigation.remember("session-a", listOf(first, second), "b")
        navigation.remember("session-a", listOf(first, second), "c")
        navigation.remember("session-b", listOf(first, second), "a")
        assertEquals("b", navigation.pane("session-a", first))
        assertEquals("c", navigation.pane("session-a", listOf(first, second)))
        assertEquals("a", navigation.pane("session-b", listOf(first, second)))
    }

    @Test fun closedPanesAndWindowsFallBackToExistingTerminal() {
        val navigation = WorkspaceNavigation()
        navigation.remember("session", listOf(first, second), "b")
        assertEquals("a", navigation.pane("session", first.copy(blocks = first.blocks.take(1))))
        assertEquals("c", navigation.pane("session", listOf(second)))
        assertEquals("c", navigation.pane("new-session", listOf(first.copy(blocks = emptyList()), second)))
        assertNull(navigation.pane("session", emptyList<WindowItem>()))
        assertNull(navigation.pane("session", first.copy(blocks = emptyList())))
    }
}
