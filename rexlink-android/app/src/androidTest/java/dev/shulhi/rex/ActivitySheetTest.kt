package dev.shulhi.rex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivitySheetTest {
    @get:Rule val ui=createComposeRule()
    private fun record(state: String,title: String,session: String,block: String)=ActivityRecord.parse(JSONObject()
        .put("session",session).put("sessionLabel","Session $session").put("windowLabel","Tab").put("block",block)
        .put("state",state).put("kind","permission").put("title",title).put("app","codex").put("message","Reported message"))!!
    @Test fun activityIsGroupedAndSelectingPreservesServerAndPaneIdentity() {
        val a=ServerProfile.create("Linux","https://linux.example","a");val b=ServerProfile.create("Mac","https://mac.example","b")
        val first=record("working","Building","s1","pane1");val second=record("blocked","Review","s2","pane2")
        var selected: ActivityTarget?=null
        ui.setContent {MaterialTheme {ActivitySheet(listOf(ActivitySource(a,true,false,"",false,listOf(first)),ActivitySource(b,true,false,"",false,listOf(second))),{},{selected=it},{})}}
        ui.onNodeWithText("Linux").assertIsDisplayed();ui.onNodeWithText("Mac").assertIsDisplayed()
        ui.onNodeWithText("Needs you").performClick()
        ui.onNodeWithText("Building").assertDoesNotExist()
        ui.onNodeWithContentDescription("Review, Approval, Session s2, Mac").performClick()
        ui.runOnIdle {assertEquals("b",selected!!.server.id);assertEquals("s2",selected!!.record.session);assertEquals("pane2",selected!!.record.block)}
    }
    @Test fun tabButtonOpensTheSwitcherDirectlyAndKeepsActivityRightmost() {
        val calls=mutableListOf<String>()
        ui.setContent {MaterialTheme {TerminalActions(true,true,{calls+="activity"},{calls+="switcher"})}}
        val pane=ui.onNodeWithContentDescription("Tabs and panes").fetchSemanticsNode().boundsInRoot
        val eye=ui.onNodeWithContentDescription("Activity").fetchSemanticsNode().boundsInRoot
        assertTrue(eye.left>=pane.right)
        ui.onNodeWithContentDescription("Tabs and panes").performClick()
        ui.runOnIdle {assertEquals(listOf("switcher"),calls)}
        ui.onNodeWithText("Switch pane").assertDoesNotExist()
        ui.onNodeWithText("New pane").assertDoesNotExist()
        ui.onNodeWithText("New tab").assertDoesNotExist()
        ui.onNodeWithContentDescription("Activity").performClick()
        ui.runOnIdle {assertEquals("activity",calls.last())}
    }
    @Test fun staleActivityIsDisabledAndShowsItsRefreshError() {
        val server=ServerProfile.create("Linux","https://linux.example","a");var selected=false
        val record=record("done","Finished task","s","p")
        ui.setContent {MaterialTheme {ActivitySheet(listOf(ActivitySource(server,false,false,"Connection interrupted",false,listOf(record))),{},{selected=true},{})}}
        ui.onNodeWithText("Connection interrupted").assertIsDisplayed()
        ui.onNodeWithContentDescription("Finished task, Finished, Session s, Linux").assertIsNotEnabled()
        ui.runOnIdle {assertFalse(selected)}
    }
    @Test fun markingAnItemSeenDoesNotOpenItsTerminal() {
        val server=ServerProfile.create("Linux","https://linux.example","a");val task=record("blocked","Review","s","p")
        val seen=androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet());var opened=0
        ui.setContent {MaterialTheme {ActivitySheet(listOf(ActivitySource(server,true,false,"",false,listOf(task),seen.value)),{},{opened++},{},onSeen={seen.value=setOf(it.record.key)})}}
        ui.onNodeWithContentDescription("Mark Review seen").performClick()
        ui.onNodeWithText("Seen").assertIsDisplayed();ui.onNodeWithText("Mark seen").assertIsNotEnabled()
        ui.runOnIdle {assertEquals(0,opened)}
    }
    @Test fun eyeHasAnAccessibleAttentionIndicator() {
        ui.setContent {MaterialTheme {TerminalActions(true,true,{},{},attentionCount=2)}}
        ui.onNodeWithContentDescription("Activity").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2 new activity items"))
    }

}
