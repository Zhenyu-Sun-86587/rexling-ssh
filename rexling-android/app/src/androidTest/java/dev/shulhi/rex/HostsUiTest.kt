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
        val jump=ServerProfile.create("Tokyo gateway","ssh://ubuntu@gateway.example","jump",ServerIcon.CLOUD,
            SshOptions("gateway.example",user="ubuntu",credentialRef="fixture"),jumpOnly=true)
        val target=ServerProfile.create("Development","ssh://dev@10.0.0.8","target",ServerIcon.SERVER,
            SshOptions("10.0.0.8",user="dev",credentialRef="fixture",jumpHost=SshJumpHost("gateway.example",user="ubuntu")))
        var hosts by mutableStateOf(emptyList<ServerProfile>())
        var dark by mutableStateOf(true)
        var selected: ServerProfile?=null
        var added=0
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            Surface(Modifier.fillMaxSize()) {
                HostsContent(hosts,emptyMap(),"",{added++},{},{},{selected=it},{},{},{})
            }
        }}
        ui.onNodeWithText("Add SSH host").performClick()
        ui.runOnIdle {assertEquals(1,added)}
        capture("empty-dark")
        ui.runOnIdle {hosts=listOf(target,jump)}
        ui.onNodeWithText("Rex hosts").assertIsDisplayed()
        ui.onNodeWithText("SSH jump hosts · Rex not required").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Tokyo gateway").performClick()
        ui.runOnIdle {assertEquals(jump,selected)}
        ui.onNodeWithContentDescription("Search hosts").performScrollTo().performTextInput("Development")
        ui.onNodeWithText("Tokyo gateway").assertDoesNotExist()
        ui.onNodeWithText("Via ubuntu@gateway.example").assertIsDisplayed()
        ui.onNodeWithContentDescription("Search hosts").performTextClearance()
        capture("saved-dark")
        ui.runOnIdle {dark=false}
        capture("saved-light")
    }

    @Test fun saveOnlyCopiesJumpCredentialsAndDoesNotOpenASocket() {
        val preferenceName="hosts-ui-${System.nanoTime()}"
        val store=ViewModelStore()
        lateinit var model: RexModel
        ui.runOnUiThread {model=RexModel(app,preferenceName);store.put("hosts",model)}
        val listener=ServerSocket(0).apply {soTimeout=500}
        try {
            ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {Surface(Modifier.fillMaxSize()) {HostsPanel(model,{})}}}
            ui.onNodeWithText("+ Add host").performClick()
            fill("Name","Tokyo gateway")
            fill("Host","127.0.0.1")
            ui.onNodeWithContentDescription("Port").performTextReplacement(listener.localPort.toString())
            fill("Username","ubuntu")
            ui.onNodeWithText("Jump host only").performScrollTo().performClick()
            ui.onNodeWithText("Password").performScrollTo().performClick()
            fill("Password","gateway-fixture-secret")
            capture("jump-only-editor")
            ui.onNodeWithText("Save & connect").assertDoesNotExist()
            ui.onNodeWithText("Save only").performClick()
            ui.waitUntil(10000) {model.servers.size==1}
            ui.runOnIdle {
                assertTrue(model.servers.single().jumpOnly)
                model.connectServer(model.servers.single().id)
                assertEquals("",model.activeServerId)
            }
            ui.onNodeWithText("+ Add host").performScrollTo().performClick()
            fill("Name","Development")
            fill("Host","10.0.0.8")
            fill("Username","dev")
            ui.onNodeWithText("Password").performScrollTo().performClick()
            fill("Password","target-fixture-secret")
            ui.onNodeWithContentDescription("Use jump host").performScrollTo().performClick()
            ui.onNodeWithText("+ Choose a saved SSH host").performScrollTo().performClick()
            val choice=ui.onNode(hasText("Tokyo gateway") and hasAnyAncestor(isDialog()))
            choice.assertIsDisplayed()
            capture("jump-picker")
            choice.performClick()
            ui.waitUntil(10000) {ui.onAllNodes(hasText("Save only") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("◎  dev@10.0.0.8").performScrollTo().assertIsDisplayed()
            capture("route-editor")
            ui.onNodeWithText("Save only").performClick()
            ui.waitUntil(10000) {model.servers.size==2}
            val target=model.servers.single {!it.jumpOnly}
            val jump=model.servers.single {it.jumpOnly}
            assertEquals(jump.ssh!!.host,target.ssh!!.jumpHost!!.host)
            val prefs=app.getSharedPreferences(preferenceName,0)
            assertTrue(prefs.getStringSet("connected_servers",emptySet()).orEmpty().isEmpty())
            ui.runOnIdle {model.forgetServer(jump.id)}
            val bytes=SshCredentials(app).load(target.ssh.credentialRef)
            try {
                val credentials=JSONObject(bytes.toString(Charsets.UTF_8))
                assertEquals("target-fixture-secret",credentials.getString("password"))
                assertEquals("gateway-fixture-secret",credentials.getJSONObject("jumpHost").getString("password"))
            } finally {bytes.fill(0)}
            assertFalse(prefs.getString("servers","")!!.contains("fixture-secret"))
            try {listener.accept().use {fail("Saving or selecting a jump-only host opened an SSH socket")}}
            catch(_: SocketTimeoutException) { /* Saving never dials. */ }
            ui.onNodeWithText("Development").assertIsDisplayed()
        } finally {
            listener.close()
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

    private fun fill(description: String,value: String) {
        ui.onNodeWithContentDescription(description).performScrollTo().performTextInput(value)
    }
}
