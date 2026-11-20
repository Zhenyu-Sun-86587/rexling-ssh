package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp

private data class PaneMove(val source: String,val target: String?=null)

/** Server layout with one mounted terminal view per visible pane. */
@Composable
internal fun MultiPaneLayout(
    client: RexConnection, session: String, window: String, placements: List<PanePlacement>,
    focused: String, liveBlock: String, controlled: Boolean, programs: Map<String, ProgramIdentity>,
    waiting: Set<String>, fontSize: Float, onFocus: (String, Pair<Int,Int>?) -> Unit,
    onView: (TerminalView) -> Unit, onImage: (android.net.Uri, () -> Unit) -> Boolean,
    onPaste: () -> Boolean, imeEnterSubmits: Boolean, tapOpensKeyboard: Boolean,
    keyboardChrome: () -> Int, extendsUnderNavigation: Boolean, hazeState: HazeState, captureBackdrop: Boolean,
    bottomPaddingDp: Int = 6, bottomMargin: Boolean = true,
    splits: List<PaneSplitLine> = emptyList(), zoomed: String = "",
    ime: WindowInsets = WindowInsets.ime, navigation: WindowInsets = WindowInsets.navigationBars,
    paneActionsEnabled: Boolean = true,
    onSplit: (String,PaneSplit)->Unit = {id,split->client.createPane(split,id)},
    onSwap: (String,String)->Unit = {source,target->
        client.swapPanePositions(session,window,source,target,placements.map {it.id})
    },
) {
    val palette=LocalTerminalPalette.current
    val scheme=MaterialTheme.colorScheme
    // Rects are fractions of the tab; normalize to the tiled panes' own bounds.
    val left=placements.minOf {it.x};val top=placements.minOf {it.y}
    val spanX=(placements.maxOf {it.x+it.width}-left).coerceAtLeast(.01f)
    val spanY=(placements.maxOf {it.y+it.height}-top).coerceAtLeast(.01f)
    val sizes=remember(session,window) { mutableStateMapOf<String,Pair<Int,Int>>() }
    val density=LocalDensity.current
    val keepAwake=LocalKeepScreenAwake.current
    // A pane zoomed in Rex covers the whole area; the rest stay in the layout but are not shown or mirrored.
    val zoomedPane=placements.firstOrNull {it.id==zoomed}
    val mirrors=(zoomedPane?.let {listOf(it)} ?: placements).map {it.id}.filter {it!=liveBlock}
    val request=mirrors.mapNotNull {id->sizes[id]?.let {(columns,rows)->RexConnection.MirrorPane(id,columns,rows)}}
    // Debounced: while a border is dragged the panes resize on every frame, but their PTYs only once
    // the finger pauses or lifts (the focused pane's resize is debounced the same way).
    LaunchedEffect(client,session,window,request) {
        kotlinx.coroutines.delay(100)
        client.mirror(session,window,request)
    }
    // A border being dragged, and its ratio. The panes reflow live from it; it stays after release until
    // Rex reports the new layout (new [splits]), so nothing snaps back in between.
    var drag by remember(splits) {mutableStateOf<Pair<String,Float>?>(null)}
    val (laidOut,laidOutSplits)=drag?.let {(id,ratio)->splits.firstOrNull {it.id==id}?.let {movedSplit(placements,splits,it,ratio)}}
        ?: (placements to splits)
    val shownPanes=zoomedPane?.let {listOf(it.copy(x=left,y=top,width=spanX,height=spanY))} ?: laidOut
    val shownSplits=if(zoomedPane!=null) emptyList() else laidOutSplits
    // Keep the controls visible for as long as the move handle is held.
    var menuFor by remember(session,window) {mutableStateOf<String?>(null)}
    var move by remember(session,window,placements,zoomed,paneActionsEnabled) {mutableStateOf<PaneMove?>(null)}
    val moving=move!=null
    LaunchedEffect(menuFor,moving) {
        if(menuFor!=null && !moving) {
            kotlinx.coroutines.delay(4000)
            menuFor=null
        }
    }
    val paneCoordinates=remember(session,window) {mutableMapOf<String,LayoutCoordinates>()}
    val swap by rememberUpdatedState(onSwap)
    fun completeMove(source: String,target: String) {
        menuFor=null
        swap(source,target)
    }
    DisposableEffect(client,session,window) {onDispose {client.mirror(session,window,emptyList())}}
    // Like the desktop: each pane a rounded card on the window background, with room between them and
    // a thin accent outline on the focused one.
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    // The workspace reserves the navigation clearance when the bottom row has no extra margin.
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().then(if(captureBackdrop) Modifier.hazeSource(hazeState) else Modifier).background(scheme.background).padding(start=4.dp,top=4.dp,end=4.dp,bottom=if(bottomMargin) 4.dp else 0.dp)) {
        val gap=8.dp
        val visibleHeight=maxHeight
        for(pane in shownPanes) key(pane.id) {
            val x=maxWidth*((pane.x-left)/spanX)
            val y=visibleHeight*((pane.y-top)/spanY)
            val width=maxWidth*(pane.width/spanX)
            val heightFraction=pane.height/spanY
            val height=visibleHeight*heightFraction
            val active=pane.id==focused
            val dropTarget=move?.target==pane.id
            val moveSource=move?.source==pane.id
            val lastRow=!bottomMargin && pane.y+pane.height>=top+spanY-.001f
            val trim=if(lastRow) gap/2 else gap
            Box(Modifier.offset(x+gap/2,y+gap/2).size((width-gap).coerceAtLeast(0.dp),(height-trim).coerceAtLeast(0.dp))
                .onGloballyPositioned {paneCoordinates[pane.id]=it}
                .clip(shape).background(Color(palette.background))
                .drawWithContent {
                    drawContent()
                    if(dropTarget) drawRect(scheme.primary.copy(alpha=.16f))
                }
                // Keep the border and padding fixed while moving so the PTY grid stays the same.
                .border(1.5.dp,when {
                    moveSource || dropTarget || active -> scheme.primary
                    else -> scheme.outline.copy(alpha=.25f)
                },shape)
                .padding(1.5.dp)) {
                val live=active && pane.id==liveBlock
                val paneFrame by remember(client,pane.id) {
                    derivedStateOf(referentialEqualityPolicy()) { client.paneFrames[pane.id] }
                }
                var keyboardFocus by remember { mutableStateOf<TerminalKeyboardFocus?>(null) }
                val reportSize by rememberUpdatedState<(Int,Int)->Unit> { columns,rows->
                    sizes[pane.id]=columns to rows
                    if(live) client.resize(columns,rows)
                }
                val dimensions=remember { {columns: Int,rows: Int->reportSize(columns,rows)} }
                LaunchedEffect(live) {
                    if(live) sizes[pane.id]?.let {(columns,rows)->client.resize(columns,rows)}
                }
                // Restore the portion of keyboard coverage this pane lost when the parent shrank.
                // Round against both container heights so the PTY grid stays fixed at row boundaries.
                AndroidView(
                    factory={TerminalView(it)},
                    modifier=Modifier.fillMaxSize().clipToBounds().stableTerminalViewport(panToCursor=live,coverage={
                        val cover=keyboardCoverage(ime,navigation,keyboardChrome,extendsUnderNavigation)
                        val fullHeight=visibleHeight+cover.toDp()
                        (fullHeight*heightFraction-trim).coerceAtLeast(0.dp).roundToPx() -
                            (height-trim).coerceAtLeast(0.dp).roundToPx()
                    }) {keyboardFocus.takeIf {live}},
                    update={view->
                        view.palette=palette
                        view.textSp=fontSize
                        view.setTerminalPaddingDp(12,8,bottomPaddingDp)
                        view.interactive=live
                        view.keepAwakeMode=if(live) keepAwake else KeepScreenAwake.SYSTEM
                        view.keyboardSuggestions=programs[pane.id]?.kind!=ProgramKind.NEOVIM
                        view.imeEnterSubmits=imeEnterSubmits
                        view.tapOpensKeyboard=tapOpensKeyboard
                        view.controlled=live && controlled
                        view.onDimensions=dimensions
                        view.onKeyboardFocus=if(live) ({keyboardFocus=it}) else null
                        keyboardFocus=if(live) view.keyboardFocus else null
                        view.onInput=client::type
                        view.onActivate=client::takeControl
                        view.onMouseClick=client::click
                        view.onWheel=client::wheel
                        view.onScroll=client::scroll
                        view.mouseTarget=client::keyTarget
                        view.imageInputTarget=client::imageInputTarget
                        view.onImage=onImage
                        view.onPaste=onPaste
                        if(!live || !client.bindTerminalView(pane.id,view)) {
                            if(!live || view.screen==null) paneFrame?.let {view.screen=it}
                        }
                        if(live) onView(view)
                    },
                )
                if(!active) {
                    Box(Modifier.matchParentSize().semantics {contentDescription="Focus pane"}
                        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button) {
                            onFocus(pane.id,sizes[pane.id])
                        })
                    if(pane.id in waiting || paneFrame==null) Text(
                        if(paneFrame==null) "Opening terminal…" else "Reconnecting…",
                        Modifier.align(Alignment.Center).background(scheme.surface).padding(6.dp),
                        color=scheme.onSurfaceVariant,fontSize=11.sp,
                    )
                } else {
                    val display by client.displayLayout.collectAsState()
                    if(live && display.below) {
                        Text("↓ Latest",Modifier.align(Alignment.BottomEnd).padding(10.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(scheme.surface)
                            .clickable(role=Role.Button,onClick=client::scrollToBottom).padding(horizontal=12.dp,vertical=7.dp),
                            fontSize=11.sp,color=scheme.primary)
                    } else if(paneFrame==null && (!live || !display.hasScreen)) {
                        Text("Opening terminal…",Modifier.align(Alignment.Center),
                            fontFamily=MonoUi,fontSize=12.sp,color=scheme.onSurfaceVariant)
                    }
                }
                val isZoomed=pane.id==zoomed
                val showActions=menuFor==pane.id
                if(isZoomed || showActions) {
                    val splitPane: (PaneSplit)->Unit={split->
                        menuFor=null
                        onSplit(pane.id,split)
                    }
                    val zoomPane: ()->Unit={
                        menuFor=null
                        if(!isZoomed && !active) onFocus(pane.id,sizes[pane.id])
                        client.zoom(pane.id,!isZoomed)
                    }
                    PaneCornerActions(
                        Modifier.align(Alignment.TopEnd).padding(6.dp),
                        zoomed=isZoomed,compact=width-gap<174.dp,
                        enabled=paneActionsEnabled && !moving,
                        onSplit=splitPane,onZoom=zoomPane,
                    )
                    if(!isZoomed && paneActionsEnabled) {
                        val handleCoordinates=remember {arrayOfNulls<LayoutCoordinates>(1)}
                        val otherPanes=shownPanes.filter {it.id!=pane.id}
                        val targetActions=otherPanes.map {other->
                            CustomAccessibilityAction("Swap with pane ${shownPanes.indexOf(other)+1}") {
                                completeMove(pane.id,other.id)
                                true
                            }
                        }
                        PaneActionPill(Modifier.align(Alignment.TopStart).padding(6.dp),active=moveSource) {
                            Box(Modifier.size(36.dp)
                                .onGloballyPositioned {handleCoordinates[0]=it}
                                .semantics {
                                    contentDescription="Move pane"
                                    if(moveSource) stateDescription="Moving"
                                    customActions=targetActions
                                }
                                .pointerInput(session,window,placements,pane.id) {
                                    detectDragGestures(
                                        onDragStart={move=PaneMove(pane.id)},
                                        onDragCancel={move=null},
                                        onDragEnd={
                                            val target=move?.target
                                            move=null
                                            if(target!=null) completeMove(pane.id,target)
                                        },
                                    ) {change,_->
                                        change.consume()
                                        val point=handleCoordinates[0]?.takeIf {it.isAttached}?.localToWindow(change.position)
                                        val target=otherPanes.firstOrNull {other->
                                            point!=null && paneCoordinates[other.id]?.takeIf {it.isAttached}
                                                ?.boundsInWindow()?.contains(point)==true
                                        }?.id
                                        move=PaneMove(pane.id,target)
                                    }
                                },contentAlignment=Alignment.Center) {
                                ChromeSymbol(ChromeIcon.MOVE)
                            }
                        }
                    }
                } else {
                    for(corner in listOf(Alignment.TopStart,Alignment.TopEnd)) {
                        Box(Modifier.align(corner).size(44.dp)
                            .semantics {contentDescription=if(corner==Alignment.TopStart) "Pane actions, top left" else "Pane actions"}
                            .clickable(enabled=!moving,interactionSource=remember {MutableInteractionSource()},
                                indication=null,role=Role.Button) {menuFor=pane.id})
                    }
                }
            }
        }
        // Pane borders: drag the grip in the gap and the panes reflow with it; on release Rex moves the
        // border for every client.
        for(split in shownSplits) key(split.id) {
            val original=splits.firstOrNull {it.id==split.id} ?: split
            val dragging=drag?.first==split.id
            val shown=split.ratio
            val horizontal=split.horizontal
            val startX=maxWidth*((split.x-left)/spanX);val startY=maxHeight*((split.y-top)/spanY)
            val spanW=maxWidth*(split.width/spanX);val spanH=maxHeight*(split.height/spanY)
            val along=if(horizontal) spanW else spanH
            val alongPx=with(density) {along.toPx()}.coerceAtLeast(1f)
            val touch=24.dp
            val handle=if(horizontal) Modifier.offset(startX+spanW*shown-touch/2,startY).size(touch,spanH)
                else Modifier.offset(startX,startY+spanH*shown-touch/2).size(spanW,touch)
            Box(handle.semantics {contentDescription=if(horizontal) "Resize panes side by side" else "Resize stacked panes"}
                // Keyed on the split alone: re-keying on its moving ratio would restart the gesture mid-drag.
                .pointerInput(split.id,horizontal,alongPx) {
                    var ratio=original.ratio
                    val start: (androidx.compose.ui.geometry.Offset)->Unit={ratio=drag?.takeIf {it.first==original.id}?.second ?: original.ratio;drag=original.id to ratio}
                    val end: ()->Unit={drag?.takeIf {it.first==original.id}?.let {client.resizeSplit(original,it.second)}}
                    val cancel: ()->Unit={drag=null}
                    if(horizontal) detectHorizontalDragGestures(start,end,cancel) {change,amount->
                        change.consume();ratio=(ratio+amount/alongPx).coerceIn(.1f,.9f);drag=original.id to ratio}
                    else detectVerticalDragGestures(start,end,cancel) {change,amount->
                        change.consume();ratio=(ratio+amount/alongPx).coerceIn(.1f,.9f);drag=original.id to ratio}
                },contentAlignment=androidx.compose.ui.Alignment.Center) {
                Box(Modifier.background(if(dragging) scheme.primary else scheme.outline.copy(alpha=.5f),androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .then(if(horizontal) Modifier.size(4.dp,36.dp) else Modifier.size(36.dp,4.dp)))
            }
        }
    }
}

