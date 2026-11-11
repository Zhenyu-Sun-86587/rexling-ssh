package dev.shulhi.rex

import android.content.Context
import org.json.JSONObject

internal data class SshHostChallenge(
    val profile: ServerProfile,val transport: Long,val fingerprint: String,val algorithm: String,val changed: Boolean,
    val host: String,val port: Int,val user: String,val hop: String,
) {
    val endpoint: String get()=java.net.URI("ssh",user,host,port,null,null,null).toASCIIString()
}

/** Application scoped: terminal, service and uploads acquire the same native transport. */
internal object SshTransport {
    private data class Entry(val profile: ServerProfile,val handle: Long)
    private val entries=mutableMapOf<String,Entry>()
    private fun hostKey(host: String,port: Int)="$host:$port"
    // Identical private addresses behind different bastions need independent target trust.
    private fun targetKey(ssh: SshOptions): String = ssh.jumpHost?.let {jump->
        "via:"+jump.user+"@"+hostKey(jump.host,jump.port)+"|"+hostKey(ssh.host,ssh.port)
    } ?: hostKey(ssh.host,ssh.port)
    private fun trustStore(context: Context)=context.applicationContext.getSharedPreferences("ssh_known_hosts",Context.MODE_PRIVATE)
    @Synchronized fun acquire(context: Context,profile: ServerProfile): Long {
        val ssh=profile.ssh ?: return 0
        entries[profile.id]?.takeIf {it.profile.connectionKey==profile.connectionKey}?.let {return it.handle}
        val secret=SshCredentials(context).load(ssh.credentialRef)
        val config=JSONObject().put("host",ssh.host.removeSurrounding("[","]")).put("port",ssh.port).put("user",ssh.user)
            .put("addressFamily",ssh.addressFamily).put("socketPath",ssh.socketPath).put("jumpHost",ssh.jumpHost?.let {jump->
                jump.json().put("fingerprint",trustStore(context).getString(hostKey(jump.host,jump.port),""))
            })
            .put("fingerprint",trustStore(context).getString(targetKey(ssh),""))
        val handle=try {NativeBridge.sshCreate(config.toString(),secret)} finally {secret.fill(0)}
        check(handle!=0L) {"Could not configure SSH"}
        entries.remove(profile.id)?.let {NativeBridge.sshRemove(it.handle)}
        entries[profile.id]=Entry(profile,handle)
        return handle
    }
    @Synchronized fun forget(id: String) {entries.remove(id)?.let {NativeBridge.sshRemove(it.handle)}}
    @Synchronized fun challenge(dismissed: Set<Long> = emptySet()): SshHostChallenge? {
        for(entry in entries.values) {
            if(entry.handle in dismissed) continue
            val status=JSONObject(NativeBridge.sshStatus(entry.handle))
            val phase=status.optString("phase")
            if(phase=="host_key" || phase=="host_key_changed") return SshHostChallenge(entry.profile,entry.handle,
                status.getString("fingerprint"),status.optString("algorithm"),phase=="host_key_changed",
                status.getString("host"),status.getInt("port"),status.getString("user"),status.optString("hop","target"))
        }
        return null
    }
    @Synchronized fun trust(context: Context,challenge: SshHostChallenge) {
        check(!challenge.changed) {"Changed host keys must be verified and removed explicitly"}
        check(entries[challenge.profile.id]?.handle==challenge.transport) {"SSH confirmation is stale"}
        val ssh=challenge.profile.ssh ?: error("SSH configuration missing")
        val key=if(challenge.hop=="jump") ssh.jumpHost!!.let {hostKey(it.host,it.port)} else targetKey(ssh)
        val store=trustStore(context)
        val previous=store.getString(key,null)
        check(store.edit().putString(key,challenge.fingerprint).commit()) {"Could not save host trust"}
        val error=NativeBridge.sshTrust(challenge.transport,challenge.fingerprint)
        if(error.isNotEmpty()) {
            store.edit().putString(key,previous).commit()
            error(error)
        }
    }
    @Synchronized fun clearTrust(context: Context,options: List<SshOptions>) {
        val editor=trustStore(context).edit()
        for(ssh in options) {
            editor.remove(targetKey(ssh))
            ssh.jumpHost?.let {editor.remove(hostKey(it.host,it.port))}
        }
        check(editor.commit()) {"Could not reset saved host trust"}
    }
    fun network(route: String) {NativeBridge.sshNetwork(route)}
}
