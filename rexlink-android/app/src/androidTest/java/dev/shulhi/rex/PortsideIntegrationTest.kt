package dev.shulhi.rex

import android.content.*
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalImageInputTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @Test fun keyboardAdvertisesImagesAndAcceptsThemWithoutSendingTextOrEnter() {
        instrumentation.runOnMainSync {
            var images=0;var text="";var releases=0;var pasted=0
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;onInput={text+=it};onImage={uri,release->assertEquals(TestImageProvider.uri,uri);images++;release();true};onPaste={pasted++;true}}
            val attrs=EditorInfo();val input=view.onCreateInputConnection(attrs)!!
            assertTrue(attrs.contentMimeTypes!!.contains("image/png"))
            assertTrue(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null),0,null))
            assertEquals(1,images);assertEquals("",text)
            assertTrue(input.performContextMenuAction(android.R.id.paste));assertEquals(1,pasted)
            input.commitText("hello",1);assertEquals("hello",text)
            view.onImage={_,release->releases++;release();false}
            assertFalse(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null),0,null));assertEquals(1,releases)
        }
    }
    @Test fun staleReadonlyAndNonImageCommitsCannotReachAnotherPane() {
        instrumentation.runOnMainSync {
            var images=0;var token="pane-a"
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;mouseTarget={token};onImage={_,release->images++;release();true};onPaste={images++;true}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            val image=InputContentInfo(TestImageProvider.uri,ClipDescription("image",arrayOf("image/png")),null)
            assertFalse(input.commitContent(InputContentInfo(TestImageProvider.uri,ClipDescription("text",arrayOf("text/plain")),null),0,null))
            token="pane-b";assertFalse(input.commitContent(image,0,null));assertFalse(input.performContextMenuAction(android.R.id.paste))
            token="pane-a";view.controlled=false;assertFalse(input.commitContent(image,0,null));assertEquals(0,images)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PortsideImageUploadTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val target=ImageTarget("mac","https://mac","session","pane-a",7,1,ProgramKind.CODEX,"codex · Mac")
    private val discovery="""{"name":"portside-server","protocol":"portside.tcp.v1","authentication":"tailscale-owner","uploads":{"directory":"/tmp/images","max_file_size":1000000}}"""
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun await(predicate: ()->Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+8000
        while(android.os.SystemClock.uptimeMillis()<deadline) {var done=false;main {done=predicate()};if(done) return;Thread.sleep(20)}
        error("Image state did not settle")
    }
    private fun scope()=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    @Test fun imageIsUploadedAndInsertedOnceWithoutSubmittingThePrompt() {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);val scope=scope();var pasted="";var pastedTarget: ImageTarget?=null;var released=0
        lateinit var uploads: ImageUploads
        try {
            server.enqueue(MockResponse().setBody(discovery));server.enqueue(MockResponse().setResponseCode(201).setBody("""{"path":"/tmp/images/one.png","bytes":${TestImageProvider.png.size}}"""))
            main {uploads=ImageUploads(context,scope,{t,path->pastedTarget=t;pasted=imagePathText(path);true},{_,_->false});assertTrue(uploads.start(TestImageProvider.uri,target,PortsideConfig.create("http://127.0.0.1:${server.port}")) {released++})}
            await {check(uploads.state?.phase!=ImagePhase.FAILED) {uploads.state.toString()};pasted.isNotEmpty()}
            main {assertEquals(target,pastedTarget);assertEquals("/tmp/images/one.png ",pasted);assertFalse(pasted.contains('\n'));assertNull(uploads.state);assertEquals(1,released)}
            server.takeRequest(3,TimeUnit.SECONDS)!!;val request=server.takeRequest(3,TimeUnit.SECONDS)!!
            assertArrayEquals(TestImageProvider.png,request.body.readByteArray())
            assertEquals("image.png",request.requestUrl!!.queryParameter("filename"))
        } finally {main {uploads.close();scope.cancel()};server.shutdown()}
    }
    @Test fun changedPaneHoldsTheOriginalResultForExplicitInsertion() {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);val scope=scope();var activePane="pane-a";var pasted=0;lateinit var uploads: ImageUploads
        try {
            server.enqueue(MockResponse().setBody(discovery));server.enqueue(MockResponse().setResponseCode(201).setBody("""{"path":"/tmp/images/one.png","bytes":${TestImageProvider.png.size}}""").setHeadersDelay(200,TimeUnit.MILLISECONDS))
            main {uploads=ImageUploads(context,scope,{t,_->if(t.block==activePane) {pasted++;true} else false},{t,_->if(t.block==activePane) {pasted++;true} else false});uploads.start(TestImageProvider.uri,target,PortsideConfig.create("http://127.0.0.1:${server.port}"));activePane="pane-b"}
            await {check(uploads.state?.phase!=ImagePhase.FAILED) {uploads.state.toString()};uploads.state?.phase==ImagePhase.READY}
            main {uploads.insertReady();assertEquals(0,pasted);assertEquals(target,uploads.state!!.target);activePane="pane-a";uploads.insertReady();assertEquals(1,pasted);assertNull(uploads.state)}
        } finally {main {uploads.close();scope.cancel()};server.shutdown()}
    }
    @Test fun cancellationAndInvalidImagesReleasePermissionsAndSendNothing() {
        val server=MockWebServer();server.start(java.net.InetAddress.getByName("127.0.0.1"),0);val scope=scope();var released=0;lateinit var uploads: ImageUploads
        try {
            main {uploads=ImageUploads(context,scope,{_,_->error("must not paste")},{_,_->false});uploads.start(TestImageProvider.uri,target,PortsideConfig.create("http://127.0.0.1:${server.port}")) {released++};uploads.cancel()}
            await {released==1};main {assertNull(uploads.state)};assertEquals(0,server.requestCount)
            main {uploads.start(Uri.parse("content://dev.shulhi.rex.test.images/bad"),target,PortsideConfig.create("http://127.0.0.1:${server.port}")) {released++}}
            await {uploads.state?.phase==ImagePhase.FAILED};main {assertEquals(2,released);uploads.cancel()};assertEquals(0,server.requestCount)
        } finally {main {uploads.close();scope.cancel()};server.shutdown()}
    }
}

