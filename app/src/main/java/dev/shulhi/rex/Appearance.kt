package dev.shulhi.rex

import androidx.compose.runtime.staticCompositionLocalOf

enum class Appearance { SYSTEM, DARK, LIGHT }

data class TerminalPalette(val dark: Boolean, val background: Int, val foreground: Int, val accent: Int, val ansi: List<Int>) {
    fun foreground(cell: TerminalCell): Int = when {
        cell.attributes and 128 != 0 -> foreground
        cell.attributes and 512 != 0 -> ansi.getOrNull((cell.attributes ushr 11) and 255) ?: cell.foreground
        else -> cell.foreground
    }
    fun background(cell: TerminalCell): Int = when {
        cell.attributes and 256 != 0 -> background
        cell.attributes and 1024 != 0 -> ansi.getOrNull((cell.attributes ushr 19) and 255) ?: cell.background
        else -> cell.background
    }
    companion object {
        private fun rgb(value: Long) = (0xff000000 or value).toInt()
        val Dark = TerminalPalette(true, rgb(0x11120f), rgb(0xd9dccf), rgb(0xcaf36b),
            listOf(0x252720,0xe48079,0xcaf36b,0xe6bd65,0x8bb5ff,0xcda2df,0x8bc7c0,0xd9dccf,0x777b6b,0xf39a91,0xd6ff86,0xf4cf80,0xa8c8ff,0xe0b9ed,0xa4dfd4,0xf2f4e9).map { rgb(it.toLong()) })
        val Light = TerminalPalette(false, rgb(0xffffff), rgb(0x202432), rgb(0x3156f4),
            listOf(0x202432,0xc03e47,0x3156f4,0xa65b00,0x3156f4,0x873bff,0x087d91,0xe3e5ee,0x62697c,0xd13f50,0x3156f4,0x995200,0x3156f4,0x873bff,0x007d91,0xf4f5fa).map { rgb(it.toLong()) })
    }
}
internal val LocalTerminalPalette = staticCompositionLocalOf { TerminalPalette.Dark }
