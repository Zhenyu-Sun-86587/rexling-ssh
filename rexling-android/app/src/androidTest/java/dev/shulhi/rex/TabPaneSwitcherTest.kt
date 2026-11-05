package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TabPaneSwitcherTest {
    @get:Rule val ui=createComposeRule()
    private val windows=listOf(WindowItem("w1","Project",listOf(BlockItem("a","Terminal"),BlockItem("b","Claude"))),
        WindowItem("w2","Terminal 2",listOf(BlockItem("c","Codex"))))
    private val programs=mapOf("a" to ProgramIdentity.resolve(listOf("fish")),"b" to ProgramIdentity.resolve(listOf("claude")),"c" to ProgramIdentity.resolve(listOf("codex")))
    private val calls=mutableListOf<String>()
    private fun show(enabled: Boolean=true,initialPage: Int=0,empty: Boolean=false,dark: Boolean=false) {
        ui.setContent {RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
            TabPaneSwitcher(if(empty) emptyList() else windows,"w1","b",programs,
                mapOf("b" to "Plan next steps","c" to "Review changes"),mapOf("a" to "~/Dev/rexlink","b" to "~/Dev/portside"),
                {if(it.id=="w1") 1 else 0},enabled,enabled,enabled && !empty,
                {calls+="tab:$it"},{calls+="pane:$it"},{calls+="new-tab"},{calls+="new-pane"},
                {calls+="close-tab:${it.id}"},{calls+="close-pane:${it.id}"},{calls+="dismiss"},initialPage)
        }}
    }
    @Test fun tabsUseFocusedProgramTitlesAndTappingSwitchesTheCorrectTab() {
        show()
        ui.onNodeWithContentDescription("Tab Project").assertIsSelected()
        ui.onNodeWithContentDescription("Tab Review changes").assertIsNotSelected()
        capture("tabs-light")
        ui.onNodeWithContentDescription("Tab Review changes").performClick()
        ui.runOnIdle {assertEquals(listOf("tab:w2"),calls)}
    }
    @Test fun swipingChangesSectionsAndPaneSelectionUsesTheCorrectId() {
        show(dark=true)
        ui.onNodeWithContentDescription("Tab and pane pages").performTouchInput {swipeLeft()}
        ui.onNodeWithText("New pane").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Plan next steps").assertIsSelected()
        ui.onNodeWithContentDescription("Pane fish").assertIsNotSelected()
        ui.onNodeWithText("~/Dev/rexlink").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Review changes").assertDoesNotExist()
        capture("panes-dark")
        ui.runOnIdle {assertTrue(calls.isEmpty())}
        ui.onNodeWithContentDescription("Pane fish").performClick()
        ui.onNodeWithContentDescription("Tab and pane pages").performTouchInput {swipeRight()}
        ui.onNodeWithText("New tab").assertIsDisplayed()
        ui.runOnIdle {assertEquals(listOf("pane:a"),calls)}
    }
    @Test fun addAndCloseActionsBelongToTheirOwnSection() {
        show()
        ui.onNodeWithText("New tab").performClick()
        ui.onNodeWithContentDescription("Close tab Project").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithText("New pane").performClick()
        ui.onNodeWithContentDescription("Close pane fish").performClick()
        ui.runOnIdle {assertEquals(listOf("new-tab","close-tab:w1","new-pane","close-pane:a"),calls)}
    }
    @Test fun busyStateDisablesTerminalActionsButAllowsBrowsingSections() {
        show(enabled=false)
        ui.onNodeWithContentDescription("Tab Project").assertIsNotEnabled()
        ui.onNodeWithText("New tab").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Close tab Project").assertIsNotEnabled()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithContentDescription("Pane fish").assertIsNotEnabled()
        ui.onNodeWithText("New pane").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Close pane fish").assertIsNotEnabled()
        ui.runOnIdle {assertTrue(calls.isEmpty())}
    }
    @Test fun emptySessionOffersANewTabButCannotAddAnUnanchoredPane() {
        show(empty=true)
        ui.onNodeWithText("No tabs yet").assertIsDisplayed()
        ui.onNodeWithText("New tab").performClick()
        ui.onNodeWithText("Panes").performClick()
        ui.onNodeWithText("No panes yet").assertIsDisplayed()
        ui.onNodeWithText("New pane").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(listOf("new-tab"),calls)}
    }
    @Test fun paneIndicatorCanOpenDirectlyOnThePaneSection() {
        show(initialPage=1)
        ui.onNodeWithText("New pane").assertIsDisplayed()
        ui.onNodeWithContentDescription("Pane Plan next steps").assertIsSelected()
        ui.onNodeWithContentDescription("Close tab and pane switcher").performClick()
        ui.runOnIdle {assertEquals(listOf("dismiss"),calls)}
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync();Thread.sleep(300)
        File(instrumentation.targetContext.cacheDir,"switcher-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