@RunWith(AndroidJUnit4::class)
class PortsidePreferencesTest {
    @get:Rule val ui=createComposeRule()
    @Test fun preferencesOpenPortsideAndEditorRejectsInvalidFolders() {
        var opened=0;var saved="";var editing by mutableStateOf(false)
        ui.setContent {MaterialTheme {
            if(editing) PortsideEditor(ServerProfile("mac","Mac","https://mac"),null,{it()},{url,folder->runCatching {PortsideConfig.create(url,folder);saved=url;null}.getOrElse {it.message}},{editing=false})
            else PreferencesSheet({},{},{},{opened++;editing=true},{},{})
        }}
        ui.onNodeWithText("Portside · Image uploads").performClick();ui.runOnIdle {assertEquals(1,opened)}
        ui.onNode(hasSetTextAction() and hasText("Upload folder (optional)")).performTextReplacement("../bad")
        ui.onNodeWithText("Save").performClick();ui.onNodeWithText("Use a relative folder such as project/screenshots").assertExists()
        ui.onNode(hasSetTextAction() and hasText("Upload folder (optional)")).performTextReplacement("screenshots")
        ui.onNodeWithText("Save").performClick();ui.runOnIdle {assertEquals("http://mac:9417",saved)}
    }
}

/** Explicit opt-in fixture test against the real Portside Rust server. */
@RunWith(AndroidJUnit4::class)
class PortsideLiveUploadTest {
    @Test fun realServerReceivesTheImageAndReturnsAnInsertableAbsolutePath() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("portsideUrl")
        org.junit.Assume.assumeTrue("Owned Portside fixture not supplied",endpoint!=null)
        val directory=args.getString("portsideDirectory")!!
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var path="";var released=0;lateinit var uploads: ImageUploads
        try {
            instrumentation.runOnMainSync {
                uploads=ImageUploads(instrumentation.targetContext,scope,{_,value->path=value;true},{_,_->false})
                uploads.start(TestImageProvider.uri,ImageTarget("fixture","https://fixture","session","pane",1,1,ProgramKind.CODEX,"Fixture"),PortsideConfig.create(endpoint!!,"Rexlink test")) {released++}
            }
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            var done=false
            while(android.os.SystemClock.uptimeMillis()<deadline && !done) {
                instrumentation.runOnMainSync {done=path.isNotEmpty();uploads.state?.takeIf {it.phase==ImagePhase.FAILED}?.let {error(it.error)}}
                if(!done) Thread.sleep(20)
            }
            instrumentation.runOnMainSync {
                assertTrue(path,path.startsWith("$directory/Rexlink test/"));assertTrue(path.endsWith("-image.png"))
                assertTrue(imagePathText(path).startsWith("'"));assertFalse(imagePathText(path).contains('\n'))
                assertEquals(1,released);assertNull(uploads.state)
            }
        } finally {instrumentation.runOnMainSync {uploads.close();scope.cancel()}}
    }
}

