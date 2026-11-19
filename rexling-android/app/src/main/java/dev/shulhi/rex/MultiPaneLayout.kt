package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The multi-pane layout: a tab's tiled panes side by side, as the desktop arranges them.
 *
 * It sits beside the single-pane carousel rather than inside it. The focused pane is the caller's
 * ordinary live terminal ([focusedContent]), so input, keyboard, scrolling and sizing go through the
 * same code as in the single-pane layout. The other panes show the engine's mirrored frames
 * ([frames]); tapping one focuses it. Each mirrored pane reports the grid that fits its area, and
 * [onMirror] asks the engine to stream and size them; leaving the layout stops the mirrors.
 */
@Composable internal fun MultiPaneLayout(placements: List<PanePlacement>,focused: String,frames: Map<String,TerminalScreen>,
    fontSize: Float,onFocus: (String)->Unit,onMirror: (List<RexConnection.MirrorPane>)->Unit,bottomPaddingDp: Int=6,bottomMargin: Boolean=true,splits: List<PaneSplitLine> = emptyList(),onResize: (PaneSplitLine,Float)->Unit={_,_->},
    zoomed: String="",onZoom: (String,Boolean)->Unit={_,_->},focusedContent: @Composable ()->Unit) {
    val palette=LocalTerminalPalette.current
    val scheme=MaterialTheme.colorScheme
    // Rects are fractions of the tab; normalize to the tiled panes' own bounds.
    val left=placements.minOf {it.x};val top=placements.minOf {it.y}
    val spanX=(placements.maxOf {it.x+it.width}-left).coerceAtLeast(.01f)
    val spanY=(placements.maxOf {it.y+it.height}-top).coerceAtLeast(.01f)
    val sizes=remember {mutableStateMapOf<String,Pair<Int,Int>>()}
    // A pane zoomed in Rex covers the whole area; the rest stay in the layout but are not shown or mirrored.
    val zoomedPane=placements.firstOrNull {it.id==zoomed}
    val mirrors=(zoomedPane?.let {listOf(it)} ?: placements).map {it.id}.filter {it!=focused}
    val request=mirrors.mapNotNull {id->sizes[id]?.let {(columns,rows)->RexConnection.MirrorPane(id,columns,rows)}}
    // Debounced: while a border is dragged the panes resize on every frame, but their PTYs only once
    // the finger pauses or lifts (the focused pane's resize is debounced the same way).
    LaunchedEffect(request) {kotlinx.coroutines.delay(100);onMirror(request)}
    // A border being dragged, and its ratio. The panes reflow live from it; it stays after release until
    // Rex reports the new layout (new [splits]), so nothing snaps back in between.
    var drag by remember(splits) {mutableStateOf<Pair<String,Float>?>(null)}
    val (laidOut,laidOutSplits)=drag?.let {(id,ratio)->splits.firstOrNull {it.id==id}?.let {movedSplit(placements,splits,it,ratio)}}
        ?: (placements to splits)
    val shownPanes=zoomedPane?.let {listOf(it.copy(x=left,y=top,width=spanX,height=spanY))} ?: laidOut
    val shownSplits=if(zoomedPane!=null) emptyList() else laidOutSplits
    // The pane whose corner menu is open; it closes by itself after a few seconds.
    var menuFor by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(menuFor) {if(menuFor!=null) {kotlinx.coroutines.delay(4000);menuFor=null}}
    DisposableEffect(Unit) {onDispose {onMirror(emptyList())}}
    // Like the desktop: each pane a rounded card on the window background, with room between them and
    // a thin accent outline on the focused one.
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    // Without [bottomMargin] the bottom row ends at the area's edge, which the caller already keeps clear.
    BoxWithConstraints(Modifier.fillMaxSize().background(scheme.background).padding(start=4.dp,top=4.dp,end=4.dp,bottom=if(bottomMargin) 4.dp else 0.dp)) {
        val gap=8.dp
        for(pane in shownPanes) key(pane.id) {
            val x=maxWidth*((pane.x-left)/spanX);val y=maxHeight*((pane.y-top)/spanY)
            val width=maxWidth*(pane.width/spanX);val height=maxHeight*(pane.height/spanY)
            val active=pane.id==focused
            val lastRow=!bottomMargin && pane.y+pane.height>=top+spanY-.001f
            Box(Modifier.offset(x+gap/2,y+gap/2).size((width-gap).coerceAtLeast(0.dp),(height-if(lastRow) gap/2 else gap).coerceAtLeast(0.dp))
                .clip(shape).background(Color(palette.background))
                // Same border width and padding focused or not, so focusing never changes a pane's grid.
                .border(1.5.dp,if(active) scheme.primary else scheme.outline.copy(alpha=.25f),shape)
                .padding(1.5.dp)) {
                if(active) focusedContent()
                else {
                    AndroidView(factory={TerminalView(it)},modifier=Modifier.fillMaxSize().background(Color(palette.background)),update={view->
                        view.palette=palette;view.textSp=fontSize
                        // Matches the live terminal's padding, which the pane gets when focused.
                        view.setTerminalPaddingDp(12,8,bottomPaddingDp)
                        view.interactive=false;view.controlled=false
                        view.onDimensions={columns,rows->sizes[pane.id]=columns to rows}
                        frames[pane.id]?.let {view.screen=it}
                    })
                    // Taps focus the pane; it then becomes the live terminal.
                    Box(Modifier.matchParentSize().semantics {contentDescription="Focus pane"}
                        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button) {onFocus(pane.id)})
                }
                // Pane actions, like the desktop's: tap the top-right corner to show them. A zoomed
                // pane always shows its way back.
                val isZoomed=pane.id==zoomed
                if(isZoomed || menuFor==pane.id) PaneActions(isZoomed,Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(6.dp)) {
                    // Zooming a pane also focuses it, so typing goes to the pane on screen.
                    menuFor=null;if(!isZoomed && !active) onFocus(pane.id);onZoom(pane.id,!isZoomed)
                }
                else Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).size(44.dp).semantics {contentDescription="Pane actions"}
                    .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,role=Role.Button) {menuFor=pane.id})
            }
        }
        // Pane borders: drag the grip in the gap and the panes reflow with it; on release Rex moves the
        // border for every client.
        val density=androidx.compose.ui.platform.LocalDensity.current
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
                    val end: ()->Unit={drag?.takeIf {it.first==original.id}?.let {onResize(original,it.second)}}
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

/** The floating pill of pane actions shown at a pane's top-right corner. For now: zoom, or restore. */
@Composable private fun PaneActions(zoomed: Boolean,modifier: Modifier,onZoom: ()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val pill=androidx.compose.foundation.shape.RoundedCornerShape(50)
    Row(modifier.clip(pill).background(scheme.surface.copy(alpha=.92f)).border(1.dp,scheme.outline.copy(alpha=.3f),pill).padding(horizontal=4.dp,vertical=2.dp),
        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        ChromeButton(if(zoomed) ChromeIcon.UNZOOM else ChromeIcon.ZOOM,if(zoomed) "Restore pane layout" else "Zoom pane",onClick=onZoom)
    }
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
