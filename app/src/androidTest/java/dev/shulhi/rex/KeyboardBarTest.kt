package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Right arrow").performScrollTo().performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Tab key").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2D","\u001b[C","\u001b[Z"),sent)}
    }
    @Test fun holdingShiftCanScrollTheArrowStripWithoutTyping() {
        keyboard()
        val shift=ui.onNodeWithContentDescription("Shift").fetchSemanticsNode().boundsInRoot.center
        val tabBefore=ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot
        val arrows=ui.onNodeWithContentDescription("Arrow keys").fetchSemanticsNode().boundsInRoot
        val start=androidx.compose.ui.geometry.Offset(arrows.right-5,arrows.center.y)
        val finish=androidx.compose.ui.geometry.Offset(arrows.left+5,arrows.center.y)
        ui.onRoot().performTouchInput {down(0,shift);down(1,start);moveTo(1,finish,200);up(1)}
        ui.runOnIdle {assertTrue("Scrolling must not send an arrow",sent.isEmpty())}
        ui.onNodeWithContentDescription("Shift").assertIsSelected()
        assertEquals("Arrow scrolling must leave modifiers fixed",tabBefore,ui.onNodeWithContentDescription("Tab key").fetchSemanticsNode().boundsInRoot)
        val right=ui.onNodeWithContentDescription("Right arrow").fetchSemanticsNode().boundsInRoot.center
        val up=ui.onNodeWithContentDescription("Up arrow").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput {down(1,right);up(1);down(1,up);up(1);up(0)}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2C","\u001b[1;2A"),sent)}
    }
    @Test fun cancelClearsHeldShiftAndDoesNotSendAnArrow() {
        keyboard()
        ui.onNodeWithContentDescription("Shift").performTouchInput {down(center);cancel()}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {down(center);cancel()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun arrowsRemainIndividuallyAccessibleAndDisabledControlsDoNotSend() {
        keyboard(enabled=false)
        ui.onNodeWithContentDescription("Shift").assertIsNotEnabled()
        for(label in listOf("Left arrow","Down arrow","Up arrow","Right arrow")) {
            ui.onNodeWithContentDescription(label).performScrollTo().assertIsNotEnabled().performTouchInput {click()}
        }
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun narrowPhoneKeepsEveryControlOnOneRowAndModifiersFullyVisible() {
        keyboard(width=320.dp)
        val labels=listOf("Escape","Control","Alt","Shift","Tab key","Arrow keys","Hide keyboard")
        val bounds=labels.map {label->
            ui.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.forEach {assertEquals(bounds.first().center.y,it.center.y,1f)}
        bounds.zipWithNext().forEach {(a,b)->assertTrue("Groups and buttons must not overlap",a.right<=b.left)}
        assertTrue("Arrow viewport must remain usable",bounds[5].width>0)
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
        for(label in listOf("Escape","Control","Alt","Shift","Tab key","Left arrow"))
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

}
