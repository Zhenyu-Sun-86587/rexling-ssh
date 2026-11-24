package dev.shulhi.rex

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

internal enum class TabBarPosition(val label: String) { BOTTOM("Bottom"), LEFT("Left"), RIGHT("Right") }
internal enum class TabRailStyle(val label: String) { ICONS("Icons"), TITLES("Icons and titles") }

internal fun tabRailWidth(style: TabRailStyle)=when(style) {
    TabRailStyle.ICONS->52.dp
    TabRailStyle.TITLES->152.dp
}

/** Where the tab bar sits, and how a side rail draws its tabs. Kept separately for folded and unfolded screens. */
internal data class TabBarLayout(val position: TabBarPosition=TabBarPosition.BOTTOM,val style: TabRailStyle=TabRailStyle.ICONS) {
    val side: Boolean get()=position!=TabBarPosition.BOTTOM
    fun encode()="${position.name},${style.name}"
    companion object {
        fun decode(raw: String?,default: TabBarLayout): TabBarLayout {
            val parts=raw?.split(',') ?: return default
            val position=TabBarPosition.entries.firstOrNull {it.name==parts.getOrNull(0)} ?: return default
            val style=TabRailStyle.entries.firstOrNull {it.name==parts.getOrNull(1)} ?: default.style
            return TabBarLayout(position,style)
        }
    }
}

/**
 * Which tab layout setting applies, by the app window's own width: the room the tabs actually have,
 * whether the window is a folded cover screen, the unfolded inner screen, or a split half of it.
 * On a Galaxy Z Fold: a portrait split half (~350dp) is narrow like a phone; the cover screen (~475dp)
 * and a landscape split half (~465dp) are medium; the inner screen held sideways (~933dp) is wide.
 */
internal enum class TabWidthClass(val title: String,val range: String,val key: String) {
    NARROW("Narrow","Under 440 dp","tab_layout_narrow"),
    MEDIUM("Medium","440–759 dp","tab_layout_medium"),
    WIDE("Wide","760 dp and up","tab_layout_wide");
    companion object {
        fun of(widthDp: Int): TabWidthClass = when {
            widthDp<440 -> NARROW
            widthDp<760 -> MEDIUM
            else -> WIDE
        }
    }
}

/** The current app window's width class, and its width in dp; both follow folding, rotation and split-screen resizes. */
@Composable internal fun currentTabWidth(): Pair<TabWidthClass,Int> {
    val width=LocalConfiguration.current.screenWidthDp
    return TabWidthClass.of(width) to width
}

