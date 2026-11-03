package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        var page by mutableIntStateOf(0)
        val mac=ServerProfile("mac","Shulhi MBP","https://shulhi-mbp-rex.taildaac1.ts.net")
        val linux=ServerProfile("linux","Linux","https://rex.taildaac1.ts.net")
        val config=PortsideConfig.create("shulhi-mbp.taildaac1.ts.net","screenshots")
        ui.setContent {
            RexTheme(dark,ThemeCatalog.defaults[if(dark) 0 else 1]) {
                Surface(Modifier.fillMaxSize()) {
                    when(page) {
                        0->PreferencesSheet({},{},{},{page=1},{},{})
                        1->ModalBottomSheet(onDismissRequest={},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
                            PortsideSettingsContent(listOf(mac,linux),mapOf(mac.id to config),{page=2},{},{})
                        }
                        else->PortsideEditor(mac,config,{it()},{_,_,_->null},{})
                    }
                }
            }
        }
        for(isDark in listOf(false,true)) {
            ui.runOnIdle {dark=isDark;page=0}
            ui.onNodeWithText("Portside").performScrollTo().assertIsDisplayed()
            capture("preferences",isDark)
            ui.onNodeWithText("Portside").performClick()
            ui.onNodeWithText("Shulhi MBP").assertIsDisplayed()
            capture("portside",isDark)
            ui.onNodeWithText("Shulhi MBP").performClick()
            ui.onNodeWithText("Use image uploads").assertIsOn()
            ui.onNodeWithText("Save").assertIsDisplayed()
            capture("uploads",isDark)
        }
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
