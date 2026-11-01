package dev.shulhi.rex

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable internal fun ProgramStack(programs: List<ProgramIdentity>, selected: Int, framed: Boolean=false) {
    val items=programs.ifEmpty {listOf(ProgramIdentity.Terminal)}
    val front=selected.coerceIn(items.indices)
    val others=items.indices.filter {it!=front}.take(2)
    val active=items[front]
    val frontY=if(framed) 4.dp else 3.5.dp
    Box(Modifier.size(if(framed) 40.dp else 28.dp,if(framed) 35.dp else 25.dp)
        .clearAndSetSemantics {contentDescription=active.label+(if(items.size>1) ", ${items.size} panes" else "")}) {
        for((layer,index) in others.reversed().withIndex()) {
            val shift=(others.size-layer)*3
            PaneProgramTile(items[index],Modifier.offset(x=shift.dp,y=(frontY-shift.dp).coerceAtLeast(0.dp))
                .size(if(framed) 31.dp else 19.dp,if(framed) 24.dp else 15.dp),framed)
        }
        PaneProgramTile(active,Modifier.offset(y=frontY)
            .size(if(framed) 34.dp else 22.dp,if(framed) 27.dp else 18.dp),framed)
    }
}

@Composable private fun PaneProgramTile(program: ProgramIdentity, modifier: Modifier, large: Boolean) {
    val catalog=when(program.kind) {
        ProgramKind.CLAUDE -> R.drawable.catalog_claude_code
        ProgramKind.CODEX -> R.drawable.catalog_codex
        ProgramKind.NEOVIM -> R.drawable.catalog_neovim
        ProgramKind.VIM -> R.drawable.catalog_vim
        ProgramKind.GIT -> R.drawable.catalog_git
        ProgramKind.PYTHON -> R.drawable.catalog_python
        ProgramKind.NODE -> R.drawable.catalog_node
        ProgramKind.TERMINAL -> R.drawable.catalog_shell
        ProgramKind.MONITOR -> if(program.executable=="btop") R.drawable.catalog_btop else R.drawable.catalog_htop
        else -> null
    }
    if(catalog!=null) {
        Image(painterResource(catalog),program.label,modifier.shadow(if(large) 2.dp else 1.dp,RoundedCornerShape(if(large) 7.dp else 5.dp)),contentScale=ContentScale.FillBounds)
        return
    }
    val dark=LocalTerminalPalette.current.dark
    val colors=when(program.kind) {
        ProgramKind.CLAUDE -> listOf(0xffffb27b,0xffee743f,0xffcb4f28)
        ProgramKind.NEOVIM,ProgramKind.VIM -> listOf(0xff8dc492,0xff4a9760,0xff296f48)
        ProgramKind.GIT -> listOf(0xffffa485,0xffdb6544,0xffad3e2d)
        ProgramKind.PYTHON -> listOf(0xff93bfe6,0xff4f8ec4,0xff305e92)
        ProgramKind.NODE -> listOf(0xffa6ce91,0xff6aa057,0xff3c7138)
        ProgramKind.TMUX -> listOf(0xffc1b6e3,0xff9183bb,0xff645487)
        ProgramKind.MONITOR -> listOf(0xff9ed8d8,0xff5da4ae,0xff326b81)
        else -> if(dark) listOf(0xff65788f,0xff3f5067,0xff253347) else listOf(0xffffffff,0xfff0f3f7,0xffd3dbe6)
    }.map {Color(it)}
    val neutral=program.kind in listOf(ProgramKind.CODEX,ProgramKind.TERMINAL,ProgramKind.SSH)
    val ink=if(neutral && !dark) Color(0xff323e50) else Color.White
    val radius=if(large) 7.dp else 5.dp
    val shape=RoundedCornerShape(radius)
    val reflection=Brush.verticalGradient(0f to Color.White.copy(alpha=.34f),.22f to Color.White.copy(alpha=.08f),
        .5f to Color.Transparent,1f to Color.Black.copy(alpha=.06f))
    Box(modifier.shadow(if(large) 3.dp else 1.dp,shape).clip(shape)
        .background(Brush.verticalGradient(colors)).background(reflection)
        .border(1.dp,Color.White.copy(alpha=if(dark) .85f else 1f),shape)
        .drawWithContent {
            drawContent()
            val inset=1.5.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha=.65f),Color.White.copy(alpha=.05f))),
                Offset(inset,inset),Size((size.width-inset*2).coerceAtLeast(0f),(size.height-inset*2).coerceAtLeast(0f)),
                CornerRadius((radius.toPx()-inset).coerceAtLeast(0f)),style=Stroke(.65.dp.toPx()))
        },contentAlignment=Alignment.Center) {
        ProgramIcon(program,Modifier.size(if(large) 19.dp else 13.dp),ink)
    }
}