@Composable
private fun PaneCornerActions(
    modifier: Modifier,zoomed: Boolean,compact: Boolean,enabled: Boolean,
    onSplit: (PaneSplit)->Unit,onZoom: ()->Unit,
) {
    val splitActions=listOf(PaneSplit.RIGHT to ChromeIcon.SPLIT_RIGHT,PaneSplit.BELOW to ChromeIcon.SPLIT_BELOW)
    if(compact && !zoomed) {
        // Leave room for the move handle on narrow tiles.
        var expanded by remember {mutableStateOf(false)}
        Box(modifier) {
            PaneActionPill(Modifier) {
                ChromeButton(ChromeIcon.MORE,"More pane actions",enabled=enabled) {expanded=true}
            }
            DropdownMenu(expanded=expanded && enabled,onDismissRequest={expanded=false}) {
                for((split,icon) in splitActions) {
                    TerminalMenuItem(split.label,icon,onClick={expanded=false;onSplit(split)})
                }
                TerminalMenuItem("Zoom pane",ChromeIcon.ZOOM,onClick={expanded=false;onZoom()})
            }
        }
    } else {
        PaneActionPill(modifier) {
            if(!zoomed) for((split,icon) in splitActions) {
                ChromeButton(icon,split.label,enabled=enabled) {onSplit(split)}
            }
            ChromeButton(if(zoomed) ChromeIcon.UNZOOM else ChromeIcon.ZOOM,
                if(zoomed) "Restore pane layout" else "Zoom pane",enabled=enabled,onClick=onZoom)
        }
    }
}

