package dev.shulhi.rex

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlShortcutsTest {
    @get:Rule val ui=createComposeRule()
    private var toggles=0;private val sent=mutableListOf<String>()
    private fun fixture(enabled: Boolean=true) {
        ui.setContent {var active by remember {mutableStateOf(false)}
            MaterialTheme {Box(Modifier.size(320.dp,320.dp)) {
                ControlDockKey(enabled,active,true,Modifier.align(Alignment.BottomCenter).size(36.dp),
                    {toggles++;active=!active},{sent+=it})
            }}
        }
    }
    private fun bounds(description: String): Rect {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val info=automation.serviceInfo;info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.serviceInfo=info
        fun find(node: AccessibilityNodeInfo?): Rect? {
            if(node==null) return null
            if(node.contentDescription?.toString()==description) {
                val rect=Rect();node.getBoundsInScreen(rect);if(!rect.isEmpty) return rect
            }
            for(i in 0 until node.childCount) find(node.getChild(i))?.let {return it}
            return null
        }
        val deadline=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<deadline) {
            for(window in automation.windows) find(window.root)?.let {return it}
            SystemClock.sleep(20)
        }
        error("No visible shortcut node: $description")
    }
    private fun touch(action: Int,x: Float,y: Float,down: Long) {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event);event.recycle()
    }
    @Test fun tapStillTogglesCtrlWithoutOpeningThePopup() {
        fixture();ui.onNodeWithContentDescription("Control").performTouchInput {click()}
        ui.onNodeWithContentDescription("Control").assertIsSelected()
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertEquals(1,toggles);assertTrue(sent.isEmpty())}
    }
    @Test fun holdSlideAndReleaseSendsExactlyOneShortcut() {
        fixture();val start=bounds("Control");val down=SystemClock.uptimeMillis();ui.mainClock.autoAdvance=false
        touch(MotionEvent.ACTION_DOWN,start.exactCenterX(),start.exactCenterY(),down)
        ui.mainClock.advanceTimeBy(650);repeat(3){ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Control shortcuts").assertIsDisplayed()
        val target=bounds("Control W")
        touch(MotionEvent.ACTION_MOVE,target.exactCenterX(),target.exactCenterY(),down);ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control W").assertIsSelected()
        touch(MotionEvent.ACTION_UP,target.exactCenterX(),target.exactCenterY(),down);ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("w"),sent);assertEquals(0,toggles)}
    }
    @Test fun holdSlideAndReleaseCanReachTheNewControlJCell() {
        fixture();val start=bounds("Control");val down=SystemClock.uptimeMillis();ui.mainClock.autoAdvance=false
        touch(MotionEvent.ACTION_DOWN,start.exactCenterX(),start.exactCenterY(),down)
        ui.mainClock.advanceTimeBy(650);repeat(3){ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        val target=bounds("Control J")
        touch(MotionEvent.ACTION_MOVE,target.exactCenterX(),target.exactCenterY(),down);ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control J").assertIsSelected()
        touch(MotionEvent.ACTION_UP,target.exactCenterX(),target.exactCenterY(),down);ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertEquals(listOf("j"),sent);assertEquals(0,toggles)}
    }
    @Test fun releasingOutsideThePopupCancelsWithoutTogglingOrTyping() {
        fixture();val start=bounds("Control");val down=SystemClock.uptimeMillis();ui.mainClock.autoAdvance=false
        touch(MotionEvent.ACTION_DOWN,start.exactCenterX(),start.exactCenterY(),down)
        ui.mainClock.advanceTimeBy(650);repeat(3){ui.mainClock.advanceTimeByFrame()};ui.waitForIdle()
        ui.onNodeWithContentDescription("Control shortcuts").assertIsDisplayed()
        touch(MotionEvent.ACTION_UP,start.exactCenterX(),start.exactCenterY(),down);ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertTrue(sent.isEmpty());assertEquals(0,toggles)}
    }
    @Test fun readOnlyCtrlCannotOpenOrSendShortcuts() {
        fixture(enabled=false);ui.onNodeWithContentDescription("Control").assertIsNotEnabled().performTouchInput {longClick()}
        ui.onNodeWithContentDescription("Control shortcuts").assertDoesNotExist()
        ui.runOnIdle {assertTrue(sent.isEmpty());assertEquals(0,toggles)}
    }
}
