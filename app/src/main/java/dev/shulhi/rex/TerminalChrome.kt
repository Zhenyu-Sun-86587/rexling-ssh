package dev.shulhi.rex

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

internal enum class ChromeIcon { SPLIT, SEARCH, CHEVRON, SESSIONS, COPY, MORE, ADD, KEYBOARD, HIDE_KEYBOARD, LEFT, RIGHT, UP, DOWN, TAB }

@Composable internal fun ChromeSymbol(icon: ChromeIcon, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(19.dp)) {
        val u = size.minDimension / 24f
        val width = 1.65f * u
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1*u,y1*u), Offset(x2*u,y2*u), width, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) = drawRoundRect(color,
            Offset(x*u,y*u), Size(w*u,h*u), CornerRadius(radius*u), style = Stroke(width))
        fun arrow(dx: Float, dy: Float) {
            val x=12f; val y=12f
            line(x-dx*6,y-dy*6,x+dx*6,y+dy*6)
            line(x+dx*6,y+dy*6,x+dx*2-dy*4,y+dy*2+dx*4)
            line(x+dx*6,y+dy*6,x+dx*2+dy*4,y+dy*2-dx*4)
        }
        when(icon) {
            ChromeIcon.SPLIT -> { box(3f,4f,18f,16f);line(12f,4f,12f,20f) }
            ChromeIcon.SEARCH -> { drawCircle(color,6f*u,Offset(10f*u,10f*u),style=Stroke(width));line(15f,15f,21f,21f) }
            ChromeIcon.CHEVRON -> {line(7f,10f,12f,15f);line(12f,15f,17f,10f)}
            ChromeIcon.SESSIONS -> { box(4f,8f,13f,12f); line(8f,4f,20f,4f); line(20f,4f,20f,16f); line(7f,12f,11f,12f); line(7f,16f,13f,16f) }
            ChromeIcon.COPY -> { box(8f,8f,12f,12f); line(15f,4f,4f,4f); line(4f,4f,4f,15f) }
            ChromeIcon.MORE -> { for(y in listOf(5f,12f,19f)) drawCircle(color,1.4f*u,Offset(12f*u,y*u)) }
            ChromeIcon.ADD -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            ChromeIcon.LEFT -> arrow(-1f,0f)
            ChromeIcon.RIGHT -> arrow(1f,0f)
            ChromeIcon.UP -> arrow(0f,-1f)
            ChromeIcon.DOWN -> arrow(0f,1f)
            ChromeIcon.TAB -> { line(4f,12f,17f,12f); line(12f,7f,17f,12f); line(12f,17f,17f,12f); line(20f,6f,20f,18f) }
            ChromeIcon.KEYBOARD, ChromeIcon.HIDE_KEYBOARD -> {
                box(2f,4f,20f,14f)
                for(x in listOf(6f,10f,14f,18f)) { line(x,8f,x+.2f,8f); line(x,11f,x+.2f,11f) }
                line(8f,14f,16f,14f)
                if(icon==ChromeIcon.HIDE_KEYBOARD) { line(9f,21f,12f,23f); line(12f,23f,15f,21f) }
            }
        }
    }
}

@Composable internal fun ChromeButton(icon: ChromeIcon, description: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled=enabled, role=Role.Button, onClick=onClick)
        .semantics { contentDescription=description }, contentAlignment=Alignment.Center) {
        ChromeSymbol(icon, color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) .85f else .3f))
    }
}

