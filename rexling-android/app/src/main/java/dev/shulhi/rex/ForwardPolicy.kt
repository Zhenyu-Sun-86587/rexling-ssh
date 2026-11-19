package dev.shulhi.rex

import java.net.URI

internal enum class PortConflictPolicy(val label: String) {
    ASK("Ask each time"),AUTO("Choose another phone port"),REPLACE("Stop the other Rexling forward");
    companion object {fun decode(value: String?)=entries.firstOrNull {it.name==value} ?: ASK}
}
internal data class LoopbackLink(val original: String,val host: String,val port: Int) {
    fun forwarded(address: String): String {
        val source=URI(original)
        return "${source.scheme}://$address${source.rawPath.orEmpty().ifEmpty {"/"}}"+
            (source.rawQuery?.let {"?$it"} ?: "")+(source.rawFragment?.let {"#$it"} ?: "")
    }
    companion object {
        fun parse(value: String): LoopbackLink? {
            val uri=runCatching {URI(value)}.getOrNull() ?: return null
            if(uri.scheme?.lowercase() !in listOf("http","https") || uri.rawUserInfo!=null) return null
            val host=uri.host?.removeSurrounding("[","]")?.lowercase() ?: return null
            if(host !in listOf("localhost","127.0.0.1","::1")) return null
            val port=if(uri.port<0) {if(uri.scheme.equals("https",true)) 443 else 80} else uri.port
            if(port !in 1..65535) return null
            return LoopbackLink(value,if(host=="localhost") "127.0.0.1" else host,port)
        }
    }
}
