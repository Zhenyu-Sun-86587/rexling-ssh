package dev.shulhi.rex

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
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
class ServerIconPickerTest {
    @get:Rule val ui=createComposeRule()
    @Test fun pickerOffersAllIconsAndChangesOnlyTheSelection() {
        val selected=mutableStateOf(ServerIcon.GLOBE)
        ui.setContent {RexTheme(false,ThemeCatalog.defaults[1]) {ServerIconPicker(selected.value) {selected.value=it}}}
        ServerIcon.entries.forEach {icon->
            ui.onNodeWithContentDescription("Choose server icon").performClick()
            if(icon==ServerIcon.GLOBE) {
                ui.onNode(isPopup()).captureToImage().asAndroidBitmap().let {bitmap->
                    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"server-icon-picker-light.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
            }
            ui.onNode(hasText(icon.label) and hasAnyAncestor(isPopup())).performClick()
            ui.onNode(isPopup()).assertDoesNotExist()
            ui.runOnIdle {assertEquals(icon,selected.value)}
        }
    }
    @Test fun editorSavesTheChosenIconWithTheExistingServerDetails() {
        val server=ServerProfile.create("Work Mac","http://127.0.0.1:1","work",ServerIcon.MAC_MINI)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val name="server-editor-test-"+System.nanoTime()
        val store=androidx.lifecycle.ViewModelStore()
        val preferences=app.getSharedPreferences(name,0)
        preferences.edit().putString("servers",SavedServers.encode(listOf(server))).commit()
        lateinit var model:RexModel
        var closed=0
        try {
            ui.runOnUiThread {model=RexModel(app,name);store.put("editor",model)}
            ui.setContent {RexTheme(true,ThemeCatalog.defaults[0]) {ServerEditor(server,model) {closed++}}}
            ui.onNodeWithContentDescription("Choose server icon").performClick()
            ui.onNode(hasText("MacBook") and hasAnyAncestor(isPopup())).performClick()
            ui.runOnIdle {assertEquals(server,model.servers.single());assertEquals(0,closed)}
            ui.onNodeWithText("Save & connect").performClick()
            ui.runOnIdle {assertEquals(server.copy(icon=ServerIcon.MACBOOK),model.servers.single());assertEquals(1,closed)}
        } finally {
            ui.runOnUiThread {store.clear()}
            app.deleteSharedPreferences(name)
        }
    }
}
