package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ServersTest {
    @Test fun jumpOnlyHostsPersistWithoutBecomingRexTargetsAndOldProfilesRemainTargets() {
        val ssh=SshOptions("bastion.example",user="ubuntu",credentialRef="encrypted-ref")
        val jump=ServerProfile.create("Jump","ssh://ubuntu@bastion.example",ssh=ssh,jumpOnly=true)
        val target=ServerProfile.create("Rex","https://rex.example")
        assertEquals(listOf(jump,target),SavedServers.decode(SavedServers.encode(listOf(jump,target)),""))
        assertFalse(SavedServers.decode("""[{"id":"old","label":"Old","endpoint":"https://old"}]""","").single().jumpOnly)
        assertTrue(runCatching {ServerProfile.create("Invalid","https://example",jumpOnly=true)}.isFailure)
    }
    @Test fun jumpChoicesIncludeOfflineSshOnlyHostsButExcludeSelfWebUrlsAndNestedRoutes() {
        val ssh=SshOptions("jump",user="dev",credentialRef="ref")
        val jump=ServerProfile.create("Jump","ssh://dev@jump","jump",ssh=ssh,jumpOnly=true)
        val target=ServerProfile.create("Target","ssh://dev@target","target",ssh=ssh.copy(host="target"))
        val nested=ServerProfile.create("Nested","ssh://dev@nested","nested",ssh=ssh.copy(host="nested",jumpHost=SshJumpHost("first",user="dev")))
        val web=ServerProfile.create("Web","https://web","web")
        assertEquals(listOf(jump),jumpHostChoices(listOf(target,web,jump,nested),"target"))
        assertEquals(listOf(target,jump),jumpHostChoices(listOf(target,web,jump,nested),"new"))
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
    @Test fun ipv6DirectProfileSurvivesPersistence() {
        val ssh=SshOptions("2001:db8::1",22,"user","key","key-ref",addressFamily="ipv6")
        val profile=ServerProfile.create("IPv6","ssh://user@[2001:db8::1]",ssh=ssh)
        assertEquals(profile,SavedServers.decode(SavedServers.encode(listOf(profile)),"").single())
    }

    @Test fun sshMetadataEditsPreserveConnectionIdentityButRouteChangesDoNot() {
        val original=SshOptions("target",user="dev",credentialRef="old",revision="old")
        val edited=original.copy(credentialRef="new",revision="new")
        assertTrue(edited.sameConnection(original))
        assertFalse(edited.copy(socketPath="/tmp/other.sock").sameConnection(original))
        assertFalse(edited.copy(jumpHost=SshJumpHost("jump",user="dev")).sameConnection(original))
        val profile=ServerProfile.create("Old","ssh://dev@target",ssh=original)
        assertEquals(profile.connectionKey,profile.copy(label="New",icon=ServerIcon.SERVER).connectionKey)
    }
    @Test fun sshCredentialChangesIncludeBothHopsAndIgnorePropertyOrder() {
        val a=org.json.JSONObject("""{"method":"password","password":"a","jumpHost":{"method":"password","password":"b"}}""")
        val b=org.json.JSONObject("""{"jumpHost":{"password":"b","method":"password"},"password":"a","method":"password"}""")
        assertTrue(sameSshCredentials(a,b))
        b.getJSONObject("jumpHost").put("password","changed")
        assertFalse(sameSshCredentials(a,b))
        b.remove("jumpHost")
        assertFalse(sameSshCredentials(a,b))
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
        val servers=listOf(ServerProfile.create("Mac","https://mac.example.ts.net","a"),ServerProfile.create("Linux","https://linux.example.ts.net","b"))
        assertEquals(servers,SavedServers.decode(SavedServers.encode(servers),"https://legacy"))
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
