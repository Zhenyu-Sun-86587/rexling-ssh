package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class NeovimShortcutLayout { GRID, CIRCULAR }

internal data class NeovimShortcut(val label: String,val keys: String,val group: String="")
internal sealed interface NeovimShortcutEntry {
    val label: String
    data class Action(val shortcut: NeovimShortcut): NeovimShortcutEntry {override val label get()=shortcut.label}
    data class Group(override val label: String): NeovimShortcutEntry
}

internal object NeovimShortcuts {
    val defaults=listOf(":w",":q",":wq",":wa",":qa",":nohlsearch").map {NeovimShortcut(it,it)}
    fun editorText(items: List<NeovimShortcut>): String = buildList {
        var group=""
        items.forEach {item->
            if(item.group!=group) {add("[${item.group}]");group=item.group}
            add(if(item.label==item.keys && !(item.keys.startsWith("[") && item.keys.endsWith("]"))) item.keys else "${item.label} = ${item.keys}")
        }
    }.joinToString("\n")
    fun entries(items: List<NeovimShortcut>,group: String=""): List<NeovimShortcutEntry> {
        if(group.isNotEmpty()) return items.filter {it.group==group}.map {NeovimShortcutEntry.Action(it)}
        val seen=mutableSetOf<String>()
        return buildList {items.forEach {item->
            if(item.group.isEmpty()) add(NeovimShortcutEntry.Action(item))
            else if(seen.add(item.group)) add(NeovimShortcutEntry.Group(item.group))
        }}
    }
    fun parse(text: String): List<NeovimShortcut> {
        val lines=text.lineSequence().map {it.trim()}.filter {it.isNotEmpty()}.toList()
        var group=""
        val groups=mutableSetOf<String>()
        val items=mutableListOf<NeovimShortcut>()
        lines.forEach {line->
            if(line.startsWith("[") && line.endsWith("]") && !line.contains(" = ")) {
                group=line.substring(1,line.lastIndex).trim()
                require(group.length<=40 && group.none {it=='[' || it==']' || it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use a group name of at most 40 characters. Groups support one level only."}
                if(group.isNotEmpty()) groups.add(group)
                return@forEach
            }
            val split=line.indexOf(" = ")
            val label=(if(split<0) line else line.take(split)).trim()
            val keys=(if(split<0) line else line.substring(split+3)).trim()
            require(label.isNotEmpty() && label.length<=40) {"Use a name of 1–40 characters. For longer keys, use Name = keys."}
            require(keys.isNotEmpty() && keys.length<=256) {"Use 1–256 characters for each key sequence."}
            require((label+keys).none {it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt()}) {"Use key notation such as <CR> instead of control characters."}
            decodeKeys(keys)
            items.add(NeovimShortcut(label,keys,group))
        }
        require(items.map {it.group to it.label}.distinct().size==items.size) {"Give shortcuts in the same group different names."}
        val populatedGroups=items.mapTo(mutableSetOf()) {it.group}
        require(populatedGroups.containsAll(groups)) {"Add at least one shortcut to each group."}
        require(items.none {it.group.isEmpty() && it.label in groups}) {"Give top-level shortcuts and groups different names."}
        return items
    }
    fun decodeKeys(keys: String): String = buildString {
        var i=0
        while(i<keys.length) {
            if(keys[i]!='<') {append(keys[i++]);continue}
            val end=keys.indexOf('>',i+1)
            if(end<0 || keys.substring(i+1,end).any {it.isWhitespace()}) {append(keys[i++]);continue}
            val token=keys.substring(i+1,end)
            val lower=token.lowercase(Locale.ROOT)
            val value=when(lower) {
                "space"->" ";"cr","enter","return"->"\r";"esc"->"\u001b"
                "bs","backspace"->"\u007f";"del"->TerminalInput.key("delete")
                "lt"->"<";"bar"->"|";"bslash"->"\\";"s-tab"->TerminalInput.key("tab",shift=true)
                "c-space"->"\u0000"
                else->when {
                    lower.startsWith("c-") && token.length==3->{
                        val c=token[2].uppercaseChar()
                        if(c=='?') "\u007f" else if(c.code in 64..95) ((c.code and 31).toChar()).toString() else ""
                    }
                    (lower.startsWith("a-") || lower.startsWith("m-")) && token.length==3->"\u001b${token[2]}"
                    else->TerminalInput.key(lower)
                }
            }
            require(value.isNotEmpty()) {if(lower=="leader") "Use your actual leader key, such as <Space>, instead of <Leader>." else "Unknown key <$token>."}
            append(value);i=end+1
        }
    }
    fun encode(items: List<NeovimShortcut>)=JSONArray().apply {items.forEach {item->put(JSONObject().put("label",item.label).put("keys",item.keys).apply {if(item.group.isNotEmpty()) put("group",item.group)})}}.toString()
    fun decode(text: String?): List<NeovimShortcut> = if(text==null) defaults else runCatching {
        val array=JSONArray(text)
        val items=(0 until array.length()).map {i->array.getJSONObject(i).let {NeovimShortcut(it.getString("label"),it.getString("keys"),it.optString("group"))}}
        parse(editorText(items)).also {require(it==items)}
    }.getOrDefault(defaults)

    /** Use live identity and a captured input target, never a cached icon or a replacement pane. */
    fun input(state: AppState,item: NeovimShortcut,target: String,currentTarget: String,active: Boolean,pending: Boolean): String? {
        if(!active || pending || target!=currentTarget || !state.connected || !state.controlled || state.creating || state.screen==null ||
            state.selectedBlock.isBlank() || state.selectedBlock in state.cachedPrograms || state.programs[state.selectedBlock]?.kind!=ProgramKind.NEOVIM) return null
        return runCatching {
            require(parse(editorText(listOf(item))).single()==item)
            val keys=decodeKeys(item.keys)
            // Submit plain Ex commands, while preserving explicitly encoded key sequences.
            val body=keys.trimStart('\u001b')
            val enter=body.startsWith(":") && body.none {it=='\r' || it=='\n' || it=='\u001b'}
            "\u001c\u000e$keys"+if(enter) "\r" else ""
        }.getOrNull()
    }
}
