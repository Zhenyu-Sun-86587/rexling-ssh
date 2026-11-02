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

    @Test fun swipesStayWithinTheirTabAndStopAtTheEdges() {
        val navigation = WorkspaceNavigation()
        assertEquals("b", navigation.neighbour(listOf(first, second), "a", 1))
        assertEquals("a", navigation.neighbour(listOf(first, second), "b", -1))
        assertNull(navigation.neighbour(listOf(first, second), "a", -1))
        assertNull(navigation.neighbour(listOf(first, second), "b", 1))
        assertNull(navigation.neighbour(listOf(first, second), "missing", 1))
        assertNull(navigation.neighbour(listOf(first, second), "a", 0))
    }

    @Test fun sessionSwipesFollowThePickerOrderAndStopAtTheEdges() {
        val navigation = WorkspaceNavigation()
        val sessions = listOf(SessionItem("one", "First"), SessionItem("two", "Second"), SessionItem("three", "Third"))
        assertEquals("two", navigation.neighbourSession(sessions, "one", 1))
        assertEquals("two", navigation.neighbourSession(sessions, "three", -1))
        assertNull(navigation.neighbourSession(sessions, "one", -1))
        assertNull(navigation.neighbourSession(sessions, "three", 1))
        assertNull(navigation.neighbourSession(sessions, "missing", 1))
        assertNull(navigation.neighbourSession(sessions, "one", 0))
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
