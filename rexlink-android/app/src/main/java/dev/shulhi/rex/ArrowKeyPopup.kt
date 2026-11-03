package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable internal fun ArrowPopupDockKey(enabled: Boolean,modifier: Modifier,onKey: (String)->Unit,readable: Boolean=enabled,inputKey: String="") {
    var open by remember(inputKey) {mutableStateOf(false)}
    LaunchedEffect(enabled) {if(!enabled) open=false}
    val provider=rememberSpeechBubblePositioner()
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(11.dp))
        .background(if(open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .semantics {contentDescription="Arrow keys";selected=open;stateDescription=if(open) "Expanded" else "Collapsed"}
        .clickable(enabled=enabled,role=Role.Button) {open=!open},contentAlignment=Alignment.Center) {
        ChromeSymbol(ChromeIcon.ARROWS,Modifier.size(20.dp),
            (if(open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface).copy(alpha=if(readable) 1f else .38f))
        if(open && enabled) Popup(popupPositionProvider=provider,onDismissRequest={open=false},
            properties=PopupProperties(focusable=false,dismissOnClickOutside=false,dismissOnBackPress=false)) {
            SpeechBubble(provider,Modifier.width(200.dp).semantics {contentDescription="Arrow key popup"}) {
                Box(Modifier.padding(8.dp)) {ArrowKeyPad(enabled,Modifier.fillMaxWidth().height(100.dp),onKey,readable,inputKey)}
            }
        }
    }
}

/** Keyboard-style arrow cluster; holds repeat, while moving off a key cancels input. */
@Composable internal fun ArrowKeyPad(enabled: Boolean,modifier: Modifier=Modifier,onKey: (String)->Unit,readable: Boolean=enabled,inputKey: String="") {
    val keys=listOf(listOf(null,"up",null),listOf("left","down","right"))
    val icons=mapOf("left" to ChromeIcon.LEFT,"down" to ChromeIcon.DOWN,"up" to ChromeIcon.UP,"right" to ChromeIcon.RIGHT)
    Column(modifier.semantics {contentDescription="Arrow directions"},verticalArrangement=Arrangement.spacedBy(4.dp)) {
        keys.forEach {row->
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                row.forEach {key->
                    if(key==null) Spacer(Modifier.weight(1f).fillMaxHeight())
                    else Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                        .semantics {contentDescription="${key.replaceFirstChar {it.uppercaseChar()}} arrow"}
                        .repeatKey(enabled,inputKey) {onKey(key)},contentAlignment=Alignment.Center) {
                        ChromeSymbol(icons.getValue(key),Modifier.size(22.dp),MaterialTheme.colorScheme.onSurface.copy(alpha=if(readable) 1f else .38f))
                    }
                }
            }
        }
    }
}
