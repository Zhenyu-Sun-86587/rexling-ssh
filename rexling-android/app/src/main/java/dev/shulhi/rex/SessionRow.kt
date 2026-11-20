package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SessionRow(target: SessionTarget,active: Boolean,last: Boolean,closing: Boolean,
    onSelect: ()->Unit,onClose: ()->Unit,onRename: ()->Unit={},revealed: Boolean,onReveal: (Boolean)->Unit) {
    val actions=listOf(SwitcherRowAction("Rename session",ChromeIcon.EDIT,run=onRename),
        SwitcherRowAction("Close session",ChromeIcon.CLOSE,destructive=true,run=onClose))
    SwitcherSwipeRow(revealed,onReveal,actions,enabled=!closing) {
        Row(Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(active) .8f else .35f))
            .clickable(enabled=!closing,onClick=onSelect)
            .semantics {
                if(!closing) customActions=actions.map {action->CustomAccessibilityAction(action.label) {onReveal(false);action.run();true}}
            }
            .heightIn(min=56.dp).padding(start=10.dp,end=12.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(6.dp).background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f)) {
                Text(target.session.label,style=SettingsType.row,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Normal,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text((target.detail?.let {"${it.attachment} · ${it.tabs} tabs · ${it.panes} panes"} ?: "Details not loaded"),
                    style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            when {closing->CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=1.5.dp)
                active->Text("current",color=MaterialTheme.colorScheme.primary,style=SettingsType.detail,fontWeight=FontWeight.SemiBold)
                last->Text("previous",color=MaterialTheme.colorScheme.onSurfaceVariant,style=SettingsType.detail)}
        }
    }
}
