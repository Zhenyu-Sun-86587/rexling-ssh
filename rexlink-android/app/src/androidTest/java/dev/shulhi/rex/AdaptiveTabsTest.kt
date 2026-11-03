package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdaptiveTabsTest {
    @get:Rule val ui=createComposeRule()
    private val count=mutableIntStateOf(4)
    private val selected=mutableStateOf("0")
    private val width=mutableFloatStateOf(360f)
    private val names=mutableStateOf(listOf("Investigate Rex protocol","Claude","fish","nvim","Five","Six","Seven","Eight"))
    private val stacked=mutableStateOf(false)
    private val programs=listOf("codex","claude","fish","nvim").map {ProgramIdentity.resolve(listOf(it))}
    private fun show(dark: Boolean=false) {
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            Box(Modifier.width(width.floatValue.dp).background(MaterialTheme.colorScheme.surface).testTag("Test dock")) {
                TerminalTabs(List(count.intValue) {WindowItem(it.toString(),names.value[it],listOf(BlockItem("pane-$it","Terminal")))},selected.value,true,
                    onSelect={selected.value=it},panePrograms={tab->val i=tab.id.toInt();List(if(i==0 && stacked.value) 3 else 1) {offset->programs[(i+offset)%programs.size]}},selectedPane={0},
                    actions={TerminalActions(true,true,{},{})})
            }
        }}
    }
    private fun tab(index: Int)=ui.onNode(hasContentDescription("Tab ${names.value[index]},",substring=true))
    private fun bounds(index: Int)=tab(index).fetchSemanticsNode().boundsInRoot
    private fun assertInside(index: Int) {
        val strip=ui.onNodeWithContentDescription("Tabs").fetchSemanticsNode().boundsInRoot
        val tab=bounds(index)
        assertTrue("$tab outside $strip",tab.left>=strip.left-.5f && tab.right<=strip.right+.5f)
    }
    @Test fun fourTabsFitAlongsideActionsAndKeepUsefulTouchTargets() {
        show()
        repeat(4) {tab(it).assertIsDisplayed();assertInside(it)}
        assertTrue(bounds(0).width>bounds(1).width)
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        repeat(4) {assertTrue("Tab $it width: ${bounds(it)}",bounds(it).width>=48*density-.5f);assertTrue("Tab $it height: ${bounds(it)}",bounds(it).height>=48*density-.5f)}
        ui.onNodeWithText("Claude").assertDoesNotExist()
        val dock=ui.onNodeWithTag("Test dock").fetchSemanticsNode().boundsInRoot
        val eye=ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot
        assertTrue(eye.right<=dock.right)
        capture("four-tabs-light")
    }
    @Test fun inactiveTitlesReturnWhenThereIsRoom() {
        count.intValue=2;show(dark=true)
        ui.onNodeWithText("Claude").assertIsDisplayed()
        capture("two-tabs-dark")
        ui.runOnIdle {count.intValue=4}
        ui.onNodeWithText("Claude").assertDoesNotExist()
        repeat(4) {assertInside(it)}
        ui.runOnIdle {count.intValue=2}
        ui.onNodeWithText("Claude").assertIsDisplayed()
    }
    @Test fun animatedTitlesKeepWidthsAndPositionsStable() {
        count.intValue=3;show()
        val before=List(3) {bounds(it)}
        for(title in listOf("Working ·","Working ··","A much longer task title that will be truncated")) {
            ui.runOnIdle {names.value=names.value.toMutableList().apply {set(0,title)}}
            repeat(3) {assertEquals(before[it],bounds(it))}
        }
    }
    @Test fun stackedIconsDoNotMoveTheActiveTitle() {
        count.intValue=2;show()
        val before=ui.onNodeWithText(names.value[0]).fetchSemanticsNode().boundsInRoot
        ui.runOnIdle {stacked.value=true}
        assertEquals(before,ui.onNodeWithText(names.value[0]).fetchSemanticsNode().boundsInRoot)
    }
    @Test fun selectingAnOverflowTabBringsItsEntireExpandedRowIntoView() {
        count.intValue=8;show()
        ui.onNodeWithContentDescription("Tabs").performScrollToIndex(7)
        tab(7).performClick().assertIsSelected()
        assertInside(7)
        capture("overflow-selection")
        ui.runOnIdle {selected.value="0"}
        tab(0).assertIsSelected();assertInside(0)
    }
    @Test fun shrinkingTheViewportKeepsTheActiveTabVisible() {
        count.intValue=8;selected.value="7";width.floatValue=400f;show()
        tab(7).assertIsSelected();assertInside(7)
        ui.runOnIdle {width.floatValue=300f}
        tab(7).assertIsSelected();assertInside(7)
    }
    private fun capture(name: String) {
        ui.mainClock.advanceTimeBy(400)
        ui.waitForIdle()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        File(app.cacheDir,"adaptive-$name.png").outputStream().use {
            ui.onNodeWithTag("Test dock").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
