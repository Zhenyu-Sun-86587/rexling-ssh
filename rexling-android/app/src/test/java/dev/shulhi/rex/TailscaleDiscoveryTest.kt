package dev.shulhi.rex

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class TailscaleDiscoveryTest {
    @Test fun deviceDiscoverySelectsTailnetIpsWithoutConfusingOnlineWithSshVerification() {
        val result=TailscaleDiscovery.parse("""{"devices":[
            {"nodeId":"b","hostname":"Build box","name":"box.example.ts.net","addresses":["fd7a:115c:a1e0::9","100.64.0.9"],"connectedToControl":false,"sshEnabled":true,"authorized":false},
            {"id":"a","hostname":"IPv6 box","addresses":["fd7a:115c:a1e0::8"],"blocksIncomingConnections":true},
            {"id":"bad","addresses":["https://evil.example","100.64.evil.0.1","100.63.1.2","100.128.0.1","100.64.0.999"]}
        ]}""")
        assertEquals(2,result.size)
        assertEquals("100.64.0.9",result[0].address)
        assertEquals(false,result[0].online);assertTrue(result[0].sshEnabled);assertFalse(result[0].authorized)
        assertEquals("fd7a:115c:a1e0::8",result[1].address)
        assertNull(result[1].online);assertTrue(result[1].blocksIncoming)
        assertTrue(TailscaleDiscovery.parse("""{"devices":[]}""").isEmpty())
        assertTrue(runCatching {TailscaleDiscovery.parse("""{"error":"not a device list"}""")}.isFailure)
    }

    @Test fun apiUsesDefaultTailnetAndFullDeviceFieldsAndNeverTreatsHttpErrorsAsEmptyLists() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            var requested: URL?=null
            val api=TailscaleDiscovery {url->requested=url;server.url(url.file).toUrl().openConnection() as HttpURLConnection}
            server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
            assertTrue(api.devices("test-api-key","").isEmpty())
            assertEquals("https://api.tailscale.com/api/v2/tailnet/-/devices?fields=all",requested.toString())
            val request=server.takeRequest()
            assertEquals("GET",request.method);assertEquals("Bearer test-api-key",request.getHeader("Authorization"))
            for(code in listOf(401,403,404,429,503,302)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",server.url("/must-not-follow"))
                    .setBody("""{"message":"test-api-key must not leak"}"""))
                val error=runCatching {api.devices("test-api-key","tailnet/other")}.exceptionOrNull()
                assertNotNull("HTTP $code returned a device list",error)
                assertFalse(error!!.message.orEmpty().contains("test-api-key"))
                assertTrue(requested.toString().contains("tailnet%2Fother"))
                assertTrue(server.takeRequest().path!!.contains("tailnet%2Fother"))
            }
        } finally {server.shutdown()}
    }
}
