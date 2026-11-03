package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardBarTest {
    @get:Rule val ui=createComposeRule()
    private val sent=mutableListOf<String>()
    private fun keyboard(enabled: Boolean=true,width: Dp=360.dp,ready: Boolean=enabled,controlled: ()->Boolean={enabled}) {
        ui.setContent {
            var shift by remember {mutableStateOf(ShiftKeyState())}
            MaterialTheme {
                Box(Modifier.width(width)) {
                    KeyboardBar(controlled(),ready,false,false,shift.active,true,{},{},
                        {shift=ShiftKeyState(latched=!shift.active)},
                        {shift=shift.press()},{shift=shift.release(it)},
                        {sent+=TerminalInput.key(it,shift=shift.active);shift=shift.consume()},onKeyboard={})
                }
            }
        }
    }
    @Test fun shiftTapAppliesToOneArrowAndShiftTab() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsSelected()
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Right arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow keys").performClick()
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Tab key").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2D","\u001b[C","\u001b[Z"),sent)}
    }
    @Test fun arrowPopupStaysOpenForRepeatedNavigationAndUsesLatchedShift() {
        keyboard()
        val tabBefore=ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsSelected()
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
        ui.onNodeWithContentDescription("Right arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Up arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        assertEquals(tabBefore,ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2C","\u001b[A"),sent)}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
    }
    @Test fun cancelClearsHeldShiftAndDoesNotSendAnArrow() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {down(center);cancel()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {down(center);cancel()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun arrowsRemainIndividuallyAccessibleAndDisabledControlsDoNotSend() {
        keyboard(enabled=false)
        ui.onNodeWithContentDescription("Shift").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Arrow keys").assertIsNotEnabled().performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.onNodeWithContentDescription("Enter").assertIsNotEnabled().performTouchInput {click()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun narrowPhoneKeepsEveryControlOnOneRowAndModifiersFullyVisible() {
        keyboard(width=320.dp)
        val labels=listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Enter","Show extra keys","Hide keyboard")
        val bounds=labels.map {label->
            ui.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {assertEquals(bounds.first().center.y,it.center.y,1f)}
        bounds.zipWithNext().forEach {(a,b)->assertTrue("Groups and buttons must not overlap",a.right<=b.left)}
        assertTrue("Arrow button must remain usable",bounds[5].width>=29)
    }

    @Test fun viewingConnectedTerminalKeepsIconsCrispWithoutEnablingTyping() {
        val controlled=mutableStateOf(true)
        keyboard(ready=true,controlled={controlled.value})
        val owning=ui.onRoot().captureToImage().toPixelMap()
        ui.runOnIdle {controlled.value=false}
        val viewing=ui.onRoot().captureToImage().toPixelMap()
        assertEquals(owning.width,viewing.width);assertEquals(owning.height,viewing.height)
        for(y in 0 until owning.height) for(x in 0 until owning.width)
            assertEquals("Read-only mode must preserve icon contrast at $x,$y",owning[x,y],viewing[x,y])
        for(label in listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Enter"))
            ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput {click()}
        ui.runOnIdle {assertTrue("Readable icons must not enable terminal input",sent.isEmpty())}
    }

    @Test fun navigationPendingKeepsTheEntireKeyRowCrispWhileInputIsDisabled() {
        val ready=mutableStateOf(true)
        ui.setContent {MaterialTheme {Box(Modifier.width(360.dp)) {
            KeyboardBar(ready.value,ready.value,false,false,false,true,{},{},{},{},{},
                {sent+=it},readable=true,onKeyboard={})
        }}}
        val before=ui.onRoot().captureToImage().toPixelMap()
        ui.runOnIdle {ready.value=false}
        val pending=ui.onRoot().captureToImage().toPixelMap()
        for(y in 0 until before.height) for(x in 0 until before.width)
            assertEquals("Navigation must not fade the row at $x,$y",before[x,y],pending[x,y])
        ui.onNodeWithContentDescription("Escape").assertIsNotEnabled().performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotEnabled()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
        ui.runOnIdle {ready.value=true}
        ui.onNodeWithContentDescription("Escape").assertIsEnabled()
    }

    @Test fun enterSendsOnceAndExtraKeysChevronIsNextToTheKeyboard() {
        keyboard()
        ui.onNodeWithContentDescription("Enter").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\r"),sent)}
        ui.onNodeWithContentDescription("Function key F1").assertDoesNotExist()
        ui.onNodeWithContentDescription("Show extra keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Function key F1").assertIsDisplayed()
        ui.onNodeWithContentDescription("Function key F12").assertIsDisplayed()
        val chevron=ui.onNodeWithContentDescription("Hide extra keys").fetchSemanticsNode().boundsInRoot
        val keyboard=ui.onNodeWithContentDescription("Hide keyboard").fetchSemanticsNode().boundsInRoot
        assertTrue(chevron.right<keyboard.left)
        ui.onNodeWithContentDescription("Hide extra keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Function key F1").assertDoesNotExist()
    }
    @Test fun changingTheInputTargetClosesTheArrowPopup() {
        val target=mutableStateOf("one")
        ui.setContent {val current=target.value;MaterialTheme {Box(Modifier.width(360.dp)) {
            KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=current},inputKey=current,onKeyboard={})
        }}}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {click()}
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.runOnIdle {target.value="two"}
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun nativeOutsideKeyTapsKeepThePopupOpenAndTheArrowButtonTogglesIt() {
        keyboard()
        nativeTap("Arrow keys")
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        nativeTap("Escape")
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        nativeTap("Enter")
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf("\u001b","\r"),sent)}
        nativeTap("Arrow keys")
        ui.onNodeWithContentDescription("Arrow key popup").assertDoesNotExist()
        nativeTap("Arrow keys")
        ui.onNodeWithContentDescription("Arrow key popup").assertIsDisplayed()
    }
    private fun nativeTap(description: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()==description) return Rect().also {node.getBoundsInScreen(it)}.takeUnless {it.isEmpty}
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        var found: Rect?=null
        val deadline=SystemClock.uptimeMillis()+4000
        while(found==null && SystemClock.uptimeMillis()<deadline) {
            found=automation.windows.firstNotNullOfOrNull {find(it.root)}
            if(found==null) SystemClock.sleep(20)
        }
        val bounds=found ?: error("No native key $description")
        val down=SystemClock.uptimeMillis()
        fun send(action: Int) {val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);instrumentation.sendPointerSync(event);event.recycle()}
        send(MotionEvent.ACTION_DOWN);SystemClock.sleep(35);send(MotionEvent.ACTION_UP)
        ui.waitForIdle()
    }
    @Test fun popupAppearsAboveTheUnchangedKeyRowInBothThemes() {
        val dark=mutableStateOf(false)
        ui.setContent {RexTheme(dark.value,ThemeCatalog.defaults[if(dark.value) 0 else 1]) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
                Box(Modifier.width(360.dp).testTag("Keyboard dock")) {
                    KeyboardBar(true,true,false,false,false,true,{},{},{},{},{},{sent+=it},onKeyboard={})
                }
            }
        }}
        capture("arrow-dock",ui.onNodeWithTag("Keyboard dock"))
        val enter=ui.onNodeWithContentDescription("Enter").fetchSemanticsNode().boundsInRoot
        val trigger=ui.onNodeWithContentDescription("Arrow keys")
        val anchor=trigger.fetchSemanticsNode().boundsInRoot
        trigger.performTouchInput {click()}
        val popup=ui.onNodeWithContentDescription("Arrow key popup")
        popup.assertIsDisplayed()
        assertTrue(popup.fetchSemanticsNode().boundsInRoot.bottom<anchor.top)
        assertEquals(enter,ui.onNodeWithContentDescription("Enter").fetchSemanticsNode().boundsInRoot)
        capture("arrow-popup-light",popup)
        ui.runOnIdle {dark.value=true}
        popup.assertIsDisplayed();capture("arrow-popup-dark",popup)
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    private fun capture(name: String,node: SemanticsNodeInteraction) {
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir,"$name.png").outputStream().use {node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
