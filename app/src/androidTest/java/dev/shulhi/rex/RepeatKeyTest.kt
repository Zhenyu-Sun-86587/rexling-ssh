package dev.shulhi.rex

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepeatKeyTest {
    @get:Rule val ui=createComposeRule()
    private val sent=mutableListOf<String>()
    @Test fun heldArrowRepeatsAndStopsOnRelease() {
        ui.setContent {MaterialTheme {ArrowKeyGroup(true,Modifier.width(90.dp).height(40.dp),{sent+=it},inputKey="a")}}
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(750);ui.runOnIdle {assertTrue(sent.size>=3);assertTrue(sent.all {it=="left"})}
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {up()};ui.mainClock.advanceTimeByFrame()
        val count=sent.size;ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(count,sent.size)}
    }
    @Test fun scrollingTheArrowGroupNeverTypes() {
        ui.setContent {MaterialTheme {ArrowKeyGroup(true,Modifier.width(90.dp).height(40.dp),{sent+=it})}}
        ui.onNodeWithContentDescription("Arrow keys").performTouchInput {swipeLeft()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
    @Test fun aNewPaneCancelsHeldRepeatAndNeverRetargetsTheOldGesture() {
        val target=mutableStateOf("a")
        ui.setContent {val current=target.value;MaterialTheme {ArrowKeyGroup(true,Modifier.width(90.dp).height(40.dp),{sent+=current},inputKey=current)}}
        ui.mainClock.autoAdvance=false
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {down(center)}
        ui.mainClock.advanceTimeBy(650);ui.runOnIdle {assertTrue(sent.isNotEmpty());target.value="b"}
        ui.mainClock.advanceTimeByFrame();val count=sent.size
        ui.mainClock.advanceTimeBy(500);ui.runOnIdle {assertEquals(count,sent.size);assertTrue(sent.none {it=="b"})}
        ui.onNodeWithContentDescription("Left arrow").performTouchInput {up()}
    }
    @Test fun disabledRepeatCannotType() {
        ui.setContent {MaterialTheme {ArrowKeyGroup(false,Modifier.width(90.dp).height(40.dp),{sent+=it})}}
        ui.onNodeWithContentDescription("Left arrow").assertIsNotEnabled().performTouchInput {longClick()}
        ui.runOnIdle {assertTrue(sent.isEmpty())}
    }
}
