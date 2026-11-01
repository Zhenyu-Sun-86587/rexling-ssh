package dev.shulhi.rex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.floor
import kotlin.math.max

class TerminalView(context: Context) : View(context) {
    var onInput: (String) -> Unit = {}
    var onDimensions: (Int, Int) -> Unit = { _, _ -> }
    var screen: TerminalScreen? = null
        set(value) { field = value; invalidate() }
    var controlled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) composing = ""
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            }
        }
    var textSp: Float = 10f
        set(value) { if (field != value) { field = value; metrics(); invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normal = Typeface.create("monospace", Typeface.NORMAL)
    private val bold = Typeface.create("monospace", Typeface.BOLD)
    private val italic = Typeface.create("monospace", Typeface.ITALIC)
    private val boldItalic = Typeface.create("monospace", Typeface.BOLD_ITALIC)
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    private var dimensions = 0 to 0
    private var composing = ""

    init {
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = "Terminal output. Tap to type after taking control."
        metrics()
    }
    private fun metrics() {
        @Suppress("DEPRECATION")
        val pixels = textSp * resources.displayMetrics.scaledDensity
        paint.textSize = pixels; paint.typeface = normal
        cellWidth = paint.measureText("M").coerceAtLeast(1f)
        cellHeight = kotlin.math.ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent + 2f)
        baseline = -paint.fontMetrics.ascent + 1f
        reportDimensions()
    }
    private fun reportDimensions() {
        val next = max(1, floor(width / cellWidth).toInt()) to max(1, floor(height / cellHeight).toInt())
        if (width > 0 && height > 0 && next != dimensions) { dimensions = next; onDimensions(next.first, next.second) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { reportDimensions() }
    override fun onDraw(canvas: Canvas) {
        val frame = screen ?: return
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(frame.background)
        // Draw all backgrounds first; spacer cells must not overwrite a wide glyph.
        for (cell in frame.cells) {
            val inverse = cell.attributes and 8 != 0
            paint.color = if (inverse) cell.foreground else cell.background
            canvas.drawRect(cell.x * cellWidth, cell.y * cellHeight, (cell.x + max(1, cell.width)) * cellWidth, (cell.y + 1) * cellHeight, paint)
        }
        for (cell in frame.cells) {
            if (cell.width == 0 || cell.text.isEmpty() || cell.attributes and 16 != 0) continue
            val x = cell.x * cellWidth; val y = cell.y * cellHeight
            if (x >= width || y >= height) continue
            paint.color = if (cell.attributes and 8 != 0) cell.background else cell.foreground
            paint.alpha = if (cell.attributes and 4 != 0) 150 else 255
            paint.typeface = when (cell.attributes and 3) { 1 -> bold; 2 -> italic; 3 -> boldItalic; else -> normal }
            canvas.save(); canvas.clipRect(x, y, x + cell.width * cellWidth, y + cellHeight)
            canvas.drawText(cell.text, x, y + baseline, paint)
            paint.strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
            if (cell.attributes and 64 != 0) canvas.drawLine(x, y + baseline + 2, x + cell.width * cellWidth, y + baseline + 2, paint)
            if (cell.attributes and 32 != 0) canvas.drawLine(x, y + cellHeight / 2, x + cell.width * cellWidth, y + cellHeight / 2, paint)
            canvas.restore(); paint.alpha = 255
        }
        if (frame.cursorX != null && frame.cursorY != null) {
            paint.color = frame.foreground; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            val x = frame.cursorX * cellWidth; val y = frame.cursorY * cellHeight
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, paint); paint.style = Paint.Style.FILL
            if (composing.isNotEmpty()) {
                paint.typeface = normal
                canvas.drawText(composing, x, y + baseline, paint)
                canvas.drawLine(x, y + baseline + 2, x + paint.measureText(composing), y + baseline + 2, paint)
            }
        }
        canvas.restoreToCount(saved)
    }
    fun keyboard() {
        if (!controlled) return
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) { performClick(); keyboard() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onCheckIsTextEditor() = controlled
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!controlled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = ""; if (text != null) onInput(text.toString()); invalidate(); return true }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); invalidate(); return true }
            override fun finishComposingText(): Boolean { if (composing.isNotEmpty()) onInput(composing); composing = ""; invalidate(); return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) { composing = composing.dropLast(beforeLength.coerceAtMost(composing.length)); invalidate(); return true }
                repeat(beforeLength.coerceAtMost(128)) { onInput("\u007f") }
                repeat(afterLength.coerceAtMost(128)) { onInput("\u001b[3~") }; return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) finishComposingText()
                return onKeyDown(event.keyCode, event)
            }
            override fun performEditorAction(actionCode: Int): Boolean { finishComposingText(); onInput("\r"); return true }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!controlled) return super.onKeyDown(keyCode, event)
        val named = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_DEL -> "backspace"; KeyEvent.KEYCODE_FORWARD_DEL -> "delete"
            KeyEvent.KEYCODE_TAB -> "tab"; KeyEvent.KEYCODE_ESCAPE -> "esc"
            KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"; KeyEvent.KEYCODE_MOVE_END -> "end"
            else -> null
        }
        if (named != null) { onInput(TerminalInput.key(named)); return true }
        val point = event.unicodeChar
        if (point != 0 && point and KeyCharacterMapCompat.COMBINING_ACCENT == 0) {
            onInput(TerminalInput.modified(String(Character.toChars(point)), event.isCtrlPressed, event.isAltPressed)); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
private object KeyCharacterMapCompat { const val COMBINING_ACCENT: Int = Int.MIN_VALUE }