/** Pane controls float over the terminal without changing its grid. */
@Composable private fun PaneActionPill(modifier: Modifier,active: Boolean=false,content: @Composable RowScope.()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val pill=androidx.compose.foundation.shape.RoundedCornerShape(50)
    val surface=if(active) scheme.primaryContainer else scheme.surface
    Row(modifier.clip(pill).background(surface.copy(alpha=.92f))
        .border(1.dp,scheme.outline.copy(alpha=.3f),pill).padding(horizontal=4.dp,vertical=2.dp),
        verticalAlignment=Alignment.CenterVertically,content=content)
}

/**
 * Whether this window has a foldable's whole inner screen (or a tablet's): at least 600dp in its
 * smaller dimension and not shared with another app. Split-screen and pop-up windows are not.
 */
@Composable internal fun isUnfoldedFullScreen(): Boolean {
    val configuration=LocalConfiguration.current
    val context=LocalContext.current
    return remember(configuration,context) {
        if(android.os.Build.VERSION.SDK_INT<30) return@remember false
        val manager=context.getSystemService(android.view.WindowManager::class.java)
        val screen=manager.maximumWindowMetrics.bounds;val window=manager.currentWindowMetrics.bounds
        val density=context.resources.displayMetrics.density
        minOf(screen.width(),screen.height())/density>=600f &&
            window.width()>=screen.width()*.95f && window.height()>=screen.height()*.95f
    }
}

/** The tab's tiled panes, when the multi-pane layout can show them: two to four, including [focused]. */
internal fun multiPaneTiles(window: WindowItem?,focused: String): List<PanePlacement>? {
    val tiled=window?.layout.orEmpty().filter {!it.floating && it.width>0f && it.height>0f}
    return tiled.takeIf {it.size in 2..4 && it.any {p->p.id==focused}}
}
