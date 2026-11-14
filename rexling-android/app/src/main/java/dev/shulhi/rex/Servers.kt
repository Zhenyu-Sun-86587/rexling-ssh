package dev.shulhi.rex

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class ServerIcon(val key: String,val label: String) {
    GLOBE("globe","Globe"),SERVER("server","Server"),CLOUD("cloud","Cloud"),MACBOOK("macbook","MacBook"),
    MAC_MINI("mac-mini","Mac mini"),MAC_STUDIO("mac-studio","Mac Studio"),DESKTOP("desktop","Desktop");
    companion object {fun decode(value: String?)=entries.firstOrNull {it.key==value} ?: GLOBE}
}

data class SshJumpHost(val host: String,val port: Int=22,val user: String,val authMethod: String="key") {
    fun json()=JSONObject().put("host",host).put("port",port).put("user",user).put("authMethod",authMethod)
    companion object {
        fun parse(o: JSONObject)=SshJumpHost(o.getString("host"),o.optInt("port",22),o.getString("user"),o.optString("authMethod","key"))
    }
}
data class SshOptions(
    val host: String,val port: Int=22,val user: String,val authMethod: String="key",
    val credentialRef: String,val socketPath: String="",val revision: String=UUID.randomUUID().toString(),
    val jumpHost: SshJumpHost?=null,val addressFamily: String="auto",
) {
    fun sameConnection(other: SshOptions): Boolean = copy(credentialRef=other.credentialRef,revision=other.revision)==other
    fun json()=JSONObject().put("host",host).put("port",port).put("user",user).put("authMethod",authMethod)
        .put("credentialRef",credentialRef).put("socketPath",socketPath).put("revision",revision).put("jumpHost",jumpHost?.json()).put("addressFamily",addressFamily)
    companion object {
        fun parse(o: JSONObject)=SshOptions(o.getString("host"),o.optInt("port",22),o.getString("user"),o.optString("authMethod","key"),
            o.getString("credentialRef"),o.optString("socketPath"),o.getString("revision"),o.optJSONObject("jumpHost")?.let(SshJumpHost::parse),o.optString("addressFamily","auto"))
    }
}

/** Compare the actual authentication fields, independent of JSON property order. */
internal fun sameSshCredentials(left: JSONObject,right: JSONObject): Boolean {
    val fields=listOf("method","privateKey","passphrase","certificate","password")
    if(fields.any {left.optString(it)!=right.optString(it)}) return false
    val a=left.optJSONObject("jumpHost");val b=right.optJSONObject("jumpHost")
    return if(a==null || b==null) a==null && b==null else sameSshCredentials(a,b)
}
data class ServerProfile(val id: String,val label: String,val endpoint: String,val icon: ServerIcon=ServerIcon.GLOBE,val ssh: SshOptions?=null,val jumpOnly: Boolean=false) {
    val storageKey: String get()=if(ssh==null) endpoint else "ssh-profile:"+id
    val connectionKey: String get()=if(ssh==null) endpoint else endpoint+":"+ssh.revision
    companion object {
        val Empty=ServerProfile("__empty__","No server","")
        fun create(label: String,endpoint: String,id: String=UUID.randomUUID().toString(),icon: ServerIcon=ServerIcon.GLOBE,ssh: SshOptions?=null,jumpOnly: Boolean=false): ServerProfile {
            val normalized=normalizeEndpoint(endpoint)
            val name=label.trim().ifEmpty {URI(normalized).host.substringBefore('.')}
            require(name.length<=64) {"Use a server name of at most 64 characters"}
            require(name.none {it.isISOControl()}) {"Use a single-line server name"}
            require(!jumpOnly || ssh!=null) {"A jump-only host needs SSH settings"}
            require(!jumpOnly || ssh?.jumpHost==null) {"Save a directly reachable SSH host as the jump; one hop is supported"}
            if(ssh!=null) {
                require(ssh.addressFamily in listOf("auto","ipv4","ipv6")) {"Invalid address family"}
                require(ssh.addressFamily!="ipv6" || ssh.jumpHost==null) {"IPv6 direct does not use a jump host"}
                require(ssh.host.isNotBlank() && ssh.user.isNotBlank() && ssh.port in 1..65535) {"Enter SSH host, username and a valid port"}
                require(ssh.socketPath.isBlank() || (ssh.socketPath.startsWith("/") && ssh.socketPath.none {it.isISOControl()})) {"Use an absolute remote socket path"}
                ssh.jumpHost?.let {jump->
                    require(jump.host.isNotBlank() && jump.user.isNotBlank() && jump.port in 1..65535) {"Enter jump host, username and a valid port"}
                    normalizeEndpoint(URI("ssh",jump.user,jump.host,jump.port,null,null,null).toASCIIString())
                }
            }
            return ServerProfile(id,name,normalized,icon,ssh,jumpOnly)
        }
        fun normalizeEndpoint(value: String): String {
            val uri=runCatching {URI(value.trim())}.getOrElse {throw IllegalArgumentException("Enter a complete server URL")}
            val scheme=uri.scheme?.lowercase(Locale.ROOT)
            require(scheme in listOf("https","http","wss","ws","ssh") && !uri.host.isNullOrBlank()) {"Enter an http(s), ws(s) or ssh server URL"}
            require((uri.rawUserInfo==null || (scheme=="ssh" && !uri.userInfo.contains(':'))) && uri.rawQuery==null && uri.rawFragment==null) {"Do not put passwords, query or fragment in the URL"}
            require(uri.path.isNullOrEmpty() || uri.path=="/") {"Use the server's root URL"}
            val port=if((scheme in listOf("https","wss") && uri.port==443) || (scheme in listOf("http","ws") && uri.port==80) || (scheme=="ssh" && uri.port==22)) -1 else uri.port
            require(port in -1..65535 && port!=0) {"Enter a valid server port"}
            return URI(scheme,if(scheme=="ssh") uri.userInfo else null,uri.host.lowercase(Locale.ROOT),port,null,null,null).toASCIIString()
        }
    }
}

