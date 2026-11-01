package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class SessionCatalogTest {
    private val linux=ServerProfile.create("Linux","https://linux","a")
    private val mac=ServerProfile.create("Mac","https://mac","b")
    @Test fun serverBoundariesArePartOfOneOrderedStrip() {
        val targets=SessionCatalog.collect(listOf(linux,mac),mapOf(
            "a" to AppState(phase="connected",sessions=listOf(SessionItem("1","first"),SessionItem("2","second"))),
            "b" to AppState(phase="connected",sessions=listOf(SessionItem("1","other host")))))
        assertEquals(listOf("Linux · first","Linux · second","Mac · other host"),targets.map {it.stripItem.label})
        assertEquals(3,targets.map {it.key}.toSet().size)
        assertEquals("b",targets[targets.indexOfFirst {it.session.id=="2"}+1].server.id)
        assertNotEquals(sessionKey("a","bc"),sessionKey("ab","c"))
    }
    @Test fun disconnectedHostsAreExcludedAndCurrentLoadingLocationIsPreserved() {
        val current=SessionTarget(linux,SessionItem("","connecting"))
        val targets=SessionCatalog.collect(listOf(linux,mac),mapOf(
            "a" to AppState(phase="paused",sessions=listOf(SessionItem("old","stale"))),
            "b" to AppState(phase="connected",sessions=listOf(SessionItem("1","shell")))))
        assertEquals(listOf("b"),targets.map {it.server.id})
        assertEquals(listOf(current.key,targets.single().key),SessionCatalog.strip(targets,current).map {it.id})
        assertEquals(1,SessionCatalog.strip(targets,targets.single()).size)
    }
    @Test fun attachmentAndCountsAreTakenFromTheCorrectHost() {
        val targets=SessionCatalog.collect(listOf(linux,mac),mapOf(
            "a" to AppState(phase="connected",sessions=listOf(SessionItem("same","Linux")),selectedSession="same",windows=listOf(WindowItem("w","tab",listOf(BlockItem("p","shell"))))),
            "b" to AppState(phase="connected",sessions=listOf(SessionItem("same","Mac")),sessionDetails=mapOf("same" to SessionDetail("on laptop",3,4)))))
        assertEquals(SessionDetail("this device",1,1),targets[0].detail)
        assertEquals(SessionDetail("on laptop",3,4),targets[1].detail)
    }
}
