package dev.shulhi.rex

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TabPaneSwitcher(
    windows: List<WindowItem>,selectedWindow: String,selectedBlock: String,
    programs: Map<String,ProgramIdentity>,titles: Map<String,String>,directories: Map<String,String>,
    selectedPane: (WindowItem)->Int,enabled: Boolean,canCreateTab: Boolean,canCreatePane: Boolean,
    onTab: (String)->Unit,onPane: (String)->Unit,onNewTab: ()->Unit,onNewPane: ()->Unit,
    onCloseTab: (WindowItem)->Unit,onClosePane: (BlockItem)->Unit,onClose: ()->Unit,initialPage: Int=0,
) {
    val pager=rememberPagerState(initialPage=initialPage.coerceIn(0,1),pageCount={2})
    val scope=rememberCoroutineScope()
    val current=windows.firstOrNull {it.id==selectedWindow}
    val panes=current?.blocks.orEmpty()
    val panelHeight=(260+maxOf(windows.size,panes.size,1).coerceAtMost(5)*76).dp.coerceAtMost(600.dp)
    fun tabTitle(tab: WindowItem): String {
        val block=tab.blocks.getOrNull(selectedPane(tab))
        val program=programs[block?.id] ?: ProgramIdentity.Terminal
        return program.tabTitle(tab.label,block?.label.orEmpty(),windows.indexOf(tab)+1,titles[block?.id].orEmpty())
    }
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().height(panelHeight).padding(bottom=12.dp)) {
            Box(Modifier.padding(horizontal=20.dp)) {SettingsHeader("Tabs & panes","Close tab and pane switcher",onClose)}
            PrimaryTabRow(selectedTabIndex=pager.currentPage,containerColor=MaterialTheme.colorScheme.surface,divider={}) {
                listOf("Tabs" to windows.size,"Panes" to panes.size).forEachIndexed {page,(label,count)->
                    Tab(selected=pager.currentPage==page,onClick={scope.launch {pager.animateScrollToPage(page)}},selectedContentColor=MaterialTheme.colorScheme.primary,unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,text={
                        Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(label,style=SettingsType.row)
                            Text(count.toString(),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                }
            }
            HorizontalPager(pager,Modifier.fillMaxWidth().weight(1f).semantics {contentDescription="Tab and pane pages"},verticalAlignment=Alignment.Top) {page->
                Column(Modifier.fillMaxSize()) {
                    Text(if(page==0) "Tabs in this session" else current?.let {"Panes in ${tabTitle(it)}"} ?: "Panes in the current tab",
                        Modifier.padding(horizontal=20.dp,vertical=14.dp),style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup(),contentPadding=PaddingValues(horizontal=20.dp,vertical=2.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(page==0) {
                            if(windows.isEmpty()) item {SwitcherEmpty("No tabs yet","Create a tab to start a terminal.")}
                            items(windows,key={it.id}) {tab->
                                val focus=selectedPane(tab)
                                val block=tab.blocks.getOrNull(focus)
                                val icons=tab.blocks.map {programs[it.id] ?: ProgramIdentity.Terminal}
                                val title=tabTitle(tab)
                                val count="${tab.blocks.size} pane${if(tab.blocks.size==1) "" else "s"}"
                                val directory=directories[block?.id].orEmpty()
                                SwitcherItem(title,if(directory.isBlank()) count else "$count · $directory",icons,focus,tab.id==selectedWindow,
                                    "Tab $title",enabled && tab.blocks.isNotEmpty(),{onTab(tab.id)},enabled,"Close tab $title",{onCloseTab(tab)})
                            }
                        } else {
                            if(panes.isEmpty()) item {SwitcherEmpty("No panes yet","Choose a tab or create a new one.")}
                            items(panes,key={it.id}) {block->
                                val program=programs[block.id] ?: ProgramIdentity.Terminal
                                val title=program.paneTitle(block.label,titles[block.id].orEmpty())
                                SwitcherItem(title,directories[block.id].orEmpty().ifBlank {program.label},listOf(program),0,block.id==selectedBlock,
                                    "Pane $title",enabled,{onPane(block.id)},enabled,"Close pane $title",{onClosePane(block)})
                            }
                        }
                    }
                    FilledTonalButton(onClick=if(page==0) onNewTab else onNewPane,enabled=(if(page==0) canCreateTab else canCreatePane) && !pager.isScrollInProgress,
                        modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).heightIn(min=48.dp)) {
                        ChromeSymbol(ChromeIcon.ADD,Modifier.size(18.dp),MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(if(page==0) "New tab" else "New pane",style=SettingsType.row)
                    }
                }
            }
        }
    }
}

@Composable private fun SwitcherEmpty(title: String,description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=SettingsType.row)
        Text(description,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SwitcherItem(title: String,subtitle: String,programs: List<ProgramIdentity>,focus: Int,active: Boolean,
    description: String,enabled: Boolean,onSelect: ()->Unit,canClose: Boolean,closeDescription: String,onClose: ()->Unit) {
    val primary=MaterialTheme.colorScheme.primary
    Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)) {
        Row(Modifier.fillMaxWidth().selectable(active,enabled=enabled,role=Role.RadioButton,onClick=onSelect)
            .semantics {contentDescription=description;stateDescription=if(active) "Active" else ""}.padding(start=12.dp,end=4.dp,top=10.dp,bottom=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).height(30.dp).then(if(active) Modifier.background(primary,RoundedCornerShape(2.dp)) else Modifier))
            Box(Modifier.width(52.dp),contentAlignment=Alignment.Center) {ProgramStack(programs,focus,framed=true,fixedSlot=true)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title,style=SettingsType.row,color=if(active) primary else MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(subtitle,style=SettingsType.detail,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            IconButton(onClick=onClose,enabled=canClose,modifier=Modifier.semantics {contentDescription=closeDescription}) {
                ChromeSymbol(ChromeIcon.CLOSE,Modifier.size(17.dp),if(canClose) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.38f))
            }
        }
    }
}
