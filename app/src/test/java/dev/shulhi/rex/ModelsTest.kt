package dev.shulhi.rex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun parsesWideCellsAndColorWithoutDuplicatingTheSpacer() {
        val screen = TerminalScreen.parse(JSONObject("""{"columns":4,"rows":1,"cursor":[3,0],"foreground":16777215,"background":0,"cells":[[0,0,"A",16777215,0,0,1],[1,0,"🦀",16777215,0,1,2],[2,0,"",16777215,0,0,0],[3,0,"",16777215,0,0,1]]}"""))
        assertEquals("A🦀", screen.plainText())
        assertEquals(-1, screen.foreground)
        assertEquals(0xff000000.toInt(), screen.background)
        assertEquals(3, screen.cursorX)
    }
    @Test fun controlAndAltProduceTerminalInput() {
        assertEquals("\u0003", TerminalInput.modified("c", control = true, alt = false))
        assertEquals("\u001bx", TerminalInput.modified("x", control = false, alt = true))
        assertEquals("λ🦀", TerminalInput.modified("λ🦀", control = false, alt = false))
        assertEquals("\u001b[A", TerminalInput.key("up"))
        assertEquals("\r", TerminalInput.key("enter"))
    }
    @Test fun parsesHistoryViewportAndKeepsOldFramesCompatible() {
        val screen=TerminalScreen.parse(JSONObject("""{"columns":2,"rows":2,"foreground":0,"background":0,"cells":[],"scrollbar":[100,25,20]}"""))
        assertEquals(80L,screen.scroll.available)
        assertEquals(55L,screen.scroll.below)
        assertEquals(0L,TerminalScroll().available)
        assertEquals("\u001b[5~",TerminalInput.key("pageup"))
    }
    @Test fun disconnectedStateAllowsMissingOptionalCollections() {
        val state = AppState.parse("""{"revision":1,"phase":"disconnected"}""")
        assertFalse(state.connected)
        assertTrue(state.sessions.isEmpty())
        assertNull(state.screen)
    }
    @Test fun functionKeysUseTheStandardTerminalSequences() {
        val expected=listOf("OP","OQ","OR","OS","[15~","[17~","[18~","[19~","[20~","[21~","[23~","[24~")
        for ((index,sequence) in expected.withIndex()) assertEquals("\u001b$sequence",TerminalInput.key("f${index+1}"))
        assertEquals("\u001b[2~",TerminalInput.key("insert"))
    }

}
