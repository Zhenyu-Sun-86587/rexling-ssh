package dev.shulhi.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalControlButtonTest {
    @get:Rule val ui=createComposeRule()
    @Test fun ownershipIconHasAccessibleActionsAndALongPressLabel() {
        ui.setContent {
            var controlled by remember {mutableStateOf(false)}
            MaterialTheme {TerminalControlButton(controlled,true) {controlled=!controlled}}
        }
        ui.onNodeWithContentDescription("Take terminal control").performClick()
        ui.onNodeWithContentDescription("Release terminal control").assertIsSelected().performTouchInput {longClick()}
        ui.onNodeWithText("Release control").assertIsDisplayed()
        ui.onNodeWithContentDescription("Release terminal control").performClick()
        ui.onNodeWithContentDescription("Take terminal control").assertIsNotSelected()
    }
}
