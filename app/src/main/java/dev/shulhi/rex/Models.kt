package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject

data class SessionItem(val id: String, val label: String)
data class SessionDetail(val attachment: String, val tabs: Int, val panes: Int)
data class BlockItem(val id: String, val label: String)
data class WindowItem(val id: String, val label: String, val blocks: List<BlockItem>)
data class TerminalScroll(val total: Long=0,val offset: Long=0,val visible: Long=0) {
    val available: Long get()=(total-visible).coerceAtLeast(0)
    val below: Long get()=(available-offset).coerceAtLeast(0)
}
data class TerminalCell(val x: Int, val y: Int, val text: String, val foreground: Int, val background: Int, val attributes: Int, val width: Int)
data class TerminalScreen(val columns: Int, val rows: Int, val cursorX: Int?, val cursorY: Int?, val foreground: Int, val background: Int, val cells: List<TerminalCell>, val scroll: TerminalScroll = TerminalScroll(), val lines: List<List<TerminalCell>> = cells.groupBy {it.y}.let {groups->List(rows){groups[it].orEmpty()}}, val inputFlags: Int=0) {
    val applicationScroll: Boolean get()=inputFlags and 1!=0 || inputFlags and 6==6
    fun plainText(): String = (0 until rows).joinToString("\n") { y ->
        cells.asSequence().filter { it.y == y && it.width > 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()
    }.trimEnd()
    companion object {
        fun parse(value: JSONObject): TerminalScreen {
            val columns = value.getInt("columns").coerceIn(1, 65535)
            val rows = value.getInt("rows").coerceIn(1, 65535)
            val cursor = value.optJSONArray("cursor")
            val cells = value.optJSONArray("cells").objects { cell ->
                val a = cell as JSONArray
                TerminalCell(a.getInt(0), a.getInt(1), a.getString(2), argb(a.getInt(3)), argb(a.getInt(4)), a.getInt(5), a.getInt(6))
            }
            val scroll=value.optJSONArray("scrollbar")
            return TerminalScreen(columns, rows, cursor?.getInt(0), cursor?.getInt(1), argb(value.getInt("foreground")), argb(value.getInt("background")), cells,
                TerminalScroll(scroll?.optLong(0) ?: 0,scroll?.optLong(1) ?: 0,scroll?.optLong(2) ?: 0),inputFlags=value.optInt("input_flags"))
        }
    }
}
data class AppState(
    val programs: Map<String, ProgramIdentity> = emptyMap(),
    val titles: Map<String, String> = emptyMap(),
    val directories: Map<String, String> = emptyMap(),
    val previewFrames: Map<String, TerminalScreen> = emptyMap(), val sessionDetails: Map<String, SessionDetail> = emptyMap(),
    val revision: Long = 0, val phase: String = "disconnected", val endpoint: String = "",
    val identity: String = "", val error: String = "", val notice: String = "",
    val sessions: List<SessionItem> = emptyList(), val windows: List<WindowItem> = emptyList(),
    val selectedSession: String = "", val selectedBlock: String = "", val controlled: Boolean = false,
    val screen: TerminalScreen? = null, val creating: Boolean = false, val navigationId: Long = 0,
) {
    val connected: Boolean get() = phase == "connected"
    companion object {
        fun parse(text: String): AppState {
            val root = JSONObject(text)
            return AppState(
                titles = root.optJSONObject("titles")?.let { titles -> titles.keys().asSequence().associateWith { id ->
                    titles.optString(id).filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }.take(256)
                } } ?: emptyMap(),
                directories = root.optJSONObject("directories")?.let { directories -> directories.keys().asSequence().associateWith { id ->
                    directories.optString(id).filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }.take(1024)
                } } ?: emptyMap(),
                programs = root.optJSONObject("programs")?.let { programs -> programs.keys().asSequence().associateWith { id ->
                    ProgramIdentity.resolve(programs.optJSONArray(id).objects { it as String })
                } } ?: emptyMap(),
                previewFrames = root.optJSONObject("previews")?.let { frames -> frames.keys().asSequence().associateWith { TerminalScreen.parse(frames.getJSONObject(it)) } } ?: emptyMap(),
                sessionDetails = root.optJSONObject("sessionDetails")?.let { details -> details.keys().asSequence().associateWith { key ->
                    val d=details.getJSONObject(key);SessionDetail(d.optString("attachment"),d.optInt("tabs"),d.optInt("panes")) } } ?: emptyMap(),
                revision = root.optLong("revision"), navigationId = root.optLong("navigationId"), phase = root.optString("phase", "disconnected"),
                endpoint = root.optString("endpoint"), identity = root.optString("identity"),
                error = root.optString("error"), notice = root.optString("notice"),
                sessions = root.optJSONArray("sessions").objects { value ->
                    val s = value as JSONObject; SessionItem(s.getString("session_id"), s.optString("label", "Session"))
                },
                windows = root.optJSONArray("windows").objects { value ->
                    val w = value as JSONObject
                    WindowItem(w.optString("id"), w.optString("label", "Window"), w.optJSONArray("blocks").objects { item ->
                        val b = item as JSONObject; BlockItem(b.getString("id"), b.optString("label", "Terminal"))
                    })
                },
                selectedSession = root.optString("selectedSession"), selectedBlock = root.optString("selectedBlock"),
                controlled = root.optBoolean("controlled"), creating = root.optBoolean("creating"), screen = root.optJSONObject("screen")?.let(TerminalScreen::parse),
            )
        }
    }
}
private fun argb(rgb: Int): Int = rgb or (0xff shl 24)
private fun <T> JSONArray?.objects(map: (Any) -> T): List<T> = if (this == null) emptyList() else (0 until length()).map { map(get(it)) }

object TerminalInput {
    fun modified(text: String, control: Boolean, alt: Boolean): String {
        val input = if (control && text.length == 1 && text[0].code in 0x40..0x7f) ((text[0].code and 0x1f).toChar()).toString() else text
        return if (alt) "\u001b$input" else input
    }
    fun key(name: String): String = when (name) {
        "esc" -> "\u001b"; "tab" -> "\t"; "enter" -> "\r"; "backspace" -> "\u007f"
        "pageup" -> "\u001b[5~"; "pagedown" -> "\u001b[6~"
        "up" -> "\u001b[A"; "down" -> "\u001b[B"; "right" -> "\u001b[C"; "left" -> "\u001b[D"
        "home" -> "\u001b[H"; "end" -> "\u001b[F"; "delete" -> "\u001b[3~"
        else -> ""
    }
}
