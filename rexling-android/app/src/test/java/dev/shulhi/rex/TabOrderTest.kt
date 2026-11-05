package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class TabOrderTest {
    @Test fun relativeMovesPreserveEveryOtherTabAndSupportTheEnd() {
        val ids=listOf("a","b","c","d")
        assertEquals(listOf("c","a","b","d"),moveTabBefore(ids,"c","a"))
        assertEquals(listOf("a","c","d","b"),moveTabBefore(ids,"b",null))
        assertEquals(ids,moveTabBefore(ids,"a","b"))
        assertNull(moveTabBefore(ids,"a","a"));assertNull(moveTabBefore(ids,"missing",null))
        assertNull(moveTabBefore(ids,"a","missing"));assertNull(moveTabBefore(listOf("a","a"),"a",null))
    }
    @Test fun optimisticOrderSurvivesStalePacketsButEndsOnAcknowledgementOrTargetChange() {
        val windows=listOf("a","b","c").map {WindowItem(it,it,emptyList())}
        val state=AppState(phase="connected",selectedSession="s",windows=windows,tabMoveId=7)
        val pending=PendingTabOrder("s",9,listOf("c","a","b"))
        assertEquals(listOf("c","a","b"),pending.apply(state)?.map {it.id})
        assertNotNull(pending.keepFor(state.copy(tabMoveId=8,titles=mapOf("pane" to "Spinner"))))
        assertNull(pending.keepFor(state.copy(tabMoveId=9))) // success and failure both ack
        assertNull(pending.keepFor(state.copy(selectedSession="other")))
        assertNull(pending.keepFor(state.copy(phase="disconnected")))
        assertNull(pending.keepFor(state.copy(windows=windows.dropLast(1))))
        assertEquals("Renamed",pending.apply(state.copy(windows=windows.map {it.copy(label="Renamed")}))?.first()?.label)
    }
}
