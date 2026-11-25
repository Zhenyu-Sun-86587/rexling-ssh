package dev.shulhi.rex

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModelStore
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
        var surface=Color.Unspecified
        ui.setContent {
            RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
                surface=MaterialTheme.colorScheme.surface
                // Simulate a busy home screen without a captured Haze source.
                Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                    PreferencesSheet({},{},{},{},{})
                }
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
            val pixels=ui.onNodeWithTag("Settings drawer").captureToImage().toPixelMap()
            assertEquals("The sheet should hide the home screen behind it: dark=$isDark",surface.toArgb(),pixels[pixels.width/4,8].toArgb())
            ui.onNodeWithText("Background alerts").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Portside").assertDoesNotExist()
            capture("preferences",isDark)

        }
    }
    @Test fun editingOneTabProfileDoesNotChangeTheCurrentProfile() {
        val layouts=mutableStateMapOf(TabWidthClass.MEDIUM to TabBarLayout(TabBarPosition.LEFT),TabWidthClass.WIDE to TabBarLayout())
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
            PreferencesSheet({},{},{},{},{},tabLayouts=layouts,tabWidth=TabWidthClass.MEDIUM to 555,
                onTabLayout={width,layout->layouts[width]=layout})
        }}
        val medium=ui.onNodeWithText("Medium")
        val wide=ui.onNodeWithText("Wide")
        val expanded=SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Expanded")
        val collapsed=SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Collapsed")
        medium.performScrollTo().assert(expanded)
        ui.onNode(hasText("Medium") and hasText("Now")).assertExists()
        ui.onNodeWithText("Left").performScrollTo().assertIsSelected()
        ui.onNodeWithText("440–759 dp · This window is 555 dp").performScrollTo().assertIsDisplayed()
        medium.performScrollTo().performClick().assert(collapsed)
        ui.onNode(hasText("Medium") and hasText("Left tabs · icons")).assertExists()
        ui.onNodeWithText("Position").assertDoesNotExist()
        wide.performScrollTo().performClick().assert(expanded)
        ui.onNode(hasText("Medium") and hasText("Now")).assertExists()
        ui.onNodeWithText("Bottom").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Sidebar style").assertDoesNotExist()
        ui.onNodeWithText("Right").performClick()
        ui.onNodeWithText("Icons and titles").performScrollTo().performClick().assertIsSelected()
        medium.performScrollTo().performClick().assert(expanded)
        ui.onNodeWithText("Left").performScrollTo().assertIsSelected()
        ui.onNode(hasText("Wide") and hasText("Right tabs · icons and titles")).assertExists()
        val profiles=ui.onNodeWithTag("Tab width profiles").performScrollTo().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"settings-tab-width-profiles.png").outputStream().use {
            profiles.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        ui.runOnIdle {
            assertEquals(TabBarLayout(TabBarPosition.LEFT),layouts[TabWidthClass.MEDIUM])
            assertEquals(TabBarLayout(TabBarPosition.RIGHT,TabRailStyle.TITLES),layouts[TabWidthClass.WIDE])
        }
    }

    @Test fun paneProfilesInheritAndSaveChoicesIndependently() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name="pane-profile-test-${System.nanoTime()}"
        val prefs=app.getSharedPreferences(name,0)
        prefs.edit(commit=true) {putBoolean("multi_pane_unfolded",true)}
        val store=ViewModelStore()
        lateinit var model: RexModel
        ui.runOnUiThread {model=RexModel(app,name);store.put("panes",model)}
        var windowOrientation by mutableIntStateOf(Configuration.ORIENTATION_PORTRAIT)
        try {
            ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {
                val configuration=Configuration(LocalConfiguration.current).apply {orientation=windowOrientation}
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    PreferencesSheet({},{},{},{},{},paneLayouts=model.paneLayouts,onPaneLayout=model::changePaneLayout)
                }
            }}
            val selectable=SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
            val single=ui.onNode(hasText("Single pane") and selectable)
            val multiple=ui.onNode(hasText("Multiple panes") and selectable)
            ui.onNode(hasText("Portrait") and hasText("Now")).performScrollTo().assertIsDisplayed()
            ui.onNode(hasText("Landscape") and hasText("Multiple panes")).assertExists()
            multiple.performScrollTo().assertIsSelected()
            ui.onNodeWithText("Landscape").performScrollTo().performClick()
            single.performScrollTo().performClick().assertIsSelected()
            ui.onNode(hasText("Portrait") and hasText("Now") and hasText("Multiple panes")).assertExists()
            ui.onNodeWithText("Portrait").performScrollTo().performClick()
            multiple.performScrollTo().assertIsSelected()

            ui.runOnIdle {windowOrientation=Configuration.ORIENTATION_LANDSCAPE}
            ui.onNode(hasText("Landscape") and hasText("Now")).performScrollTo().assertIsDisplayed()
            single.performScrollTo().assertIsSelected()
            ui.runOnIdle {
                val reopened=RexModel(app,name).also {store.put("panes-reopened",it)}
                assertEquals(mapOf(PaneOrientation.PORTRAIT to true,PaneOrientation.LANDSCAPE to false),reopened.paneLayouts)
                assertFalse(prefs.contains(PaneOrientation.PORTRAIT.preferenceKey))
            }
        } finally {
            ui.runOnUiThread {store.clear()}
            app.deleteSharedPreferences(name)
        }
    }

    @Test fun compactTopBarAndCtrlShortcutUseSegmentedChoices() {
        var mode by mutableStateOf(CompactHeaderMode.NEVER)
        var gesture by mutableStateOf(ControlShortcutGesture.HOLD)
        ui.setContent {MaterialTheme {
            PreferencesSheet({},{},{},{},{},compactHeaderMode=mode,onCompactHeaderMode={mode=it},
                shortcutGesture=gesture,onShortcutGesture={gesture=it})
        }}
        ui.onNodeWithText("Never").performScrollTo().assertIsSelected()
        listOf(CompactHeaderMode.ALWAYS,CompactHeaderMode.WITH_KEYBOARD,CompactHeaderMode.NEVER).forEach {next->
            ui.onNodeWithText(next.label).performScrollTo().performClick().assertIsSelected()
            ui.runOnIdle {assertEquals(next,mode)}
        }
        ui.onNodeWithText("Hold").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Double tap").performScrollTo().performClick().assertIsSelected()
        ui.runOnIdle {assertEquals(ControlShortcutGesture.DOUBLE_TAP,gesture)}
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
