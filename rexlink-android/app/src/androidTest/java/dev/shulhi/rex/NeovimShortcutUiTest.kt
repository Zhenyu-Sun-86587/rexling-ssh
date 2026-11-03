package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeovimShortcutUiTest {
    @get:Rule val ui=createComposeRule()
    @Test fun menuSelectsNamedKeysForItsCapturedPaneAndCanOpenCustomization() {
        val sent=mutableListOf<Pair<NeovimShortcut,String>>()
        var customized=false
        val items=NeovimShortcuts.parse(":w\nLazyGit = <Space>lg")
        ui.setContent {MaterialTheme {NeovimShortcutButton(items,"pane-one",true,{item,target->sent+=item to target},{customized=true})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithText("LazyGit").performClick()
        ui.runOnIdle {assertEquals(listOf(items[1] to "pane-one"),sent)}
        ui.onNodeWithText("LazyGit").assertIsDisplayed().performClick()
        ui.onNodeWithText(":w").performClick()
        ui.runOnIdle {assertEquals(listOf(items[1] to "pane-one",items[1] to "pane-one",items[0] to "pane-one"),sent)}
        ui.onNodeWithText("Customize shortcuts").performClick()
        ui.runOnIdle {assertTrue(customized)}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
    }
    @Test fun onlyAnOutsideTapDismissesTheMenuAfterRepeatedCommands()=checkOutsideDismissal(NeovimShortcutLayout.GRID)
    @Test fun circularMenuAlsoStaysOpenUntilAnOutsideTap()=checkOutsideDismissal(NeovimShortcutLayout.CIRCULAR)
    private fun checkOutsideDismissal(layout: NeovimShortcutLayout) {
        var sent=0
        ui.setContent {MaterialTheme {Box(Modifier.size(320.dp,500.dp).semantics {contentDescription="Vim menu area"}) {
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},layout=layout)
        }}}
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()=="Vim menu area") return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        var area: Rect?=null
        val deadline=SystemClock.uptimeMillis()+4000
        while(area==null && SystemClock.uptimeMillis()<deadline) {
            area=automation.windows.firstNotNullOfOrNull {find(it.root)}
            if(area==null) SystemClock.sleep(20)
        }
        val bounds=area ?: error("No native terminal area")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        repeat(2) {ui.onNodeWithText(":w").performTouchInput {click()}}
        ui.runOnIdle {assertEquals(2,sent)}
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
        val down=SystemClock.uptimeMillis()
        val y=bounds.top+80*instrumentation.targetContext.resources.displayMetrics.density
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),y,0)
            instrumentation.sendPointerSync(event);event.recycle()
        }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertEquals(2,sent)}
    }
    @Test fun switchingPanesOrLosingControlClosesTheOpenMenu() {
        val target=mutableStateOf("one");val enabled=mutableStateOf(true)
        var sent=0
        ui.setContent {MaterialTheme {NeovimShortcutButton(NeovimShortcuts.defaults,target.value,enabled.value,{_,_->sent++},{})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.onNodeWithText(":q").assertIsDisplayed()
        ui.runOnIdle {target.value="two"};ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.runOnIdle {enabled.value=false};ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithContentDescription("Neovim shortcuts").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(0,sent)}
    }
    @Test fun editorValidatesAndSavesCustomKeysWithoutAddingEnter() {
        var saved: List<NeovimShortcut>?=null;var closed=false
        val enabled=mutableStateOf(true)
        val layout=mutableStateOf(NeovimShortcutLayout.GRID)
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize()) {
            NeovimShortcutsSheet(NeovimShortcuts.defaults,{text->runCatching {saved=NeovimShortcuts.parse(text)}.exceptionOrNull()?.message},{closed=true},enabled.value,{enabled.value=it},layout.value,{layout.value=it})
        }}}
        ui.onNodeWithText("Grid").assertIsSelected()
        ui.onNodeWithText("Circular").performClick().assertIsSelected()
        ui.onNodeWithText("Enable Neovim shortcuts").assertIsOn().performClick().assertIsOff()
        ui.onNodeWithText("Shortcuts").performTextReplacement("LazyGit = <Leader>lg")
        ui.onNodeWithText("Shortcuts").assertTextContains("LazyGit = <Leader>lg")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(4000) {ui.onAllNodesWithText("Use your actual leader key, such as <Space>, instead of <Leader>.").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("Use your actual leader key, such as <Space>, instead of <Leader>.").performScrollTo().assertIsDisplayed()
        ui.runOnIdle {assertFalse(closed);assertNull(saved)}
        ui.onNodeWithText("Shortcuts").performTextReplacement(":w\nLazyGit = <Space>lg")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.runOnIdle {assertTrue(closed);assertFalse(enabled.value);assertEquals(NeovimShortcuts.parse(":w\nLazyGit = <Space>lg"),saved)}
    }
    @Test fun draggingMovesTheButtonWithoutOpeningTheMenuAndStaysInsideAResizedTerminal() {
        val position=mutableStateOf(Offset(.2f,.6f));val height=mutableFloatStateOf(400f)
        var moved=0;var sent=0;var terminalTaps=0
        ui.setContent {MaterialTheme {Box(Modifier.size(320.dp,height.floatValue.dp).testTag("Terminal area")) {
            Box(Modifier.fillMaxSize().clickable {terminalTaps++})
            NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->sent++},{},position=position.value,onPosition={position.value=it;moved++})
        }}}
        val button=ui.onNodeWithContentDescription("Neovim shortcuts")
        val before=button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput {swipe(center,center+Offset(100f,-90f),300)}
        val after=button.fetchSemanticsNode().boundsInRoot
        assertTrue(after.left>before.left);assertTrue(after.top<before.top)
        ui.onNodeWithText(":q").assertDoesNotExist()
        ui.onNodeWithTag("Terminal area").performTouchInput {click(Offset(center.x,20f))}
        ui.runOnIdle {assertEquals(1,terminalTaps)}
        ui.runOnIdle {assertEquals(1,moved);assertEquals(0,sent);height.floatValue=200f}
        val resized=button.fetchSemanticsNode().boundsInRoot
        val area=ui.onNodeWithTag("Terminal area").fetchSemanticsNode().boundsInRoot
        assertTrue(resized.left>=area.left && resized.right<=area.right)
        assertTrue(resized.top>=area.top && resized.bottom<=area.bottom)
        button.performClick();ui.onNodeWithText(":q").assertIsDisplayed()
    }
    @Test fun compactGridPagesVerticallyWithoutSelectingAndKeepsTheSameBounds() {
        val dark=mutableStateOf(false)
        val items=NeovimShortcuts.defaults+(7..36).map {NeovimShortcut("Action $it",":echo $it")}
        val selected=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->selected+=item},{})
        }}
        captureMenu("button","Neovim shortcuts")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val grid=ui.onNodeWithContentDescription("Neovim shortcut pages")
        val before=grid.fetchSemanticsNode().boundsInRoot
        NeovimShortcuts.defaults.forEach {ui.onNodeWithText(it.label).assertIsDisplayed()}
        ui.onNodeWithText("Action 7").assertIsNotDisplayed()
        captureMenu("first-light")
        grid.performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("Action 7").assertIsDisplayed()
        ui.onNodeWithText(":w").assertIsNotDisplayed()
        assertEquals(before,grid.fetchSemanticsNode().boundsInRoot)
        grid.performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText(":w").assertIsDisplayed()
        repeat(5) {grid.performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("Action 31").assertIsDisplayed()
        ui.onNodeWithText("6/6").assertIsDisplayed()
        assertEquals(before,grid.fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertTrue(selected.isEmpty());dark.value=true}
        captureMenu("last-dark")
        grid.performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("1/6").assertIsDisplayed()
        ui.onNodeWithText(":w").assertIsDisplayed()
        grid.performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText("6/6").assertIsDisplayed()
        ui.onNodeWithText("Action 31").performClick()
        ui.runOnIdle {assertEquals(listOf(items[30]),selected)}
    }
    @Test fun groupPillReplacesTheGridWithPagedChildrenAndBackRestoresIt() {
        val dark=mutableStateOf(false)
        val items=NeovimShortcuts.parse(":w\n[Git]\n"+(1..19).joinToString("\n") {"Action $it = :echo $it"}+"\n[]\n:q")
        val selected=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->selected+=item},{})
        }}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val bounds=ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot
        captureMenu("groups-root-light")
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithText("Action 1").assertIsDisplayed()
        ui.onNodeWithText(":w").assertDoesNotExist()
        ui.onNodeWithContentDescription("Shortcut group Git").assertDoesNotExist()
        assertEquals(bounds,ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot)
        ui.runOnIdle {assertTrue(selected.isEmpty())}
        captureMenu("groups-child-light")
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithText(":w").assertIsDisplayed()
        ui.onNodeWithContentDescription("Shortcut group Git").assertIsDisplayed()
        ui.runOnIdle {dark.value=true}
        captureMenu("groups-root-dark")
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        repeat(3) {ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("Action 19").assertIsDisplayed().performClick()
        ui.onNodeWithText("Action 19").assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals(listOf(items[19],items[19]),selected)}
    }
    @Test fun backFromAGroupRemembersTheRootPage() {
        val items=NeovimShortcuts.parse((1..7).joinToString("\n") {"Root $it = :echo $it"}+"\n[Git]\nLazyGit = <Space>lg")
        ui.setContent {MaterialTheme {NeovimShortcutButton(items,"one",true,{_,_->error("Group navigation must not send keys")},{})}}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithText("LazyGit").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.onNodeWithText("Root 7").assertIsDisplayed()
        val hiddenRoots=ui.onAllNodesWithText("Root 1")
        hiddenRoots.fetchSemanticsNodes().indices.forEach {hiddenRoots[it].assertIsNotDisplayed()}
    }
    @Test fun circularWheelPagesGroupsAndKeepsActionsOpen() {
        val dark=mutableStateOf(false);var customized=false
        val items=NeovimShortcuts.parse(":w\n[Git]\n"+(1..19).joinToString("\n") {"Action $it = :echo $it"}+"\n[]\n:q")
        val sent=mutableListOf<NeovimShortcut>()
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            NeovimShortcutButton(items,"one",true,{item,_->sent+=item},{customized=true},layout=NeovimShortcutLayout.CIRCULAR)
        }}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        val bounds=ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width,bounds.height,1f)
        captureMenu("circular-root-light")
        ui.onNodeWithText(":w").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.runOnIdle {assertEquals(listOf(items[0]),sent)}
        val cells=(1..6).map {ui.onNodeWithText("Action $it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot}
        for(a in cells.indices) for(b in a+1 until cells.size) assertFalse("Circular key targets must not overlap",cells[a].overlaps(cells[b]))
        val center=ui.onNodeWithContentDescription("Back to all shortcuts").fetchSemanticsNode().boundsInRoot
        cells.forEach {assertFalse("Ring targets must stay clear of the center button",it.overlaps(center))}
        captureMenu("circular-child-light")
        ui.runOnIdle {dark.value=true}
        captureMenu("circular-child-dark")
        repeat(3) {ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()}
        ui.onNodeWithText("4/4").assertIsDisplayed()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("1/4").assertIsDisplayed()
        ui.onNodeWithText("Action 1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeDown()};ui.waitForIdle()
        ui.onNodeWithText("4/4").assertIsDisplayed()
        repeat(2) {ui.onNodeWithText("Action 19").performTouchInput {click()}}
        ui.runOnIdle {assertEquals(listOf(items[0],items[19],items[19]),sent)}
        assertEquals(bounds,ui.onNodeWithContentDescription("Neovim shortcut menu").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Back to all shortcuts").performClick()
        ui.onNodeWithContentDescription("Shortcut group Git").assertIsDisplayed()
        repeat(2) {ui.onNodeWithContentDescription("Neovim shortcut center").performTouchInput {click()}}
        ui.runOnIdle {assertFalse(customized);assertEquals(listOf(items[0],items[19],items[19]),sent)}
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertIsDisplayed()
    }
    @Test fun circularControlsStayCenteredOnTheAssistiveButtonAndRestoreItsRestingPosition() {
        val position=mutableStateOf(Offset(0f,1f));var saved=0
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize().padding(top=40.dp)) {
            Box(Modifier.size(320.dp,500.dp).semantics {contentDescription="Radial terminal area"}) {
                NeovimShortcutButton(NeovimShortcuts.defaults,"one",true,{_,_->},{},position=position.value,
                    onPosition={position.value=it;saved++},layout=NeovimShortcutLayout.CIRCULAR)
            }
        }}}
        ui.waitForIdle()
        val area=nativeBounds("Radial terminal area")
        val logicalArea=ui.onNodeWithContentDescription("Radial terminal area").fetchSemanticsNode().boundsInWindow
        val windowOffset=Offset(area.left-logicalArea.left,area.top-logicalArea.top)
        val rest=nativeBounds("Neovim shortcuts")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.waitForIdle()
        fun checkCentered() {
            // A focusable popup hides covered accessibility nodes; use the real
            // Compose anchor's window coordinates, translated to native screen space.
            val button=ui.onNodeWithContentDescription("Neovim shortcuts").fetchSemanticsNode().boundsInWindow
            val buttonCenter=button.center+windowOffset
            val wheel=nativeBounds("Neovim shortcut menu")
            val center=nativeBounds("Neovim shortcut center")
            assertEquals(buttonCenter.x,wheel.exactCenterX(),2f)
            assertEquals(buttonCenter.y,wheel.exactCenterY(),2f)
            assertEquals(buttonCenter.x,center.exactCenterX(),2f)
            assertEquals(buttonCenter.y,center.exactCenterY(),2f)
            assertTrue("The radial controls must fit the terminal",area.contains(wheel))
        }
        checkCentered()
        captureMenu("anchored-wheel-bottom-left")
        nativeTap(area.exactCenterX(),area.top+40f)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        assertEquals(rest,nativeBounds("Neovim shortcuts"))
        ui.runOnIdle {assertEquals(0,saved);position.value=Offset(1f,0f)}
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick();ui.waitForIdle()
        checkCentered()
        captureMenu("anchored-wheel-top-right")
        nativeTap(area.left+20f,area.bottom-30f)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        ui.runOnIdle {assertEquals(Offset(1f,0f),position.value);assertEquals(0,saved)}
    }
    @Test fun draggingAnOpenGroupKeepsItsPageMovesTheWholeMenuAndSavesOnlyOnRelease() {
        val position=mutableStateOf(Offset(.5f,.5f));var saved=0;var sent=0
        val items=NeovimShortcuts.parse("[Git]\n"+(1..7).joinToString("\n") {"Action $it = :echo $it"})
        ui.setContent {MaterialTheme {Box(Modifier.fillMaxSize().padding(top=40.dp)) {
            Box(Modifier.size(360.dp,560.dp).semantics {contentDescription="Draggable terminal area"}) {
                NeovimShortcutButton(items,"one",true,{_,_->sent++},{error("Dragging must not open settings")},position=position.value,
                    onPosition={position.value=it;saved++},layout=NeovimShortcutLayout.CIRCULAR)
            }
        }}}
        ui.waitForIdle()
        val area=nativeBounds("Draggable terminal area")
        ui.onNodeWithContentDescription("Neovim shortcuts").performClick()
        ui.onNodeWithContentDescription("Shortcut group Git").performClick()
        ui.onNodeWithContentDescription("Neovim shortcut pages").performTouchInput {swipeUp()};ui.waitForIdle()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        val before=nativeBounds("Back to all shortcuts")
        nativeDrag(before.exactCenterX(),before.exactCenterY(),before.exactCenterX()+96f,before.exactCenterY()+72f)
        val moved=nativeBounds("Back to all shortcuts")
        assertTrue("The moving popup must follow screen-space finger motion",moved.exactCenterX()>before.exactCenterX()+50f)
        assertTrue(moved.exactCenterY()>before.exactCenterY()+35f)
        assertTrue(moved.exactCenterX()<=before.exactCenterX()+98f)
        ui.onNodeWithText("Action 7").assertIsDisplayed()
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.runOnIdle {assertEquals(1,saved);assertEquals(0,sent)}
        val savedPosition=position.value
        nativeDrag(moved.exactCenterX(),moved.exactCenterY(),moved.exactCenterX()-72f,moved.exactCenterY()-48f,cancel=true)
        ui.runOnIdle {assertEquals(1,saved);assertEquals(savedPosition,position.value);assertEquals(0,sent)}
        ui.waitUntil(2500) {nativeBounds("Back to all shortcuts")==moved}
        ui.runOnIdle {assertEquals(1,saved);assertEquals(savedPosition,position.value)}
        nativeDrag(moved.exactCenterX(),moved.exactCenterY(),area.right-10f,area.bottom-10f)
        val wheel=nativeBounds("Neovim shortcut menu")
        val center=nativeBounds("Back to all shortcuts")
        assertTrue("Dragging must keep the full ring inside the terminal",area.contains(wheel))
        assertEquals(center.exactCenterX(),wheel.exactCenterX(),2f)
        assertEquals(center.exactCenterY(),wheel.exactCenterY(),2f)
        ui.onNodeWithText("2/2").assertIsDisplayed()
        ui.runOnIdle {assertEquals(2,saved);assertEquals(0,sent);assertTrue(position.value.x in 0f..1f && position.value.y in 0f..1f)}
        captureMenu("dragged-open-group")
        nativeTap(area.left+15f,area.top+20f)
        ui.onNodeWithContentDescription("Neovim shortcut menu").assertDoesNotExist()
        val resting=nativeBounds("Neovim shortcuts")
        assertEquals(center.exactCenterX(),resting.exactCenterX(),2f)
        assertEquals(center.exactCenterY(),resting.exactCenterY(),2f)
    }
    private fun nativeDrag(x: Float,y: Float,toX: Float,toY: Float,cancel: Boolean=false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val down=SystemClock.uptimeMillis()
        fun send(action: Int,px: Float,py: Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0)
            instrumentation.sendPointerSync(event);event.recycle();ui.waitForIdle()
        }
        send(MotionEvent.ACTION_DOWN,x,y)
        repeat(12) {step->val t=(step+1)/12f;send(MotionEvent.ACTION_MOVE,x+(toX-x)*t,y+(toY-y)*t)}
        send(if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,toX,toY)
    }
    private fun nativeBounds(description: String): Rect {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()==description) return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        val deadline=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<deadline) {
            automation.windows.firstNotNullOfOrNull {find(it.root)}?.let {return it}
            SystemClock.sleep(20)
        }
        error("No native node $description")
    }
    private fun nativeTap(x: Float,y: Float) {
        val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
        }
        ui.waitForIdle()
    }
    private fun captureMenu(name: String,description: String="Neovim shortcut menu") {
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir,"neovim-menu-$name.png").outputStream().use {
            ui.onNodeWithContentDescription(description).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
