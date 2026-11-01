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
        val Light = TerminalPalette(false, rgb(0xffffff), rgb(0x282a23), rgb(0x507b00),
            listOf(0x303329,0xad3c36,0x507b00,0xa76600,0x2b62ce,0x8b459d,0x087b78,0xdddcd4,0x777a6c,0xb9322d,0x527c00,0x966000,0x285acd,0x923d9d,0x067671,0xf2f1ec).map { rgb(it.toLong()) })
    }
}
internal val LocalTerminalPalette = staticCompositionLocalOf { TerminalPalette.Dark }
