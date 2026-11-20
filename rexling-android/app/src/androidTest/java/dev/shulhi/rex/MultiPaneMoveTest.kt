package dev.shulhi.rex

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MultiPaneMoveTest {
    @get:Rule val ui=createComposeRule()

    @Test fun eitherCornerRevealsTheLeftHandleAndOnlyAValidDropSwaps() {
        val application=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val scope=CoroutineScope(Dispatchers.Main)
        val swaps=mutableListOf<Pair<String,String>>()
        val splits=mutableListOf<Pair<String,PaneSplit>>()
        lateinit var client: RexConnection
        ui.runOnUiThread {client=RexConnection(application,scope,"")}
        try {
            ui.setContent {MaterialTheme {
                Box(Modifier.width(380.dp).height(320.dp)) {
                    MultiPaneLayout(
                        client,"session","tab",listOf(
                            PanePlacement("left",0f,0f,.5f,1f),
                            PanePlacement("right",.5f,0f,.5f,1f),
                        ),focused="left",liveBlock="left",controlled=true,programs=emptyMap(),
                        waiting=emptySet(),fontSize=11f,onFocus={_,_->error("Dragging must preserve focus")},
                        onView={},onImage={_,_->false},onPaste={false},imeEnterSubmits=false,
                        tapOpensKeyboard=false,keyboardChrome={0},extendsUnderNavigation=false,
                        hazeState=remember {HazeState()},captureBackdrop=false,
                        onSplit={id,split->splits+=id to split},
                        onSwap={source,target->swaps+=source to target},
                    )
                }
            }}
            ui.onAllNodesWithContentDescription("Pane actions, top left")[0].performClick()
            val grip=ui.onNodeWithContentDescription("Move pane").assertIsDisplayed()
            val zoom=ui.onNodeWithContentDescription("Zoom pane").assertIsDisplayed()
            val start=grip.fetchSemanticsNode().boundsInRoot
            assertTrue(start.right<zoom.fetchSemanticsNode().boundsInRoot.left)
            val destination=ui.onNodeWithContentDescription("Focus pane").fetchSemanticsNode().boundsInRoot.center-start.topLeft
            grip.performTouchInput {
                down(center)
                moveTo(destination,delayMillis=200)
                cancel()
            }
            ui.runOnIdle {assertEquals(emptyList<Pair<String,String>>(),swaps)}
            grip.performTouchInput {swipe(center,destination,durationMillis=300)}
            ui.runOnIdle {assertEquals(listOf("left" to "right"),swaps)}
            ui.onAllNodesWithContentDescription("Pane actions")[1].performClick()
            ui.onNodeWithContentDescription("Move pane").assertIsDisplayed()
            ui.onNodeWithContentDescription("Zoom pane").assertIsDisplayed()
            ui.onNodeWithContentDescription("Split right").assertIsDisplayed().performClick()
            ui.onAllNodesWithContentDescription("Pane actions")[1].performClick()
            ui.onNodeWithContentDescription("Split below").assertIsDisplayed().performClick()
            ui.runOnIdle {assertEquals(listOf("right" to PaneSplit.RIGHT,"right" to PaneSplit.BELOW),splits)}
        } finally {
            ui.runOnIdle {client.close();scope.cancel()}
        }
    }
}
