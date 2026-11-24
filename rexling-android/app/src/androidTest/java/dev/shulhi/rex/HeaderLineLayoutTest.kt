package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeaderLineLayoutTest {
    @get:Rule val ui=createComposeRule()
    private val titles=listOf("Task title","* Task title","✳ Task title","✻ Task title","✽ Task title","✢ Task title","✳️ Task title","⠋ Task title","🚀 Task title")
    private fun header(title: State<String>,directory: State<String>,scale: State<Float>) {
        ui.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,scale.value)) {MaterialTheme {
                Box(Modifier.width(340.dp).testTag("header")) {
                    SessionHeader(listOf(SessionItem("a","alpha · Mac")),"a",true,true,
                        listOf(ProgramIdentity(ProgramKind.CLAUDE,"Claude Code")),0,directory.value,{},{},{},title=title.value,serverIcons=mapOf("a" to ServerIcon.MACBOOK),menu={})
                }
            }}
        }
    }
    private fun bounds(node: SemanticsNodeInteraction)=node.fetchSemanticsNode().boundsInRoot
    private fun text(value: String)=ui.onNodeWithText(value,useUnmergedTree=true)
    private fun baseline(node: SemanticsNodeInteraction): Float {
        val coordinates=node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.positionInRoot().y+coordinates[FirstBaseline]
    }
    @Test fun claudeSpinnerChangesKeepAllThreeRowsAndTheirBaselinesInPlace() {
        val title=mutableStateOf(titles.first());val directory=mutableStateOf("~/Dev/rexlink");val scale=mutableFloatStateOf(1f)
        header(title,directory,scale)
        for(fontScale in listOf(1f,1.5f,2f)) {
            ui.runOnIdle {scale.floatValue=fontScale;title.value=titles.first()}
            val initial=text(title.value).fetchSemanticsNode().layoutInfo
            val original=initial.width to initial.height;val base=baseline(text(title.value))
            val cwd=bounds(text(directory.value));val strip=bounds(ui.onNodeWithContentDescription("Session strip"));val root=bounds(ui.onNodeWithTag("header"))
            for(value in titles.drop(1)) {
                ui.runOnIdle {title.value=value}
                // Glyph bounds can vary; the allocated line and placed baseline must not.
                val line=text(value).fetchSemanticsNode().layoutInfo
                assertEquals(value,original,line.width to line.height)
                assertEquals(value,base,baseline(text(value)),.6f)
                assertEquals(value,cwd,bounds(text(directory.value)))
                assertEquals(value,strip,bounds(ui.onNodeWithContentDescription("Session strip")))
                assertEquals(value,root,bounds(ui.onNodeWithTag("header")))
            }
        }
    }
    @Test fun aTemporarilyMissingDirectoryKeepsItsLineReserved() {
        val title=mutableStateOf("Task title");val directory=mutableStateOf("~/Dev/rexlink");val scale=mutableFloatStateOf(1f)
        header(title,directory,scale)
        val original=bounds(text(title.value));val strip=bounds(ui.onNodeWithContentDescription("Session strip"));val root=bounds(ui.onNodeWithTag("header"))
        ui.runOnIdle {directory.value=""}
        assertEquals(original,bounds(text(title.value)));assertEquals(strip,bounds(ui.onNodeWithContentDescription("Session strip")));assertEquals(root,bounds(ui.onNodeWithTag("header")))
        ui.runOnIdle {directory.value="~/Another/directory"}
        assertEquals(original,bounds(text(title.value)));assertEquals(strip,bounds(ui.onNodeWithContentDescription("Session strip")));assertEquals(root,bounds(ui.onNodeWithTag("header")))
    }

    @Test fun textOpensTheSessionDrawerInBothHeaderLayouts() {
        var keyboard by mutableIntStateOf(0)
        var compact by mutableStateOf(false)
        var drawerOpens=0
        var menuOpens=0
        var overlap=0
        val title="Task title"
        val directory="~/Dev/rexlink"
        val session="ShipPDF · Shipyard"
        ui.setContent {MaterialTheme {
            val haze=remember {HazeState()}
            Column(Modifier.width(340.dp).height(150.dp)) {
                SessionHeader(listOf(SessionItem("a",session)),"a",true,true,emptyList(),0,directory,
                    onSessions={drawerOpens++},onSelect={},onLast={},title=title,
                    collapseForIme=if(compact) WindowInsets(bottom=keyboard) else null,
                    serverIcons=mapOf("a" to ServerIcon.CLOUD),headerHaze=haze,onHeaderOverlap={overlap=it},
                    modifier=Modifier.testTag("header").zIndex(1f),
                    menu={ChromeButton(ChromeIcon.MORE,"Terminal menu") {menuOpens++}})
                Box(Modifier.weight(1f).fillMaxWidth().underWorkspaceHeader {overlap}.hazeSource(haze)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.Red)
                        drawRect(Color.Blue,topLeft=Offset(size.width/2f,0f),size=Size(size.width/2f,size.height))
                    }
                }
            }
        }}
        val header=ui.onNodeWithTag("header")
        val fullHeight=bounds(header).height
        listOf(title,directory,session).forEach {label->text(label).performTouchInput {click()}}
        ui.runOnIdle {assertEquals(3,drawerOpens);keyboard=300}
        text(directory).assertIsDisplayed()
        assertEquals(fullHeight,bounds(header).height)
        ui.runOnIdle {compact=true}
        text(directory).assertIsNotDisplayed()
        assertTrue(bounds(header).height<fullHeight)
        val inCompact=hasAnyAncestor(hasTestTag("Compact session header"))
        ui.onNode(hasContentDescription("Cloud") and inCompact,useUnmergedTree=true).assertIsDisplayed()
        val backdrop=header.captureToImage().toPixelMap()
        val y=backdrop.height-8
        val left=backdrop[backdrop.width/4,y]
        val edge=backdrop[backdrop.width/2,y]
        val right=backdrop[backdrop.width*3/4,y]
        assertTrue("The collapsed header should blur the backdrop edge: $left, $edge, $right",
            left.red>edge.red && edge.red>right.red && left.blue<edge.blue && edge.blue<right.blue)
        ui.onNode(hasText(title) and hasText(session)).assertIsDisplayed().performTouchInput {click()}
        ui.onNode(hasContentDescription("Terminal menu") and inCompact).performClick()
        ui.runOnIdle {
            assertEquals(4,drawerOpens)
            assertEquals(1,menuOpens)
            keyboard=0
        }
        text(directory).assertIsDisplayed()
        assertEquals(fullHeight,bounds(header).height)
    }
}