/** A vertical tab list beside the terminal; [actions] (switcher, Activity, keyboard) sit at its foot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TerminalTabRail(windows: List<WindowItem>,selectedId: String,enabled: Boolean,
    onSelect: (String)->Unit,panePrograms: (WindowItem)->List<ProgramIdentity>,selectedPane: (WindowItem)->Int,
    side: TabBarPosition,style: TabRailStyle,paneTitle: (WindowItem,Int)->String={_,_->""},paneWorking: (WindowItem)->Boolean={false},
    onClose: (WindowItem)->Unit={},onMove: (String,String?)->Boolean={_,_->false},reorderKey: String="",
    paneProgress: (WindowItem)->TerminalProgress?={null},tabAttention: (WindowItem)->Int={0},animateWorking: Boolean=true,
    onStyle: (TabRailStyle)->Unit={},extendUnderNav: Boolean=false,
    actions: @Composable ()->Unit) {
    val titles=style==TabRailStyle.TITLES
    // Swipe across the rail toward the terminal to show titles, back toward the screen edge to hide them.
    // Resizes are debounced, so the animated width sends the pane only its final size.
    val width by androidx.compose.animation.core.animateDpAsState(tabRailWidth(style),
        androidx.compose.animation.core.tween(180),label="Tab rail width")
    val outward=if(side==TabBarPosition.LEFT) 1f else -1f
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current) {32.dp.toPx()}
    var swipe by remember {mutableFloatStateOf(0f)}
    // Gesture navigation treats edge swipes as Back. Android lets an app opt out for at most 200dp of
    // each edge, so claim the rail's lowest 200dp (its buttons and nearest tabs); the toggle below
    // always works.
    val exclusion=with(androidx.compose.ui.platform.LocalDensity.current) {200.dp.toPx()}
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    val scheme=MaterialTheme.colorScheme
    val divider=scheme.outlineVariant.copy(alpha=.65f)
    val list=rememberLazyListState()
    var menuId by remember(reorderKey) {mutableStateOf<String?>(null)}
    val ids=windows.map {it.id}
    val index=ids.indexOf(selectedId)
    LaunchedEffect(ids) {menuId=null}
    LaunchedEffect(selectedId,reorderKey,index) {if(index>=0) list.animateScrollToItem(index)}
    Column(Modifier.fillMaxHeight().width(width).background(scheme.surface)
        .drawWithContent {
            drawContent()
            val stroke=.5.dp.toPx()
            val x=if(side==TabBarPosition.LEFT) size.width-stroke/2 else stroke/2
            drawLine(divider,Offset(x,0f),Offset(x,size.height),strokeWidth=stroke)
        }
        // Reaches the screen edge under the gesture handle; its buttons stay above it.
        .then(if(extendUnderNav) Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier)
        .systemGestureExclusion {coordinates->
            val height=coordinates.size.height.toFloat()
            androidx.compose.ui.geometry.Rect(0f,(height-exclusion).coerceAtLeast(0f),coordinates.size.width.toFloat(),height)
        }
        .draggable(androidx.compose.foundation.gestures.rememberDraggableState {swipe+=it},androidx.compose.foundation.gestures.Orientation.Horizontal,
            onDragStarted={swipe=0f},onDragStopped={
                val next=when {
                    swipe*outward>threshold -> TabRailStyle.TITLES
                    swipe*outward< -threshold -> TabRailStyle.ICONS
                    else -> style
                }
                swipe=0f
                if(next!=style) {haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);onStyle(next)}
            })
        .semantics {customActions=listOf(CustomAccessibilityAction(if(titles) "Show icons only" else "Show tab titles") {
            onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES);true})}
        .padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics {contentDescription="Tabs"},state=list,
            contentPadding=PaddingValues(vertical=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.Bottom)) {
            itemsIndexed(windows,key={_,w->w.id}) {i,w->
                fun moveBy(delta: Int): Boolean {
                    val target=i+delta
                    if(target !in windows.indices) return false
                    val reordered=ids.toMutableList().apply {removeAt(i);add(target,w.id)}
                    return onMove(w.id,reordered.getOrNull(target+1))
                }
                val active=w.id==selectedId
                val programs=panePrograms(w)
                val pane=selectedPane(w)
                val program=programs.getOrNull(pane) ?: ProgramIdentity.Loading
                val title=program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
                val unread=tabAttention(w)
                val progress=paneProgress(w)
                val busy=paneWorking(w) || progress?.busy==true
                val indicator=terminalProgressColor(progress)
                val breathe=active && animateWorking && if(progress!=null) progress.busy && progress.percent==null else busy
                val workPhase=rememberWorkingPhase(breathe)
                Box(Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null)) {
                    Row(Modifier.fillMaxWidth().height(48.dp)
                        .background(if(active) scheme.surfaceVariant else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            if(active) {
                                val bar=Size(3.dp.toPx(),22.dp.toPx())
                                val inset=5.dp.toPx()
                                val x=if(layoutDirection==LayoutDirection.Ltr) inset else size.width-inset-bar.width
                                val origin=Offset(x,(size.height-bar.height)/2)
                                val brightness=if(breathe) workingBarBrightness(workPhase.value) else 1f
                                drawTabMarker(indicator,origin,bar,progress?.percent,brightness,vertical=true)
                            }
                        }
                        .semantics(mergeDescendants=true) {contentDescription="Tab $title, ${program.label}"+(progress?.let {", ${it.description}"} ?: if(busy) ", working" else "")}
                        .semantics {
                            selected=active
                            if(unread>0) stateDescription="$unread new activity items"
                            if(enabled) customActions=buildList {
                                if(i>0) add(CustomAccessibilityAction("Move up") {moveBy(-1)})
                                if(i<windows.lastIndex) add(CustomAccessibilityAction("Move down") {moveBy(1)})
                            }
                        }
                        .combinedClickable(enabled=enabled,role=Role.Tab,onLongClickLabel="Tab options",
                            onLongClick={menuId=w.id},onClick={onSelect(w.id)})
                        .padding(start=if(titles) 14.dp else 5.dp,end=if(titles) 10.dp else 0.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(titles) Arrangement.spacedBy(4.dp) else Arrangement.Center) {
                        Box(Modifier.semantics {testTag="Tab program icon ${w.id}"},contentAlignment=Alignment.Center) {
                            ProgramStack(programs,pane,fixedSlot=true)
                            if(unread>0) {
                                val iconInset=(36.dp-programStackPaintedWidth(programs.size))/2
                                Box(Modifier.align(Alignment.TopEnd).offset(x=4.dp-iconInset,y=(-2).dp).size(10.dp)
                                    .border(1.5.dp,if(active) scheme.surfaceVariant else scheme.surface,CircleShape)
                                    .background(scheme.primary,CircleShape)
                                    .semantics {testTag="Tab unread dot ${w.id}"})
                            }
                        }
                        if(titles) Text(title,Modifier.weight(1f),fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                            fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,color=if(active) scheme.onSurface else scheme.onSurfaceVariant)
                    }
                    DropdownMenu(menuId==w.id,{menuId=null},containerColor=scheme.surface,tonalElevation=0.dp) {
                        TerminalMenuItem("Move up",ChromeIcon.UP,enabled=enabled && i>0,onClick={menuId=null;moveBy(-1)})
                        TerminalMenuItem("Move down",ChromeIcon.DOWN,enabled=enabled && i<windows.lastIndex,onClick={menuId=null;moveBy(1)})
                        TerminalMenuItem("Close tab",ChromeIcon.CLOSE,enabled=enabled,onClick={menuId=null;onClose(w)})
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal=9.dp,vertical=4.dp),thickness=.5.dp,color=divider)
        // Points the way the rail will go: toward the terminal to show titles, toward the edge to hide them.
        val towardTerminal=if(side==TabBarPosition.LEFT) -90f else 90f
        ChromeButton(ChromeIcon.CHEVRON,if(titles) "Show icons only" else "Show tab titles",
            modifier=Modifier.graphicsLayer {rotationZ=if(titles) -towardTerminal else towardTerminal}) {
            onStyle(if(titles) TabRailStyle.ICONS else TabRailStyle.TITLES)
        }
        Box(Modifier.padding(top=4.dp)) {actions()}
    }
}
