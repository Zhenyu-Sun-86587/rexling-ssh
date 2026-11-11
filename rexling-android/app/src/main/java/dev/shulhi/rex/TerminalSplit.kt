package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

internal fun splitPaneBlocks(blocks: List<BlockItem>,selected: String): List<BlockItem> {
    val visible=blocks.take(8)
    val active=blocks.firstOrNull {it.id==selected}
    return if(active!=null && visible.none {it.id==selected}) visible.dropLast(1)+active else visible
}

/** Every tile receives a persistent read-only data subscription; only focus owns input/resize. */
@Composable internal fun TerminalSplit(
    client: RexConnection,blocks: List<BlockItem>,chosen: String,liveBlock: String,screen: TerminalScreen?,
    controlled: Boolean,fontSize: Float,programs: Map<String,ProgramIdentity>,titles: Map<String,String>,
    onSelect: (String)->Unit,onView: (TerminalView)->Unit,onInput: (String)->Unit,onScroll: (Int)->Unit,
    onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: ()->Unit,onDimensions: (Int,Int)->Unit,
    onImage: (android.net.Uri,()->Unit)->Boolean,onPaste: ()->Boolean,modifier: Modifier=Modifier,
) {
    val palette=LocalTerminalPalette.current
    val frames=client.paneFrames
    BoxWithConstraints(modifier) {
        val panes=splitPaneBlocks(blocks,chosen)
        val columns=if(maxWidth>maxHeight) if(panes.size>4) 3 else 2 else if(panes.size>3) 2 else 1
        val rows=(panes.size+columns-1)/columns
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            repeat(rows) {row->
                Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    panes.drop(row*columns).take(columns).forEach {block->key(block.id) {
                        val active=block.id==chosen && block.id==liveBlock
                        val frame=if(block.id==liveBlock && screen!=null) screen else frames[block.id]
                        val shape=RoundedCornerShape(12.dp)
                        Column(Modifier.weight(1f).fillMaxHeight().clip(shape).background(Color(palette.background))
                            .border(if(active) 2.dp else 1.dp,if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,shape)) {
                            Row(Modifier.fillMaxWidth().background(if(active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {onSelect(block.id)}.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                                val program=programs[block.id] ?: ProgramIdentity.Terminal
                                PaneProgramTile(program,Modifier.size(22.dp,18.dp),false,0f)
                                Text(program.paneTitle(block.label,titles[block.id].orEmpty()),Modifier.padding(start=6.dp).weight(1f),
                                    fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
                                    color=if(active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().then(if(active) Modifier else Modifier.clickable {onSelect(block.id)})) {
                                if(frame!=null) AndroidView(factory={TerminalView(it)},modifier=Modifier.fillMaxSize(),update={view->
                                    view.onDimensions=if(active) onDimensions else {_,_->}
                                    view.fitToPane=true;view.setTerminalPaddingDp(6,4,4);view.palette=palette;view.textSp=fontSize
                                    if(!active || !client.bindTerminalView(block.id,view)) view.screen=frame
                                    view.interactive=active;view.controlled=active && controlled;view.inputAvailable=active
                                    view.onInput=if(active) onInput else ({})
                                    view.onActivate=if(active) onActivate else ({onSelect(block.id)})
                                    view.onScroll=if(active) onScroll else ({})
                                    view.onWheel=if(active) onWheel else ({_,_,_,_,_->})
                                    view.onMouseClick=if(active) client::click else ({_,_,_,_->})
                                    view.mouseTarget=client::keyTarget;view.imageInputTarget=client::imageInputTarget
                                    view.onImage=if(active) onImage else ({_,release->release();false})
                                    view.onPaste=if(active) onPaste else ({false})
                                    view.onPaneDrag=null;view.onPaneDragEnd=null
                                    if(active) onView(view)
                                }) else Text("Opening…",Modifier.align(Alignment.Center),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }}
                }
            }
        }
    }
}