object SavedServers {
    fun decode(text: String?,legacyEndpoint: String): List<ServerProfile> {
        fun legacy() = runCatching {listOf(ServerProfile.create("Rex server",legacyEndpoint,"legacy"))}.getOrDefault(emptyList())
        if(text==null) return legacy()
        return runCatching {
            val array=JSONArray(text)
            (0 until array.length()).mapNotNull { index -> runCatching {
                val item=array.getJSONObject(index)
                ServerProfile.create(item.optString("label"),item.getString("endpoint"),item.getString("id"),ServerIcon.decode(item.optString("icon")),
                    item.optJSONObject("ssh")?.let(SshOptions::parse),item.optBoolean("jumpOnly",false))
            }.getOrNull() }.distinctBy {it.id}
        }.getOrElse {legacy()}
    }
    fun encode(servers: List<ServerProfile>): String = JSONArray(servers.map {JSONObject().put("id",it.id).put("label",it.label).put("endpoint",it.endpoint).put("icon",it.icon.key).put("ssh",it.ssh?.json()).put("jumpOnly",it.jumpOnly)}).toString()
}

/** Only directly reachable saved SSH hosts can supply our single jump hop. */
internal fun jumpHostChoices(servers: List<ServerProfile>,targetId: String): List<ServerProfile> =
    servers.filter {it.id!=targetId && it.ssh!=null && it.ssh.jumpHost==null}

data class ServerStatus(val phase: String="disconnected",val error: String="") {
    val connected: Boolean get()=phase=="connected"
    val maintained: Boolean get()=phase in listOf("connected","connecting","reconnecting","paused")
}

/** Raw IPv6 is always a whole address; only brackets or a single colon carry a port. */
internal data class SshAddress(val host: String,val port: Int) {
    companion object {
        fun parse(value: String,defaultPort: Int=22): SshAddress {
            val text=value.trim()
            require(text.isNotEmpty() && text.none {it.isWhitespace() || it in "/?#@"}) {"Enter a hostname or IPv4/IPv6 address"}
            val host: String
            val port: Int
            if(text.startsWith("[")) {
                val end=text.indexOf(']');require(end>1) {"Close the IPv6 address bracket"}
                host=text.substring(1,end)
                val suffix=text.substring(end+1)
                require(suffix.isEmpty() || suffix.matches(Regex(":[0-9]+"))) {"Use [IPv6]:port"}
                port=if(suffix.isEmpty()) defaultPort else suffix.drop(1).toIntOrNull() ?: error("Invalid SSH port")
            } else if(text.count {it==':'}==1) {
                host=text.substringBefore(':');port=text.substringAfter(':').toIntOrNull() ?: error("Invalid SSH port")
            } else {host=text;port=defaultPort}
            require(port in 1..65535) {"SSH port must be between 1 and 65535"}
            val uri=URI("ssh",null,host,port,null,null,null)
            require(!uri.host.isNullOrBlank()) {"Invalid SSH host"}
            return SshAddress(host.lowercase(Locale.ROOT),port)
        }
    }
}
