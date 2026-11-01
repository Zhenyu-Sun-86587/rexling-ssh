package dev.shulhi.rex

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable internal fun TerminalCarousel(blocks: List<BlockItem>,selectedBlock: String,liveBlock: String,
    liveScreen: TerminalScreen?,frames: Map<String,TerminalScreen>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onDimensions: (Int,Int)->Unit,onSelect: (String)->Unit,onPanes: ()->Unit,
    onView: (TerminalView)->Unit,modifier: Modifier=Modifier) {
    val palette=LocalTerminalPalette.current
    val scope=rememberCoroutineScope()
    var visualId by remember(blocks.map {it.id}) {mutableStateOf(selectedBlock)}
    var drag by remember {mutableFloatStateOf(0f)}
    var dragging by remember {mutableStateOf(false)}
    var settling by remember {mutableStateOf(false)}
    var toast by remember {mutableStateOf(false)}
    val latestSelect by rememberUpdatedState(onSelect)
    val index=blocks.indexOfFirst {it.id==visualId}.coerceAtLeast(0)
    LaunchedEffect(selectedBlock) {if(!settling && !dragging) {visualId=selectedBlock;drag=0f}}
    LaunchedEffect(visualId) {toast=true;delay(1000);toast=false}
    BoxWithConstraints(modifier.clipToBounds()) {
        val cardWidth=(maxWidth-28.dp).coerceAtLeast(80.dp)
        val cardLeft=14.dp
        val stride=with(LocalDensity.current){(cardWidth+8.dp).toPx()}
        val leftPx=with(LocalDensity.current){cardLeft.toPx()}
        val candidate=(index+if(drag< -stride*.12f) 1 else if(drag>stride*.12f) -1 else 0).coerceIn(0,(blocks.size-1).coerceAtLeast(0))
        val flingSpeed=with(LocalDensity.current){450.dp.toPx()}
        fun finish(dx: Float,velocity: Float) {
            val step=if(dx < -stride*.18f || (dx< -20f && velocity < -flingSpeed)) 1 else if(dx > stride*.18f || (dx>20f && velocity>flingSpeed)) -1 else 0
            val target=(index+step).coerceIn(0,(blocks.size-1).coerceAtLeast(0))
            dragging=false;settling=true
            if(target!=index) blocks.getOrNull(target)?.let {latestSelect(it.id)}
            scope.launch {
                val animation=Animatable(drag)
                animation.animateTo(if(target==index) 0f else if(target>index) -stride else stride,tween(190)) {drag=value}
                if(target!=index) visualId=blocks[target].id
                drag=0f;settling=false;toast=true
            }
        }
        for(i in (index-1).coerceAtLeast(0)..(index+1).coerceAtMost(blocks.lastIndex)) {
            val block=blocks[i]
            val center=i==index
            val frame=if(block.id==liveBlock && liveScreen!=null) liveScreen else frames[block.id]
            val position=(i-index)*stride+drag
            key(block.id) {
                Column(Modifier.width(cardWidth).fillMaxHeight().graphicsLayer {
                    translationX=kotlin.math.round(leftPx+position)
                    alpha=1f-(abs(position)/stride).coerceIn(0f,1f)*.48f
                }.clip(RoundedCornerShape(21.dp)).background(Color(palette.background))
                    .then(if(center) Modifier else Modifier.clickable(role=Role.Button){latestSelect(block.id)})) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(start=6.dp,end=6.dp,top=8.dp)) {
                        if(frame!=null) AndroidView(
                            factory={TerminalView(it)},modifier=Modifier.fillMaxSize().clipToBounds(),
                            update={view ->
                                view.palette=palette;view.textSp=fontSize;view.screen=frame
                                view.interactive=center
                                view.controlled=center && controlled && block.id==liveBlock
                                view.onInput=onInput
                                view.onDimensions=if(center && block.id==liveBlock) onDimensions else {_,_->}
                                view.onPaneDrag=if(center) {dx,active ->
                                    if(!settling) {
                                        dragging=active
                                        drag=if((index==0&&dx>0)||(index==blocks.lastIndex&&dx<0)) dx*.2f else dx
                                        if(!active) drag=0f
                                    }
                                } else null
                                view.onPaneDragEnd=if(center) {dx,velocity->if(!settling) finish(dx,velocity)} else null
                                view.importantForAccessibility=if(center) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                if(center) onView(view)
                            })
                        else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            Text(if(center) "Opening terminal…" else block.label,fontFamily=MonoUi,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(22.dp).clickable(role=Role.Button,onClick=onPanes)
                        .semantics {contentDescription="Pane ${i+1} of ${blocks.size}. Switch pane"},
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
                        if(blocks.size>1) {
                            PositionDots(i,blocks.size)
                        }
                    }
                }
            }
        }
        if((dragging || settling || toast) && blocks.size>1 && blocks.getOrNull(candidate)!=null) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom=58.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal=15.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(29.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center) {
                        Text((candidate+1).toString(),fontFamily=MonoUi,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimary,fontSize=14.sp)
                    }
                    Text(blocks[candidate].label,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                }
                Spacer(Modifier.height(9.dp));PositionDots(candidate,blocks.size)
            }
        }
    }
}
