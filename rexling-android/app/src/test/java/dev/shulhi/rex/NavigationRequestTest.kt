package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class NavigationRequestTest {
    private val emptyScreen = TerminalScreen(80, 24, null, null, -1, 0xff000000.toInt(), emptyList())
    @Test fun recoveryRequiresTheExactNavigationAndAReadyScreen() {
        val request=NavigationRequest(session="session",block="pane",id=8)
        val current=AppState(phase="connected",selectedSession="session",selectedBlock="pane",navigationId=8,screen=emptyScreen)
        assertTrue(request.matchesTarget(current))
        assertFalse(request.matchesTarget(current.copy(navigationId=7)))
        assertFalse(request.matchesTarget(current.copy(selectedBlock="other")))
        assertFalse(request.matchesTarget(current.copy(screen=null)))
        assertFalse(request.matchesTarget(current.copy(phase="disconnected")))
    }
    @Test fun slowOlderRepliesAndUnrelatedErrorsNeverRevertLatestIntent() {
        val request=NavigationRequest(session="s",block="last",id=9)
        for(old in listOf(
            AppState(phase="connected",selectedSession="s",selectedBlock="previous",navigationId=8,screen=emptyScreen),
            AppState(phase="connected",selectedSession="s",selectedBlock="previous",navigationId=8,error="older RPC timeout")
        )) assertEquals(request,request.acknowledge(old))
        assertFalse(request.acknowledge(AppState(phase="connected",selectedSession="s",selectedBlock="last",navigationId=9,screen=emptyScreen)).pending)
    }
    @Test fun anEarlierTabReplyCannotClearTheLatestTap() {
        val pending = NavigationRequest(block = "latest")
        val oldReply = AppState(phase = "connected", selectedBlock = "earlier", screen = emptyScreen)
        assertEquals(pending, pending.acknowledge(oldReply))
        assertFalse(pending.acknowledge(oldReply.copy(selectedBlock = "latest")).pending)
    }
    @Test fun inputWaitsForTheNewSnapshotRatherThanOnlyTheBlockId() {
        val pending = NavigationRequest(block = "target")
        val loading = AppState(phase = "connected", selectedBlock = "target")
        assertTrue(pending.acknowledge(loading).pending)
        assertFalse(pending.acknowledge(loading.copy(screen = emptyScreen)).pending)
    }
    @Test fun sessionRepliesAndErrorsResolveOnlyTheRelevantIntent() {
        val pending = NavigationRequest(session = "new")
        val prior = AppState(phase = "connected", selectedSession = "old")
        assertEquals(pending, pending.acknowledge(prior.copy(revision = 3)))
        assertFalse(pending.acknowledge(prior.copy(selectedSession = "new")).pending)
        assertTrue(pending.acknowledge(prior.copy(error = "older request failed")).pending)
        assertFalse(pending.acknowledge(AppState()).pending)
    }
    @Test fun returningToTheOriginalTabWaitsForItsOwnAcknowledgement() {
        val request = NavigationRequest(block = "original", id = 3)
        val old = AppState(phase = "connected", selectedBlock = "original", screen = emptyScreen, navigationId = 1)
        assertTrue(request.acknowledge(old).pending)
        assertTrue(request.acknowledge(old.copy(selectedBlock = "middle", navigationId = 2)).pending)
        assertFalse(request.acknowledge(old.copy(navigationId = 3)).pending)
    }

}
