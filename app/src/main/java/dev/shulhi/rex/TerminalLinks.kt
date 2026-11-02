package dev.shulhi.rex

import java.net.URI

internal data class TerminalLink(val row: Int,val start: Int,val end: Int,val url: String) {
    fun contains(column: Int)=column>=start && column<end
}

/** Visible web URLs mapped back to terminal cells, including wide Unicode glyphs. */
internal object TerminalLinks {
    private val candidate=Regex("""(?i)(?<![\p{L}\p{N}_@/])(?:https?://|www\.)[^\s\p{Cc}<>"`“”]+""")
    fun webUrl(value: String): String? {
        val normalized=if(value.startsWith("www.",true)) "https://$value" else value
        val uri=runCatching {URI(normalized)}.getOrNull() ?: return null
        return normalized.takeIf {(uri.scheme.equals("https",true) || uri.scheme.equals("http",true)) && !uri.host.isNullOrBlank()}
    }
    fun row(cells: List<TerminalCell>,row: Int,columns: Int): List<TerminalLink> {
        val text=StringBuilder();val starts=ArrayList<Int>();val ends=ArrayList<Int>();var column=0
        fun append(value: String,start: Int,end: Int) {
            text.append(value);repeat(value.length) {starts.add(start);ends.add(end)}
        }
        for(cell in cells.sortedBy {it.x}) {
            if(cell.width<=0 || cell.x<column || cell.x>=columns) continue
            while(column<cell.x) {append(" ",column,column+1);column++}
            append(if(cell.attributes and 16!=0) " " else cell.text.ifEmpty {" "},cell.x,(cell.x+cell.width).coerceAtMost(columns))
            column=cell.x+cell.width
        }
        return candidate.findAll(text).mapNotNull {match->
            var value=match.value
            while(value.isNotEmpty()) {
                val last=value.last()
                val pair=when(last) {')'->'(';']'->'[';'}'->'{';else->null}
                if(last in ".,;!:'’" || (pair!=null && value.count {it==last}>value.count {it==pair})) value=value.dropLast(1) else break
            }
            val url=webUrl(value) ?: return@mapNotNull null
            TerminalLink(row,starts[match.range.first],ends[match.range.first+value.length-1],url)
        }.toList()
    }
}
