package dev.shulhi.rex

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PortsideConfig(val endpoint: String,val folder: String="") {
    companion object {
        fun create(endpoint: String,folder: String=""): PortsideConfig {
            val url=endpoint.trim().toHttpUrlOrNull() ?: error("Enter the Portside server URL, such as http://mac:9417")
            require(url.scheme in listOf("http","https") && PortsideAddress.validHost(url.host)) {"Use a Tailscale IP or MagicDNS address"}
            require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath=="/" && url.query==null && url.fragment==null) {"Use the server's root URL without credentials or query parameters"}
            val subfolder=folder.trim()
            require(subfolder.toByteArray().size<=1024 && '\\' !in subfolder && subfolder.none {it.isISOControl()} &&
                (subfolder.isEmpty() || subfolder.split('/').all {it.isNotEmpty() && it!="." && it!=".."})) {"Use a relative folder such as project/screenshots"}
            return PortsideConfig(url.toString().removeSuffix("/"),subfolder)
        }
        fun decode(value: String?): Map<String,PortsideConfig> = runCatching {
            val root=JSONObject(value ?: "{}")
            root.keys().asSequence().mapNotNull {id->runCatching {val item=root.getJSONObject(id);id to create(item.getString("endpoint"),item.optString("folder"))}.getOrNull()}.toMap()
        }.getOrDefault(emptyMap())
        fun encode(values: Map<String,PortsideConfig>): String = JSONObject().apply {values.forEach {(id,value)->put(id,JSONObject().put("endpoint",value.endpoint).put("folder",value.folder))}}.toString()
    }
    fun url(path: String): HttpUrl = endpoint.toHttpUrlOrNull()!!.newBuilder().encodedPath(path).build()
}

/** Keep Portside's cleartext HTTP inside Tailscale (or loopback for local fixtures). */
internal object PortsideAddress {
    fun allowed(address: InetAddress): Boolean {
        val bytes=address.address.map {it.toInt() and 255}
        return if(bytes.size==4) bytes[0]==127 || (bytes[0]==100 && bytes[1] in 64..127)
            else address.isLoopbackAddress || bytes.take(6)==listOf(0xfd,0x7a,0x11,0x5c,0xa1,0xe0)
    }
    fun validHost(host: String): Boolean {
        if(host=="localhost") return true
        if(':' in host || host.all {it.isDigit() || it=='.'}) {
            if(':' in host && !host.matches(Regex("[0-9a-fA-F:]+"))) return false
            if(':' !in host && host.split('.').let {it.size!=4 || it.any {p->p.isEmpty() || (p.length>1 && p.startsWith('0')) || p.toIntOrNull() !in 0..255}}) return false
            return runCatching {allowed(InetAddress.getByName(host))}.getOrDefault(false)
        }
        val labels=host.split('.')
        return host.length in 1..253 && labels.all {it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))} &&
            (labels.size==1 || host.endsWith(".ts.net",ignoreCase=true))
    }
    val dns=object: Dns {
        override fun lookup(hostname: String)=Dns.SYSTEM.lookup(hostname).also {addresses->
            if(addresses.isEmpty() || addresses.any {!allowed(it)}) throw UnknownHostException("Portside must resolve to a Tailscale address")
        }
    }
}

internal data class PortsideInfo(val directory: String,val maxBytes: Long)
internal data class UploadedImage(val path: String,val bytes: Long)
internal fun imagePathText(path: String): String {
    require(path.startsWith('/') && path.length<=16384 && path.none {it.isISOControl()}) {"Portside returned an invalid file path"}
    return (if(path.matches(Regex("[A-Za-z0-9_./:@+-]+"))) path else "'"+path.replace("'","'\\''")+"'")+" "
}
internal class PortsideClient {
    companion object {private val sharedHttp by lazy {OkHttpClient.Builder().dns(PortsideAddress.dns).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS)
        .callTimeout(10,TimeUnit.MINUTES).build()}}
    private val http get()=sharedHttp
    suspend fun info(config: PortsideConfig): PortsideInfo {
        val json=request(Request.Builder().url(config.url("/v1/info")).build(),200)
        require(json.optString("name")=="portside-server" && json.optString("protocol")=="portside.tcp.v1" && json.optString("authentication")=="tailscale-owner") {"Update Portside to a server using Tailscale identity"}
        val uploads=json.optJSONObject("uploads") ?: error("Image uploads are disabled on this Portside server. Enable its upload directory.")
        val directory=uploads.getString("directory");imagePathText(directory)
        val max=uploads.getLong("max_file_size");require(max>0) {"Portside returned an invalid upload limit"}
        return PortsideInfo(directory,max)
    }
    suspend fun upload(config: PortsideConfig,file: File,name: String,limit: Long,progress: (Float)->Unit): UploadedImage {
        require(file.length() in 1..limit) {"This image exceeds the server's upload limit"}
        val url=config.url("/v1/files").newBuilder().addQueryParameter("filename",name).addQueryParameter("folder",config.folder).build()
        val body=object: RequestBody() {
            override fun contentType()="application/octet-stream".toMediaType()
            override fun contentLength()=file.length()
            override fun writeTo(sink: BufferedSink) {
                var sent=0L;var last=0L
                file.inputStream().use {input->val buffer=ByteArray(65536)
                    while(true) {val n=input.read(buffer);if(n<0) break;sink.write(buffer,0,n);sent+=n
                        val now=System.nanoTime();if(now-last>100_000_000 || sent==contentLength()) {progress(sent.toFloat()/contentLength());last=now}
                    }
                }
            }
        }
        val result=request(Request.Builder().url(url).put(body).build(),201)
        val path=result.getString("path");imagePathText(path)
        require(result.getLong("bytes")==file.length()) {"Portside did not confirm the complete image"}
        return UploadedImage(path,file.length())
    }
    private suspend fun request(request: Request,expected: Int): JSONObject = withContext(Dispatchers.IO) {suspendCancellableCoroutine<JSONObject> {continuation->
        val call=http.newCall(request);continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) {if(continuation.isActive) continuation.resumeWithException(IOException("Could not reach Portside. Check Tailscale and the server address."))}
            override fun onResponse(call: Call,response: Response) {
                try {
                    val result=response.use {r->
                        val body=r.body ?: error("Portside returned an empty response")
                        val bytes=ByteArray(65537);var size=0
                        body.byteStream().use {input->while(size<bytes.size) {val n=input.read(bytes,size,bytes.size-size);if(n<0) break;size+=n}}
                        require(size<=65536) {"Portside's response is too large"}
                        val text=String(bytes,0,size,Charsets.UTF_8)
                        val json=if(r.code==expected) runCatching {JSONObject(text)}.getOrElse {throw IOException("Portside returned an invalid response")} else runCatching {JSONObject(text)}.getOrDefault(JSONObject())
                        if(r.code!=expected) {
                            val message=when(r.code) {401,403->"Portside denied access. Use the same Tailscale account as the server.";404->"Uploads are unavailable. Check the server address and upload settings.";413->"This image exceeds the server's upload limit";in 300..399->"Portside redirects are not supported";else->json.optJSONObject("error")?.optString("message").orEmpty().filterNot {it.isISOControl()}.take(256).ifBlank {"Portside request failed (${r.code})"}}
                            throw IOException(message)
                        }
                        json
                    }
                    if(continuation.isActive) continuation.resume(result)
                } catch(error: Exception) {if(continuation.isActive) continuation.resumeWithException(error)}
            }
        })
    }}
}
