package dev.shulhi.rex

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaneChromeTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var terminal: TerminalView
    private val selected=mutableListOf<String>()
    private fun carousel(initial: String="a",count: Int=3) {
        val blocks=listOf(BlockItem("a","shell"),BlockItem("b","shell"),BlockItem("c","shell")).take(count)
        val screen=TerminalScreen(20,10,null,null,-1,0,emptyList())
        ui.setContent {
            var selected by remember {mutableStateOf(initial)}
            MaterialTheme {
                TerminalCarousel(blocks,selected,selected,screen,blocks.associate {it.id to screen},
                    mapOf("a" to ProgramIdentity(ProgramKind.TERMINAL,"fish"),
                        "b" to ProgramIdentity(ProgramKind.CODEX,"Codex"),"c" to ProgramIdentity(ProgramKind.NEOVIM,"Neovim")),
                    false,10f,{},{},{_,_,_,_,_->},{},{},{_,_->},{selected=it;this@PaneChromeTest.selected.add(it)},{},{terminal=it},Modifier.size(320.dp,480.dp))
            }
        }
        ui.mainClock.autoAdvance=false
    }
    private fun badge()=ui.onNodeWithContentDescription("Pane switch indicator")
    @Test fun badgeNeverDisappearsOrChangesPaneAcrossTheAnimationHandoff() {
        carousel()
        badge().assertDoesNotExist()
        val dx=-terminal.width*.55f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame()
        badge().assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"b"))
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        // Sample every frame, including the frame where visualId changes.
        repeat(22) {
            ui.mainClock.advanceTimeByFrame()
            badge().assertExists().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"b"))
        }
        ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist()
    }
    @Test fun cancelledSwipeDoesNotRestartTheBadgeAfterSettling() {
        carousel()
        val dx=-terminal.width*.14f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame();badge().assertExists()
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        ui.mainClock.advanceTimeBy(250)
        badge().assertDoesNotExist()
        ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist()
    }
    private fun swipe(step: Int,expected: String) {
        val dx=terminal.width*.55f*if(step>0) -1f else 1f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)}
        ui.mainClock.advanceTimeByFrame()
        badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,expected))
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)}
        repeat(22) {ui.mainClock.advanceTimeByFrame();badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,expected))}
    }
    @Test fun firstAndLastPanesWrapWithoutReversingTheAnimationTarget() {
        carousel();swipe(-1,"c");swipe(1,"a")
        ui.runOnIdle {assertEquals(listOf("c","a"),selected)}
    }
    @Test fun twoPanesLoopInBothDirectionsWithoutDuplicateSelections() {
        carousel(count=2);swipe(-1,"b");swipe(-1,"a");swipe(1,"b");swipe(1,"a")
        ui.runOnIdle {assertEquals(listOf("b","a","b","a"),selected)}
    }
    @Test fun cancellingAtTheWrapBoundaryDoesNotSelectTheOppositePane() {
        carousel();val dx=terminal.width*.14f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true)};ui.mainClock.advanceTimeByFrame()
        badge().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"c"))
        ui.runOnIdle {terminal.onPaneDragEnd?.invoke(dx,0f)};ui.mainClock.advanceTimeBy(1200)
        badge().assertDoesNotExist();ui.runOnIdle {assertTrue(selected.isEmpty())}
    }
    @Test fun singlePaneDoesNotNavigateToItself() {
        carousel(count=1);val dx=terminal.width*.55f
        ui.runOnIdle {terminal.onPaneDrag?.invoke(dx,true);terminal.onPaneDragEnd?.invoke(dx,0f)}
        ui.mainClock.advanceTimeBy(500);badge().assertDoesNotExist();ui.runOnIdle {assertTrue(selected.isEmpty())}
    }

}
