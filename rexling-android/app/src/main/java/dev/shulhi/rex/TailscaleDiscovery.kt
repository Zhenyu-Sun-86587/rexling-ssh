package dev.shulhi.rex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class TailscaleDevice(
    val id: String,val label: String,val dnsName: String,val address: String,val os: String,
    val online: Boolean?,val sshEnabled: Boolean,val authorized: Boolean,val blocksIncoming: Boolean,
)

/** Control-plane discovery only: never establishes a VPN or changes SSH policy. */
internal class TailscaleDiscovery(private val open: (URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}) {
    suspend fun devices(apiKey: String,tailnet: String): List<TailscaleDevice> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank() && apiKey.none {it.isISOControl()}) {"Enter a valid Tailscale API key"}
        val net=URLEncoder.encode(tailnet.trim().ifBlank {"-"},"UTF-8")
        val connection=open(URL("https://api.tailscale.com/api/v2/tailnet/$net/devices?fields=all"))
        try {
            connection.connectTimeout=10_000;connection.readTimeout=10_000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Authorization","Bearer ${apiKey.trim()}")
            connection.setRequestProperty("Accept","application/json")
            when(val status=connection.responseCode) {
                200 -> connection.inputStream.use {input->
                    val output=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=input.read(buffer);if(count<0) break
                        require(output.size()+count<=2*1024*1024) {"Device list is too large"}
                        output.write(buffer,0,count)
                    }
                    parse(output.toString("UTF-8"))
                }
                401 -> error("API key expired, revoked or invalid. Enter a new key.")
                403 -> error("This key cannot read devices. Check its account permissions.")
                404 -> error("Tailnet not found. Check its ID or leave it blank for the default.")
                429 -> error("Tailscale rate limit reached. Wait before refreshing again.")
                else -> error("Device discovery failed (HTTP $status). Try again later.")
            }
        } finally {connection.disconnect()}
    }
    companion object {
        const val CREDENTIAL_ID="tailscale-discovery"
        fun parse(text: String): List<TailscaleDevice> {
            val devices=JSONObject(text).getJSONArray("devices")
            return (0 until devices.length()).mapNotNull {i->
                val item=devices.getJSONObject(i)
                val addresses=item.optJSONArray("addresses")
                val ips=if(addresses==null) emptyList() else (0 until addresses.length()).map {addresses.getString(it)}
                // Never trust an API-provided URL or hostname as an SSH destination.
                val valid=ips.mapNotNull {value->runCatching {SshAddress.parse(value)}.getOrNull()}.filter {
                    val octets=it.host.split('.').mapNotNull(String::toIntOrNull)
                    val ipv4=it.host.split('.').size==4 && octets.size==4 && octets.all {n->n in 0..255} && octets[0]==100 && octets[1] in 64..127
                    ipv4 || (it.host.startsWith("fd7a:115c:a1e0:") && it.host.matches(Regex("[0-9a-f:]+")))
                }
                val address=(valid.firstOrNull {!it.host.contains(':')} ?: valid.firstOrNull())?.host ?: return@mapNotNull null
                TailscaleDevice(item.optString("nodeId").ifBlank {item.optString("id",address)},
                    item.optString("hostname").ifBlank {item.optString("name",address)},item.optString("name"),address,item.optString("os"),
                    if(item.has("connectedToControl") && !item.isNull("connectedToControl")) item.getBoolean("connectedToControl") else null,
                    item.optBoolean("sshEnabled"),item.optBoolean("authorized",true),item.optBoolean("blocksIncomingConnections"))
            }.distinctBy {it.id}.sortedBy {it.label.lowercase()}
        }
    }
}
