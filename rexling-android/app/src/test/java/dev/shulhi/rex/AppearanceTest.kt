package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    private fun cell(flags: Int,fg: Int=0xff123456.toInt(),bg: Int=0xff654321.toInt())=TerminalCell(0,0,"X",fg,bg,flags,1)
    @Test fun defaultColorsFollowAppearanceAndExplicitRgbIsPreserved() {
        for(theme in listOf(TerminalPalette.Dark,TerminalPalette.Light)) {
            assertEquals(theme.foreground,theme.foreground(cell(128)))
            assertEquals(theme.background,theme.background(cell(256)))
            assertEquals(0xff123456.toInt(),theme.foreground(cell(0)))
            assertEquals(0xff654321.toInt(),theme.background(cell(0)))
        }
        assertNotEquals(TerminalPalette.Dark.background,TerminalPalette.Light.background)
    }
    @Test fun ansiColorsUseTheirIndexAndExtendedColorsStayExact() {
        val fg=cell(512 or (2 shl 11))
        val bg=cell(1024 or (4 shl 19))
        for(theme in listOf(TerminalPalette.Dark,TerminalPalette.Light)) {
            assertEquals(theme.ansi[2],theme.foreground(fg))
            assertEquals(theme.ansi[4],theme.background(bg))
            assertEquals(0xff123456.toInt(),theme.foreground(cell(512 or (200 shl 11))))
        }
    }
    @Test fun coloredDirectoryTextDoesNotCollapseIntoItsBackground() {
        for(theme in listOf(TerminalPalette.Dark,TerminalPalette.Light)) {
            // ls uses SGR 34;42 for other-writable directories: blue on green.
            for((fg,bg) in listOf(4 to 2,12 to 10,2 to 4,10 to 12)) {
                val directory=cell(512 or (fg shl 11) or 1024 or (bg shl 19))
                assertNotEquals(theme.foreground(directory),theme.background(directory))
            }
            assertEquals("Keep every ANSI role distinct",16,theme.ansi.toSet().size)
        }
    }
}
