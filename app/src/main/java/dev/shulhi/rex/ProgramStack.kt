package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable internal fun ProgramStack(programs: List<ProgramIdentity>, selected: Int, framed: Boolean=false) {
    val items=programs.ifEmpty {listOf(ProgramIdentity.Terminal)}
    val front=selected.coerceIn(items.indices)
    val others=items.indices.filter {it!=front}.take(2)
    val active=items[front]
    Box(Modifier.size(if(framed) 43.dp else 24.dp,if(framed) 44.dp else 23.dp)
        .clearAndSetSemantics {contentDescription=active.label+(if(items.size>1) ", ${items.size} panes" else "")}) {
        for((layer,index) in others.reversed().withIndex()) {
            val shift=(others.size-layer)*3
            PaneProgramTile(items[index],Modifier.offset(x=shift.dp,y=(if(framed) 6-shift else 4-shift.coerceAtMost(4)).dp)
                .size(if(framed) 34.dp else 16.dp),framed)
        }
        if(framed || others.isNotEmpty()) PaneProgramTile(active,
            Modifier.offset(y=(if(framed) if(others.isEmpty()) 3 else 6 else 4).dp).size(if(framed) 37.dp else 18.dp),framed)
        else Box(Modifier.size(24.dp,23.dp),contentAlignment=Alignment.Center) {ProgramIcon(active)}
    }
}

@Composable private fun PaneProgramTile(program: ProgramIdentity, modifier: Modifier, large: Boolean) {
    val dark=LocalTerminalPalette.current.dark
    val style=when(program.kind) {
        ProgramKind.CLAUDE -> 0xff35251d to 0xfff0ad83
        ProgramKind.NEOVIM,ProgramKind.VIM -> 0xff183626 to 0xffa0d98d
        ProgramKind.GIT -> 0xff402521 to 0xffffae98
        ProgramKind.PYTHON -> 0xff183249 to 0xffa4d1f4
        ProgramKind.NODE -> 0xff213b28 to 0xffb0dc91
        ProgramKind.TMUX -> 0xff302844 to 0xffd2c2ff
        ProgramKind.MONITOR -> 0xff183c3c to 0xff9de2d9
        else -> 0xff192c48 to 0xffe3edff
    }
    val face=if(large) Color(style.first) else if(dark) Color(0xff1c2731) else Color(0xffeef1f7)
    val accent=Color(style.second)
    val shape=RoundedCornerShape(if(large) 10.dp else 4.dp)
    Box(modifier.shadow(if(large) 2.dp else 0.dp,shape).clip(shape).background(face).border(1.dp,
        if(large) accent.copy(alpha=.4f) else MaterialTheme.colorScheme.outline,shape),contentAlignment=Alignment.Center) {
        ProgramIcon(program,Modifier.size(if(large) 23.dp else 13.dp),if(large) accent else null)
    }
}
