package dev.shulhi.rex

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.roundToInt

data class TerminalMetrics(val cellWidth: Float, val cellHeight: Float, val baseline: Float)

/** Nerd Font Mono keeps icon glyphs and text on the same fixed grid on every device. */
class TerminalFonts(private val context: Context) {
    val regular: Typeface = context.resources.getFont(R.font.jetbrains_nerd_regular)
    val bold: Typeface = context.resources.getFont(R.font.jetbrains_nerd_bold)
    val italic: Typeface = context.resources.getFont(R.font.jetbrains_nerd_italic)
    val boldItalic: Typeface = context.resources.getFont(R.font.jetbrains_nerd_bold_italic)

    fun configure(paint: Paint, sizeSp: Float): TerminalMetrics {
        paint.typeface = regular
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
        paint.textScaleX = 1f
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.hinting = Paint.HINTING_ON
        paint.fontFeatureSettings = "'liga' 0, 'calt' 0, 'kern' 0"
        val advance = paint.measureText("M").coerceAtLeast(1f)
        val width = advance.roundToInt().coerceAtLeast(1).toFloat()
        // Match glyph advance to the integer pixel grid instead of adding tracking.
        paint.textScaleX = width / advance
        val metrics = paint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent).coerceAtLeast(1f)
        return TerminalMetrics(width, height, -metrics.ascent)
    }
}
