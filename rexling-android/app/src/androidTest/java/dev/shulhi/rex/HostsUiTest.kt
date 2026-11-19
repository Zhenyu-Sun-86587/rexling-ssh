package dev.shulhi.rex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class HostsUiTest {
    @get:Rule val ui=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as Application

    private fun capture(name: String) {
        ui.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(350)
        File(app.cacheDir,"hosts-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun hostsAreGroupedAndSearchable() {
        val jump=ServerProfile.create("Tokyo gateway","ssh://ubuntu@gateway.example","jump",ServerIcon.CLOUD).copy(needsSshSetup=true)
        val target=ServerProfile.create("Development","ssh://dev@10.0.0.8","target",ServerIcon.SERVER)
        var hosts by mutableStateOf(emptyList<ServerProfile>())
        var dark by mutableStateOf(true)
        var selected: ServerProfile?=null
        var added=0
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            Surface(Modifier.fillMaxSize()) {
                HostsContent(hosts,emptyMap(),"",{added++},{},{selected=it},{},{},{})
            }
        }}
        ui.onNodeWithText("Add server").performClick()
        ui.runOnIdle {assertEquals(1,added)}
        capture("empty-dark")
        ui.runOnIdle {hosts=listOf(target,jump)}
        ui.onNodeWithText("Rex hosts").assertIsDisplayed()
        ui.onNodeWithText("Old SSH hosts · setup required").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Tokyo gateway").performClick()
        ui.runOnIdle {assertEquals(jump,selected)}
        ui.onNodeWithText("Search hosts").performScrollTo().performTextInput("Development")
        ui.onNodeWithText("Tokyo gateway").assertDoesNotExist()
        ui.onNode(hasText("Development") and !hasSetTextAction()).assertIsDisplayed()
        ui.onNode(hasSetTextAction() and hasText("Search hosts")).performTextClearance()
        capture("saved-dark")
        ui.runOnIdle {dark=false}
        capture("saved-light")
    }

    @Test fun oldSshProfileRequiresExplicitSetupAndSaveOnlyDoesNotDial() {
        val preferenceName="hosts-ui-${System.nanoTime()}"
        val store=ViewModelStore()
        lateinit var model: RexModel
        val listener=ServerSocket(0).apply {soTimeout=500}
        val endpoint="ssh://dev@127.0.0.1:${listener.localPort}"
        val prefs=app.getSharedPreferences(preferenceName,0)
        val oldHost=JSONObject().put("id","old").put("label","Development").put("endpoint",endpoint)
            .put("ssh",JSONObject().put("credentialRef","old-key").put("jumpHost",JSONObject().put("host","gateway.example")))
        prefs.edit().putString("servers",JSONArray().put(oldHost).toString()).putString("active_server","old")
            .putStringSet("connected_servers",setOf("old")).commit()
        ui.runOnUiThread {model=RexModel(app,preferenceName);store.put("hosts",model)}
        try {
            var opened=0
            ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {Surface(Modifier.fillMaxSize()) {HostsPanel(model,{opened++})}}}
            ui.runOnIdle {
                assertTrue(model.servers.single().needsSshSetup)
                assertTrue(model.forwardServers().isEmpty())
                assertTrue(BackgroundAlerts.servers(prefs).isEmpty())
                model.connectServer("old")
                assertEquals("",model.activeServerId)
            }
            ui.onNodeWithText("Development").performClick()
            ui.onNodeWithText("Set up this host again.",substring=true).assertIsDisplayed()
            capture("go-reconfigure-editor")
            ui.onNodeWithText("Save only").performClick()
            ui.waitUntil(10000) {!model.servers.single().needsSshSetup}
            ui.runOnIdle {
                assertEquals(endpoint,model.servers.single().endpoint)
                assertEquals(0,opened)
                assertTrue(prefs.getStringSet("connected_servers",emptySet()).orEmpty().isEmpty())
            }
            try {listener.accept().use {fail("Old profile or Save only opened an SSH socket")}}
            catch(_: SocketTimeoutException) { /* No socket should be opened. */ }
            ui.onNodeWithText("Saved · tap to connect").assertIsDisplayed()
        } finally {
            listener.close()
            ui.runOnUiThread {model.servers.toList().forEach {model.forgetServer(it.id)};store.clear()}
            app.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun savesAJumpOnlyHostAndReusesItFromTheTargetEditor() {
        val preferenceName="hosts-jump-ui-${System.nanoTime()}"
        val prefs=app.getSharedPreferences(preferenceName,0)
        val store=ViewModelStore()
        val listener=ServerSocket(0).apply {soTimeout=500}
        val gateway="ssh://ubuntu@127.0.0.1:${listener.localPort}"
        val target="ssh://dev@ci-target-${listener.localPort}.invalid"
        lateinit var model: RexModel
        ui.runOnUiThread {model=RexModel(app,preferenceName);store.put("hosts",model)}
        try {
            var opened=0
            ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {Surface(Modifier.fillMaxSize()) {HostsPanel(model,{opened++})}}}
            fun newHost(name: String,url: String) {
                ui.onNodeWithText("+ Add host").performScrollTo().performClick()
                ui.onNode(hasSetTextAction() and hasText("Name")).performTextInput(name)
                val field=ui.onNode(hasSetTextAction() and hasText("Server URL"))
                field.performTextInput(url)
                field.performImeAction()
            }
            newHost("Tokyo gateway",gateway)
            ui.onNodeWithContentDescription("Jump host only").performScrollTo().performClick()
            ui.onNodeWithText("Save & connect").assertDoesNotExist()
            capture("jump-only-editor")
            ui.onNodeWithText("Save only").performClick()
            ui.waitUntil(10000) {model.servers.any {it.relayOnly}}
            ui.onNodeWithText("SSH jump hosts").assertIsDisplayed()
            newHost("Private target",target)
            ui.onNodeWithText("Choose a saved SSH host").performScrollTo().performClick()
            ui.onNodeWithText("Choose jump host").assertIsDisplayed()
            capture("jump-picker")
            ui.onNode(hasText("Tokyo gateway") and hasAnyAncestor(isDialog())).performClick()
            ui.onNodeWithText("Via Tokyo gateway").assertIsDisplayed()
            ui.onNodeWithText("Connection network").assertDoesNotExist()
            capture("target-via-jump")
            ui.onNodeWithText("Save only").performClick()
            ui.waitUntil(10000) {model.servers.size==2}
            ui.runOnIdle {
                assertEquals(gateway,NetworkSettings.jumpEndpoint(target))
                val jump=model.servers.single {it.relayOnly}
                assertEquals(listOf(target),model.forwardServers().map {it.profile.endpoint})
                assertNotNull(model.forgetServer(jump.id))
                model.connectServer(jump.id)
                assertEquals(0,opened)
                assertTrue(prefs.getStringSet("connected_servers",emptySet()).orEmpty().isEmpty())
            }
            try {listener.accept().use {fail("Saving or selecting a jump-only host opened an SSH socket")}}
            catch(_: SocketTimeoutException) { /* Save only never connects. */ }
        } finally {
            listener.close()
            ui.runOnUiThread {model.servers.filterNot {it.relayOnly}.forEach {model.forgetServer(it.id)}}
            ui.waitUntil(10000) {NetworkSettings.jumpEndpoint(target)==null}
            ui.runOnUiThread {model.servers.toList().forEach {model.forgetServer(it.id)};store.clear()}
            app.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun tailscaleExplainsDiscoveryAndBlocksIneligibleDevices() {
        var sheet by mutableStateOf(true)
        var selected: TailscaleDevice?=null
        val devices=listOf(
            TailscaleDevice("a","Build workstation","build.example.ts.net","100.64.0.9","linux",true,false,true,false),
            TailscaleDevice("b","Restricted server","restricted.example.ts.net","100.64.0.10","linux",false,true,true,true),
        )
        ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {
            Surface(Modifier.fillMaxSize()) {
                if(sheet) TailscaleSheet({sheet=false},{selected=it})
                else androidx.compose.foundation.layout.Column {TailscaleDeviceList(devices) {selected=it}}
            }
        }}
        ui.onNodeWithText("Discover your tailnet").assertIsDisplayed()
        ui.onNodeWithText("Find devices").performScrollTo().assertIsNotEnabled()
        capture("tailscale-settings")
        ui.runOnIdle {sheet=false}
        ui.onNodeWithText("Restricted server").assertIsNotEnabled()
        ui.onNodeWithText("Build workstation").performClick()
        ui.runOnIdle {assertEquals("a",selected!!.id)}
    }

}
