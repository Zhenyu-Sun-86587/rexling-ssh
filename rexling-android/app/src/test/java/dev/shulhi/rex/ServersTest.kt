package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ServersTest {
    @Test fun onlyLegacySshProfilesRequireSetupAndStayInactiveAfterSavingTheList() {
        val saved="""[
            {"id":"jump","label":"Gateway","endpoint":"ssh://dev@gateway","jumpOnly":true},
            {"id":"via","label":"Internal","endpoint":"ssh://dev@target","ssh":{"credentialRef":"old-key","jumpHost":{"host":"gateway"}}},
            {"id":"direct","label":"Direct","endpoint":"ssh://dev@direct","ssh":{"credentialRef":"old-key"}},
            {"id":"go","label":"Go","endpoint":"ssh://dev@target"},
            {"id":"web","label":"HTTPS","endpoint":"https://web","ssh":null}
        ]"""
        val decoded=SavedServers.decode(saved,"")
        assertEquals(listOf("jump","via","direct"),decoded.filter {it.needsSshSetup}.map {it.id})
        assertEquals(listOf("go","web"),decoded.filterNot {it.needsSshSetup}.map {it.id})
        assertEquals("ssh://dev@target",decoded.first {it.id=="via"}.endpoint)
        val encoded=SavedServers.encode(decoded)
        assertFalse(encoded.contains("old-key"))
        assertEquals(decoded,SavedServers.decode(encoded,""))
    }
    @Test fun sshAddressSeparatesOnlyUnambiguousPorts() {
        assertEquals(SshAddress("2001:db8::22",22),SshAddress.parse("2001:db8::22"))
        assertEquals(SshAddress("2001:db8::22",2222),SshAddress.parse("[2001:db8::22]:2222"))
        assertEquals(SshAddress("::1",2222),SshAddress.parse("[::1]",2222))
        assertEquals(SshAddress("rex.example",2222),SshAddress.parse("REX.example:2222"))
        assertEquals(SshAddress("rex.example",22),SshAddress.parse("rex.example"))
        for(value in listOf("[::1]:0","[::1]:65536","::wrong","host:no","[::1]junk","user@host"))
            assertTrue(value,runCatching {SshAddress.parse(value)}.isFailure)
    }
    @Test fun ipv6SshUrlSurvivesPersistence() {
        val profile=ServerProfile.create("IPv6","ssh://user@[2001:db8::1]:2222/tmp/rex.sock")
        assertEquals(profile,SavedServers.decode(SavedServers.encode(listOf(profile)),"").single())
    }

    @Test fun sshURLsKeepUserAndOptionalSocketWithoutAllowingPasswords() {
        assertEquals("ssh://alice@host:2222/tmp/rex%20socket",ServerProfile.normalizeEndpoint("ssh://alice@HOST:2222/tmp/rex%20socket"))
        assertEquals("ssh://alice@host",ServerProfile.normalizeEndpoint("ssh://alice@host"))
        for(url in listOf("ssh://host","ssh://@host","ssh://user:password@host","ssh://user@host?secret=x")) assertTrue(url,runCatching {ServerProfile.normalizeEndpoint(url)}.isFailure)
    }

    @Test fun serverURLsNormalizeWithoutDroppingPortsOrTransportChoice() {
        assertEquals("https://rex.example.ts.net",ServerProfile.normalizeEndpoint(" HTTPS://REX.EXAMPLE.TS.NET:443/ "))
        assertEquals("http://host:9080",ServerProfile.normalizeEndpoint("http://HOST:9080/"))
        assertEquals("wss://host",ServerProfile.normalizeEndpoint("wss://host/"))
        assertEquals("http://[::1]:9000",ServerProfile.normalizeEndpoint("http://[::1]:9000/"))
    }
    @Test fun invalidOrCredentialBearingURLsCannotBecomeSavedProfiles() {
        for(url in listOf("host","unix:///tmp/rex.sock","https://user:pass@host","https://host/api/control","https://host/?secret=x","https://host/#x","http://host:0")) {
            assertTrue(url,runCatching {ServerProfile.create("Test",url)}.isFailure)
        }
    }
    @Test fun profilesAndStableIdsRoundTripAcrossRestart() {
        val servers=listOf(ServerProfile.create("Mac","https://mac.example.ts.net","a"),ServerProfile.create("Gateway","ssh://jumper@gateway","b").copy(relayOnly=true))
        val restored=SavedServers.decode(SavedServers.encode(servers),"https://legacy")
        assertEquals(servers,restored)
        assertEquals(listOf("a"),restored.filter {it.canConnect}.map {it.id})
    }
    @Test fun migrationPreservesTheExistingServerAndAnExplicitEmptyListStaysEmpty() {
        assertEquals("https://legacy",SavedServers.decode(null,"https://legacy/").single().endpoint)
        assertEquals("legacy",SavedServers.decode(null,"https://legacy").single().id)
        assertTrue(SavedServers.decode("[]","https://legacy").isEmpty())
        assertEquals("https://legacy",SavedServers.decode("not json","https://legacy").single().endpoint)
    }
    @Test fun corruptEntriesDoNotDiscardOtherSavedHosts() {
        val decoded=SavedServers.decode("""[{"id":"a","label":"Mac","endpoint":"https://mac"},{"id":"bad","endpoint":"file:///x"},{"id":"b","label":"Linux","endpoint":"https://linux"}]""","https://legacy")
        assertEquals(listOf("a","b"),decoded.map {it.id})
    }
    @Test fun freshInstallsHaveNoPresetPersonalServer() {
        assertTrue(SavedServers.decode(null,"").isEmpty())
        assertTrue(SavedServers.decode("not json","").isEmpty())
        assertTrue(SavedServers.decode(null,"invalid").isEmpty())
    }

    @Test fun serverIconsPersistWithoutChangingIdentityOrEndpoint() {
        val profiles=ServerIcon.entries.mapIndexed {index,icon->ServerProfile.create("Host $index","https://host$index","id$index",icon)}
        assertEquals(profiles,SavedServers.decode(SavedServers.encode(profiles),""))
    }
    @Test fun oldAndUnknownIconValuesKeepTheServerAndDefaultToGlobe() {
        val json="""[{"id":"old","label":"Old","endpoint":"https://old"},{"id":"new","label":"New","endpoint":"https://new","icon":"future-icon"}]"""
        val decoded=SavedServers.decode(json,"")
        assertEquals(listOf("old","new"),decoded.map {it.id})
        assertTrue(decoded.all {it.icon==ServerIcon.GLOBE})
    }

}
