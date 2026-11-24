package dev.shulhi.rex

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated settings previews: never connect to or resize a user's Rex pane. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class SettingsScreensTest {
    @get:Rule val ui=createComposeRule()
    @Test fun settingsRemainReadableInBothThemes() {
        var dark by mutableStateOf(false)
        ui.setContent {
            RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
                // Match the workspace: no parent Surface providing a text color.
                PreferencesSheet({},{},{},{},{})
            }
        }
        for(isDark in listOf(false,true)) {
            ui.runOnIdle {dark=isDark}
            var layout: androidx.compose.ui.text.TextLayoutResult?=null
            ui.onNodeWithContentDescription("Close preferences").assertIsDisplayed()
            ui.onNodeWithText("Preferences").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {action->
                val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(results);layout=results.first()
            }
            val luminance=layout!!.layoutInput.style.color.luminance()
            assertTrue("Preference header must contrast with its background: dark=$isDark luminance=$luminance",if(isDark) luminance>.5f else luminance<.3f)
            ui.onNodeWithText("Background alerts").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Portside").assertDoesNotExist()
            capture("preferences",isDark)

        }
    }
    @Test fun editingOneTabProfileDoesNotChangeTheCurrentProfile() {
        val layouts=mutableStateMapOf(TabWidthClass.MEDIUM to TabBarLayout(TabBarPosition.LEFT),TabWidthClass.WIDE to TabBarLayout())
        ui.setContent {MaterialTheme {
            PreferencesSheet({},{},{},{},{},tabLayouts=layouts,tabWidth=TabWidthClass.MEDIUM to 555,
                onTabLayout={width,layout->layouts[width]=layout})
        }}
        ui.onNodeWithText("Medium").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Left").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Wide").performScrollTo().performClick().assertIsSelected()
        ui.onNodeWithText("Bottom").assertIsSelected()
        ui.onNodeWithText("Sidebar style").assertDoesNotExist()
        ui.onNodeWithText("Right").performClick()
        ui.onNodeWithText("Icons and titles").performScrollTo().performClick().assertIsSelected()
        ui.onNodeWithText("Medium").performScrollTo().performClick()
        ui.onNodeWithText("Left").assertIsSelected()
        ui.runOnIdle {
            assertEquals(TabBarLayout(TabBarPosition.LEFT),layouts[TabWidthClass.MEDIUM])
            assertEquals(TabBarLayout(TabBarPosition.RIGHT,TabRailStyle.TITLES),layouts[TabWidthClass.WIDE])
        }
    }

    @Test fun compactTopBarOptionsSwitchBetweenAllThreeModes() {
        var mode by mutableStateOf(CompactHeaderMode.NEVER)
        ui.setContent {MaterialTheme {
            PreferencesSheet({},{},{},{},{},compactHeaderMode=mode,onCompactHeaderMode={mode=it})
        }}
        ui.onNodeWithText("Never").performScrollTo().assertIsSelected()
        listOf(CompactHeaderMode.ALWAYS,CompactHeaderMode.WITH_KEYBOARD,CompactHeaderMode.NEVER).forEach {next->
            ui.onNodeWithText(next.label).performScrollTo().performClick().assertIsSelected()
            ui.runOnIdle {assertEquals(next,mode)}
        }
    }

    @Test fun toolbarSystemColorsAreOptIn() {
        assumeTrue(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)
        var enabled by mutableStateOf(false)
        ui.setContent {MaterialTheme {
            PreferencesSheet({},{},{},{},{},toolbarSystemColors=enabled,onToolbarSystemColors={enabled=it})
        }}
        val setting=ui.onNodeWithText("Use system colors for toolbar").performScrollTo()
        setting.assertIsOff().performClick().assertIsOn()
        setting.performClick().assertIsOff()
    }

    @Test fun scrollingTheDrawerContentCannotDismissIt() {
        var open by mutableStateOf(true)
        ui.setContent {MaterialTheme {
            if(open) PreferencesSheet({},{},{},{},onClose={open=false})
        }}
        val content=ui.onNode(hasScrollAction(),useUnmergedTree=true)
        ui.onNodeWithText("About Rexling").performScrollTo()
        repeat(4) {content.performTouchInput {swipeDown()}}
        ui.onNodeWithText("Appearance").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close preferences").assertIsDisplayed()
        val heading=ui.onNodeWithText("Preferences")
        val handle=ui.onNodeWithContentDescription("Drawer handle")
        val restingTop=heading.fetchSemanticsNode().boundsInRoot.top
        val shortPull=with(ui.density) {28.dp.toPx()}
        handle.performTouchInput {down(center);moveBy(Offset(0f,shortPull))}
        val movedTop=heading.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The entire drawer should move before the finger is released",movedTop>restingTop+shortPull/2)
        handle.performTouchInput {up()}
        ui.waitForIdle()
        assertEquals("A short pull should return the drawer to rest",restingTop,heading.fetchSemanticsNode().boundsInRoot.top,1f)

        val pull=with(ui.density) {88.dp.toPx()}
        handle.performTouchInput {down(center);moveBy(Offset(0f,pull));cancel()}
        ui.waitForIdle()
        assertEquals("Cancelling a pull should restore the drawer",restingTop,heading.fetchSemanticsNode().boundsInRoot.top,1f)
        handle.performTouchInput {swipe(center,center+Offset(0f,pull))}
        ui.waitUntil(5000) {!open}
        ui.runOnIdle {open=true}
        ui.onNodeWithContentDescription("Close preferences").performClick()
        ui.waitUntil(5000) {!open}
    }
    private fun capture(name: String,dark: Boolean) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Allow the platform dialog/window animation to reach the screenshot surface.
        Thread.sleep(300)
        val large=instrumentation.targetContext.resources.configuration.fontScale>1.2f
        File(instrumentation.targetContext.cacheDir,"settings-$name-${if(dark) "dark" else "light"}${if(large) "-large-text" else ""}.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
