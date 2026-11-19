package dev.shulhi.rex

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class TabBarPosition(val label: String) { BOTTOM("Bottom"), LEFT("Left"), RIGHT("Right") }
internal enum class TabRailStyle(val label: String) { ICONS("Icons"), TITLES("Icons and titles") }

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
internal enum class TabWidthClass(val title: String,val detail: String,val key: String) {
    NARROW("Narrow","Under 440dp: phones, and a split half of the upright inner screen","tab_layout_narrow"),
    MEDIUM("Medium","440–760dp: the cover screen, a split half held sideways, and the upright inner screen","tab_layout_medium"),
    WIDE("Wide","760dp and wider: the inner screen held sideways","tab_layout_wide");
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
    paneProgress: (WindowItem)->TerminalProgress?={null},tabAttention: (WindowItem)->Int={0},animateWorking: Boolean=true,actions: @Composable ()->Unit) {
    val titles=style==TabRailStyle.TITLES
    val scheme=MaterialTheme.colorScheme
    val list=rememberLazyListState()
    var menuId by remember(reorderKey) {mutableStateOf<String?>(null)}
    val ids=windows.map {it.id}
    val index=ids.indexOf(selectedId)
    LaunchedEffect(ids) {menuId=null}
    LaunchedEffect(selectedId,reorderKey,index) {if(index>=0) list.animateScrollToItem(index)}
    Column(Modifier.fillMaxHeight().width(if(titles) 184.dp else 60.dp).background(scheme.surface).padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics {contentDescription="Tabs"},state=list,
            contentPadding=PaddingValues(horizontal=6.dp,vertical=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
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
                Box(Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null)) {
                    val workPhase=rememberWorkingPhase(animateWorking && if(progress!=null) progress.busy && progress.percent==null else active && busy)
                    Row(Modifier.fillMaxWidth().height(48.dp)
                        .background(if(active) scheme.surfaceVariant else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            // The same bottom bar as the bottom tab bar: active, progress and working.
                            if(active || progress!=null) {
                                val origin=Offset(0f,size.height-2.dp.toPx());val bar=Size(size.width,2.dp.toPx())
                                if(progress!=null) {
                                    drawWorkingBar(indicator.copy(alpha=.18f),origin,bar)
                                    val fill=progress.percent?.let {bar.copy(width=bar.width*it/100f)} ?: bar
                                    drawWorkingBar(indicator,origin,fill,phase=if(progress.busy && progress.percent==null && animateWorking) workPhase.value else null)
                                } else drawWorkingBar(indicator,origin,bar,phase=if(busy && animateWorking) workPhase.value else null)
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
                        .padding(horizontal=if(titles) 8.dp else 0.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(titles) Arrangement.spacedBy(6.dp) else Arrangement.Center) {
                        Box(contentAlignment=Alignment.Center) {
                            ProgramStack(programs,pane,fixedSlot=true)
                            if(unread>0 || busy) Box(Modifier.align(Alignment.TopEnd).offset(x=2.dp,y=(-2).dp).size(8.dp)
                                .border(1.5.dp,scheme.background,CircleShape)
                                .background(if(unread>0) scheme.primary else indicator,CircleShape)
                                .semantics {testTag=if(unread>0) "Tab unread dot ${w.id}" else "Tab working dot ${w.id}"})
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
        Box(Modifier.padding(top=4.dp)) {actions()}
    }
}