internal val MonoUi = FontFamily(Font(R.font.jetbrains_mono_regular),Font(R.font.jetbrains_mono_bold,FontWeight.Bold))

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun SessionHeader(sessions: List<SessionItem>, selectedId: String,
    connected: Boolean, enabled: Boolean, controlled: Boolean, canControl: Boolean,
    programs: List<ProgramIdentity>, paneIndex: Int, directory: String,
    onSessions: ()->Unit, onSelect: (String)->Unit, onLast: ()->Unit, onControl: ()->Unit, title: String="", working: Boolean=false, expanded: Boolean=false, menu: @Composable ()->Unit) {
    val program=programs.getOrNull(paneIndex) ?: ProgramIdentity.Terminal
    val selectedIndex=sessions.indexOfFirst { it.id==selectedId }.coerceAtLeast(0)
    val pager=rememberPagerState(initialPage=selectedIndex,pageCount={sessions.size.coerceAtLeast(1)})
    val latestSelected by rememberUpdatedState(selectedId)
    val latestSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selectedId,sessions.map { it.id }) {
        if(sessions.isNotEmpty() && !pager.isScrollInProgress && pager.currentPage!=selectedIndex) pager.animateScrollToPage(selectedIndex)
    }
    LaunchedEffect(pager,sessions,enabled) {
        // The initial page is not a swipe; startup selection comes from saved session history.
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { index ->
            if(enabled && sessions.getOrNull(index)?.id!=latestSelected) sessions.getOrNull(index)?.let { latestSelect(it.id) }
        }
    }
    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal=9.dp),verticalAlignment=Alignment.CenterVertically) {
        if(!expanded) {ProgramStack(programs,paneIndex,framed=true);Spacer(Modifier.width(7.dp))}
        Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.Center) {
            if(!expanded) Row(Modifier.fillMaxWidth().height(19.dp),verticalAlignment=Alignment.CenterVertically) {
                if(working) {WorkingIndicator();Spacer(Modifier.width(4.dp))}
                Text(title.ifBlank {program.title},fontWeight=FontWeight.Bold,fontSize=13.sp,letterSpacing=0.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=if(title.isBlank()) 90.dp else 190.dp))
                if(directory.isNotBlank()) Text(directory,Modifier.weight(1f).padding(start=6.dp,end=6.dp),
                    fontFamily=MonoUi,fontSize=9.sp,letterSpacing=0.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(if(expanded) 42.dp else 17.dp)) {
                val pageWidth=if(expanded) 160.dp else maxWidth.coerceAtLeast(40.dp)
                val pagePadding=if(expanded) ((maxWidth-pageWidth)/2).coerceAtLeast(0.dp) else 0.dp
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    HorizontalPager(pager,Modifier.fillMaxWidth().height(if(expanded) 32.dp else 17.dp),userScrollEnabled=enabled && sessions.size>1,
                        pageSize=PageSize.Fixed(pageWidth),contentPadding=PaddingValues(horizontal=pagePadding),pageSpacing=6.dp,key={sessions.getOrNull(it)?.id ?: "empty"}) { index ->
                        val item=sessions.getOrNull(index)
                        val active=item?.id==selectedId
                        Row(Modifier.requiredWidth(pageWidth).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                            .border(1.dp,if(expanded && active) MaterialTheme.colorScheme.primary.copy(alpha=.5f) else Color.Transparent,RoundedCornerShape(19.dp))
                            .padding(horizontal=if(expanded) 9.dp else 0.dp).graphicsLayer { alpha=if(active || sessions.isEmpty()) 1f else .38f }
                            .combinedClickable(onClick={if(active || item==null) onSessions() else onSelect(item.id)},onLongClick=onLast)
                            .semantics { contentDescription=if(active || item==null) "Switch session" else "Session ${item.label}" },
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(if(expanded) 6.dp else 4.dp)) {
                            Box(Modifier.size(if(expanded) 7.dp else 5.dp).background(if(connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                            Text(item?.label ?: "Connect",fontFamily=MonoUi,fontWeight=if(expanded) FontWeight.Bold else FontWeight.Normal,
                                fontSize=if(expanded) 12.sp else 9.sp,letterSpacing=0.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                                color=if(expanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier=Modifier.widthIn(max=(pageWidth-if(expanded) 50.dp else 24.dp).coerceAtLeast(10.dp)))
                            ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(if(expanded) 13.dp else 11.dp),MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(expanded && sessions.size>1) PositionDots(pager.currentPage,sessions.size,Modifier.height(5.dp))
                }
            }
        }
        if(!expanded) {
            Box(Modifier.height(29.dp).clip(RoundedCornerShape(18.dp))
                .border(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=if(canControl) .45f else .15f),RoundedCornerShape(18.dp))
                .background(if(controlled) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(enabled=canControl,role=Role.Button,onClick=onControl)
                .semantics { contentDescription=if(controlled) "Release terminal control" else "Take terminal control" }
                .padding(horizontal=10.dp),contentAlignment=Alignment.Center) {
                Text(if(controlled) "Release" else "Take over",fontSize=10.sp,letterSpacing=0.sp,fontWeight=FontWeight.SemiBold,
                    color=(if(controlled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary).copy(alpha=if(canControl) 1f else .4f))
            }
            menu()
        }
    }
}

/** Read animation state only during drawing, so terminal chrome does not recompose every tick. */
@Composable private fun WorkingIndicator() {
    val transition=androidx.compose.animation.core.rememberInfiniteTransition(label="Program working")
    val phase=transition.animateFloat(0f,1f,animationSpec=androidx.compose.animation.core.infiniteRepeatable(
        animation=androidx.compose.animation.core.tween(1000,easing=androidx.compose.animation.core.LinearEasing)),label="Working dots")
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(16.dp,6.dp).semantics {contentDescription="Program working"}) {
        repeat(3) {i ->
            val wave=(1f+kotlin.math.cos((phase.value-i/3f)*2f*kotlin.math.PI.toFloat()))/2f
            drawCircle(color.copy(alpha=.3f+.7f*wave),1.4.dp.toPx(),Offset((3+i*5).dp.toPx(),size.height/2))
        }
    }
}

@Composable internal fun PositionDots(index: Int,count: Int,modifier: Modifier=Modifier) {
    val first=(index-3).coerceIn(0,(count-7).coerceAtLeast(0))
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
        repeat(minOf(count,7)) { offset ->
            val active=first+offset==index
            Box(Modifier.size(if(active) 15.dp else 5.dp,5.dp).background(
                if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.35f),RoundedCornerShape(3.dp)))
        }
    }
}

@Composable internal fun TerminalTabs(windows: List<WindowItem>,selectedId: String,enabled: Boolean,
    onSelect: (String)->Unit,onPanes: ()->Unit,panePrograms: (WindowItem)->List<ProgramIdentity>,selectedPane: (WindowItem)->Int,paneTitle: (WindowItem,Int)->String={_,_->""},paneWorking: (WindowItem)->Boolean={false},add: @Composable ()->Unit) {
    val list=rememberLazyListState()
    val index=windows.indexOfFirst { it.id==selectedId }
    LaunchedEffect(selectedId,windows.map {it.id}) {if(index>=0) {
        val item=list.layoutInfo.visibleItemsInfo.firstOrNull {it.index==index}
        if(item==null || item.offset<0 || item.offset+item.size>list.layoutInfo.viewportEndOffset) list.animateScrollToItem(index)
    }}
    Row(Modifier.fillMaxWidth().height(49.dp).padding(start=11.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f).fillMaxHeight().clipToBounds(),state=list,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            itemsIndexed(windows,key={_,w->w.id}) {i,w ->
                val active=w.id==selectedId
                val indicatorColor=MaterialTheme.colorScheme.primary
                val programs=panePrograms(w)
                val pane=selectedPane(w)
                val program=programs.getOrNull(pane) ?: ProgramIdentity.Terminal
                val title=program.tabTitle(w.label,w.blocks.getOrNull(pane)?.label.orEmpty(),i+1,paneTitle(w,pane))
                Column(Modifier.height(44.dp).width(IntrinsicSize.Max).widthIn(min=70.dp,max=185.dp)
                    .drawWithContent {
                        drawContent()
                        if(active) drawRect(indicatorColor,Offset(0f,size.height-2.dp.toPx()),Size(size.width,2.dp.toPx()))
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .semantics(mergeDescendants=true) {contentDescription="Tab $title, ${program.label}"}
                    .selectable(active,enabled,role=Role.Tab) {onSelect(w.id)},horizontalAlignment=Alignment.CenterHorizontally) {
                    Row(Modifier.weight(1f).padding(horizontal=10.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                        ProgramStack(programs,pane)
                        if(paneWorking(w)) WorkingIndicator()
                        Text(title,Modifier.widthIn(max=125.dp),fontSize=12.sp,lineHeight=18.sp,
                            style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,
                            color=if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis,letterSpacing=0.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
        ChromeButton(ChromeIcon.SPLIT,"Switch pane",enabled,onClick=onPanes)
        add()
    }
}

@Composable internal fun KeyboardBar(controlled: Boolean,enabled: Boolean,control: Boolean,alt: Boolean,
    keyboardVisible: Boolean,onControl: ()->Unit,onAlt: ()->Unit,onKey: (String)->Unit,onKeyboard: ()->Unit) {
    var expanded by remember {mutableStateOf(false)}
    val keyScroll=rememberScrollState()
    LaunchedEffect(expanded) {if(!expanded) keyScroll.scrollTo(0)}
    LaunchedEffect(keyboardVisible) {if(!keyboardVisible) {expanded=false;keyScroll.scrollTo(0)}}
    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha=.4f)))
        Row(Modifier.fillMaxWidth().height(53.dp).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(keyScroll),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                DockKey("esc",controlled,description="Escape") {onKey("esc")}
                Box(Modifier.width(22.dp).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics {contentDescription=if(expanded) "Hide extra keys" else "Show extra keys";stateDescription=if(expanded) "Expanded" else "Collapsed"}
                    .clickable(enabled=enabled,role=Role.Button) {expanded=!expanded},contentAlignment=Alignment.Center) {
                    ChromeSymbol(ChromeIcon.CHEVRON,Modifier.size(14.dp).graphicsLayer {rotationZ=if(expanded) 180f else 0f})
                }
                DockKey("ctrl",controlled,control,onClick=onControl)
                DockKey("alt",controlled,alt,onClick=onAlt)
                DockKey("tab",controlled,description="Tab key") {onKey("tab")}
                DockKey("←",controlled,description="Left arrow") {onKey("left")}
                DockKey("↓",controlled,description="Down arrow") {onKey("down")}
                DockKey("↑",controlled,description="Up arrow") {onKey("up")}
                DockKey("→",controlled,description="Right arrow") {onKey("right")}
            }
            ChromeButton(if(keyboardVisible) ChromeIcon.HIDE_KEYBOARD else ChromeIcon.KEYBOARD,
                if(!controlled) "Take control and show keyboard" else if(keyboardVisible) "Hide keyboard" else "Show keyboard",enabled,onClick=onKeyboard)
        }
        if(expanded) {
            val extra=listOf((1..6).map {"F$it" to "f$it"},(7..12).map {"F$it" to "f$it"},
                listOf("Home" to "home","PgUp" to "pageup","PgDn" to "pagedown","End" to "end","Ins" to "insert","Del" to "delete"))
            for(row in extra) Row(Modifier.fillMaxWidth().height(43.dp).padding(bottom=6.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                for((label,key) in row) DockKey(label,controlled,description=if(key.startsWith("f")) "Function key $label" else label) {onKey(key)}
            }
        }
    }
}
@Composable private fun DockKey(label: String,enabled: Boolean,active: Boolean=false,description: String=label,onClick: ()->Unit) {
    Box(Modifier.width(if(label=="ctrl") 40.dp else 34.dp).fillMaxHeight().clip(RoundedCornerShape(11.dp))
        .background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics(mergeDescendants=true) {contentDescription=description;selected=active}
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Text(label,fontFamily=MonoUi,fontSize=11.sp,fontWeight=if(active) FontWeight.Bold else FontWeight.Normal,
            color=(if(active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(enabled) 1f else .38f),letterSpacing=0.sp)
    }
}
