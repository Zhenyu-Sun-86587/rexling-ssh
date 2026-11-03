package dev.shulhi.rex

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PortsideTest {
    private fun fail(block: ()->Unit) {assertTrue(runCatching(block).isFailure)}
    @Test fun configurationsKeepTailnetBoundariesAndEncodeEachServerIndependently() {
        val a=PortsideConfig.create("HTTP://Mac.Tailnet.TS.NET:9417/"," project/screenshots ")
        val b=PortsideConfig.create("http://100.64.0.10:9417")
        assertEquals(mapOf("a" to a,"b" to b),PortsideConfig.decode(PortsideConfig.encode(mapOf("a" to a,"b" to b))))
        for(url in listOf("http://example.com:9417","https://192.168.0.1:9417","file:///tmp","http://user:pass@mac:9417","http://mac:9417/x","http://mac:9417/?x=secret","http://mac:9417/#frag","http://[::ffff:8.8.8.8]:9417")) fail {PortsideConfig.create(url)}
        for(folder in listOf("/abs","../bad","a/../bad","a//b","a\\b","a\nb","a/./b")) fail {PortsideConfig.create("http://mac:9417",folder)}
        assertTrue(PortsideAddress.allowed(InetAddress.getByName("100.127.255.1")))
        assertTrue(PortsideAddress.allowed(InetAddress.getByName("fd7a:115c:a1e0::123")))
        assertFalse(PortsideAddress.allowed(InetAddress.getByName("100.128.0.1")))
        assertFalse(PortsideAddress.allowed(InetAddress.getByName("192.168.1.2")))
        assertTrue(PortsideConfig.decode("broken").isEmpty())
    }
    @Test fun uploadedPathsCannotInjectTerminalControlsAndAreQuotedWhenNeeded() {
        assertEquals("/home/me/image.png ",imagePathText("/home/me/image.png"))
        assertEquals("'/home/my files/image.png' ",imagePathText("/home/my files/image.png"))
        assertEquals("'/tmp/a'\\''b.png' ",imagePathText("/tmp/a'b.png"))
        for(path in listOf("relative.png","/tmp/x\nrm x","/tmp/x\u001b[0m","/tmp/x\u0000")) fail {imagePathText(path)}
    }
    private val info="""{"name":"portside-server","protocol":"portside.tcp.v1","authentication":"tailscale-owner","uploads":{"directory":"/tmp/uploads","max_file_size":1000000}}"""
    @Test fun uploadUsesRawBytesAndEncodedParametersAndConfirmsTheCompleteFile()=runBlocking {
        val server=MockWebServer();server.start()
        val file=File.createTempFile("image",".png").apply {writeBytes(ByteArray(65539){(it%251).toByte()})}
        try {
            server.enqueue(MockResponse().setChunkedBody(info,13))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"path":"/tmp/uploads/file.png","bytes":65539}"""))
            val config=PortsideConfig.create(server.url("/").toString(),"screens/with space")
            val api=PortsideClient();assertEquals(1000000L,api.info(config).maxBytes)
            val progress=mutableListOf<Float>()
            assertEquals("/tmp/uploads/file.png",api.upload(config,file,"a & b.png",1000000){progress.add(it)}.path)
            val discovery=server.takeRequest();assertNull(discovery.getHeader("Authorization"))
            val request=server.takeRequest();assertEquals("PUT",request.method)
            assertEquals("a & b.png",request.requestUrl!!.queryParameter("filename"));assertEquals("screens/with space",request.requestUrl!!.queryParameter("folder"))
            assertArrayEquals(file.readBytes(),request.body.readByteArray());assertEquals(1f,progress.last())
        } finally {server.shutdown();file.delete()}
    }
    @Test fun redirectsDeniedIdentityAndDisabledUploadsDoNotBecomeSuccessfulPaths()=runBlocking {
        val server=MockWebServer();server.start()
        try {
            val api=PortsideClient();val config=PortsideConfig.create(server.url("/").toString())
            for(response in listOf(MockResponse().setResponseCode(302).addHeader("Location",server.url("/elsewhere")),MockResponse().setResponseCode(403).setBody("""{"error":{"code":"identity_denied"}}"""),MockResponse().setBody("""{"name":"portside-server","protocol":"portside.tcp.v1","authentication":"tailscale-owner","uploads":null}"""))) {
                server.enqueue(response);assertTrue(runCatching {api.info(config)}.isFailure)
            }
            assertEquals(3,server.requestCount)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(65537),1024));assertTrue(runCatching {api.info(config)}.isFailure)
        } finally {server.shutdown()}
    }
    @Test fun cancellationStopsTheRequestAndDoesNotRetryAFileMutation()=runBlocking {
        val server=MockWebServer();server.start()
        val file=File.createTempFile("image",".png").apply {writeBytes(ByteArray(65536))}
        try {
            server.enqueue(MockResponse().setResponseCode(201).setHeadersDelay(500,TimeUnit.MILLISECONDS).setBody("""{"path":"/tmp/image.png","bytes":65536}"""))
            val job=launch(Dispatchers.IO) {PortsideClient().upload(PortsideConfig.create(server.url("/").toString()),file,"image.png",1000000){}}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));job.cancelAndJoin();assertEquals(1,server.requestCount)
        } finally {server.shutdown();file.delete()}
    }
}