@RunWith(AndroidJUnit4::class)
class ClipboardImagePasteTest {
    @get:Rule val ui=createComposeRule()
    @Test fun imageClipboardUsesUploadAndPlainTextKeepsOrdinaryPaste() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ui.setContent {MaterialTheme {androidx.compose.material3.Text("Clipboard test")}}
        ui.runOnIdle {
            val old=clipboard.primaryClip
            try {
                var images=0;var text=""
                clipboard.setPrimaryClip(ClipData("Image",arrayOf("image/png"),ClipData.Item(TestImageProvider.uri)))
                assertTrue(pasteTerminalClipboard(context,{assertEquals(TestImageProvider.uri,it);images++;true},{text=it}))
                assertEquals(1,images);assertEquals("",text)
                clipboard.setPrimaryClip(ClipData.newPlainText("Text","hello\nworld"))
                assertTrue(pasteTerminalClipboard(context,{images++;true},{text=it}))
                assertEquals(1,images);assertEquals("hello\nworld",text)
            } finally {if(old!=null) clipboard.setPrimaryClip(old) else clipboard.clearPrimaryClip()}
        }
    }
}

@RunWith(AndroidJUnit4::class)
class PortsideSavedSettingsTest {
    @Test fun settingsPersistPerRexServerAndForgettingOneDoesNotAffectAnother() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val name="portside-settings-${System.nanoTime()}"
        val store=androidx.lifecycle.ViewModelStore()
        val profiles=listOf(ServerProfile.create("Mac","https://mac","a"),ServerProfile.create("Linux","https://linux","b"))
        app.getSharedPreferences(name,0).edit().putString("servers",SavedServers.encode(profiles)).commit()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val first=RexModel(app,name);store.put("first",first)
                assertNull(first.savePortside("a","http://mac:9417","mac/images"))
                assertNull(first.savePortside("b","http://linux:9417","linux/images"))
                val restored=RexModel(app,name);store.put("restored",restored)
                assertEquals("mac/images",restored.portside["a"]!!.folder)
                assertEquals("http://linux:9417",restored.portside["b"]!!.endpoint)
                restored.forgetServer("b")
                assertFalse(restored.portside.containsKey("b"));assertTrue(restored.portside.containsKey("a"))
                val again=RexModel(app,name);store.put("again",again)
                assertEquals(setOf("a"),again.portside.keys)
            }
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync {store.clear()};app.deleteSharedPreferences(name)}
    }
}
