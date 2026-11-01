package dev.shulhi.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    private fun keyboard(enabled: Boolean=true) {
        ui.setContent {
            var shift by remember {mutableStateOf(ShiftKeyState())}
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    KeyboardBar(enabled,enabled,false,false,shift.active,true,{},{},
                        {shift=ShiftKeyState(latched=!shift.active)},
                        {shift=shift.press()},{shift=shift.release(it)},
                        {sent+=TerminalInput.key(it,shift=shift.active);shift=shift.consume()},{})
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
        ui.onNodeWithContentDescription("Right arrow").performTouchInput {click()}
        ui.onNodeWithContentDescription("Shift").performTouchInput {click()}
        ui.onNodeWithContentDescription("Tab key").performTouchInput {click()}
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2D","\u001b[C","\u001b[Z"),sent)}
    }
    @Test fun holdingShiftCanScrollTheArrowStripWithoutTyping() {
        keyboard()
        val shift=ui.onNodeWithContentDescription("Shift").fetchSemanticsNode().boundsInRoot.center
        val left=ui.onNodeWithContentDescription("Left arrow").fetchSemanticsNode().boundsInRoot.center
        val right=ui.onNodeWithContentDescription("Right arrow").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput {down(0,shift);down(1,right);moveTo(1,left,200);up(1)}
        ui.runOnIdle {assertTrue("Scrolling must not send an arrow",sent.isEmpty())}
        ui.onNodeWithContentDescription("Shift").assertIsSelected()
        val home=ui.onNodeWithContentDescription("Home").fetchSemanticsNode().boundsInRoot.center
        val end=ui.onNodeWithContentDescription("End").fetchSemanticsNode().boundsInRoot.center
        ui.onRoot().performTouchInput {down(1,home);up(1);down(1,end);up(1);up(0)}
        ui.onNodeWithContentDescription("Shift").assertIsNotSelected()
        ui.runOnIdle {assertEquals(listOf("\u001b[1;2H","\u001b[1;2F"),sent)}
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
            ui.onNodeWithContentDescription(label).assertIsNotEnabled().performTouchInput {click()}
        }
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
}
