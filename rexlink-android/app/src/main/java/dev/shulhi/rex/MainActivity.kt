package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val model: RexModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeAlert(intent)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark=when(model.appearance) {Appearance.SYSTEM->isSystemInDarkTheme();Appearance.DARK->true;Appearance.LIGHT->false}
            SideEffect {WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            RexTheme(dark,model.theme(dark)) {RexApp(model)}
        }
    }
    private fun consumeAlert(intent: Intent?) {
        AlertDestinations(BackgroundAlerts.preferences(this)).consume(intent?.data)?.let(model::openAlert)
        intent?.data=null
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);consumeAlert(intent)}
    override fun onStart(){super.onStart();BackgroundAlerts.foreground.value=true;model.resume()}
    override fun onStop(){if(!isChangingConfigurations) BackgroundAlerts.foreground.value=false;model.pause();super.onStop()}
}

@Composable private fun RexTheme(dark: Boolean,theme: AppTheme,content: @Composable ()->Unit) {
    val base=if(dark) darkColorScheme(primary=Color(0xff315cf4),onPrimary=Color.White,
        background=Color(0xff090c11),surface=Color(0xff141922),onSurface=Color(0xffe5e9f2),
        surfaceVariant=Color(0xff232934),onSurfaceVariant=Color(0xff969fb1),outline=Color(0xff2b3341))
    else lightColorScheme(primary=Color(0xff3156f4),onPrimary=Color.White,
        background=Color(0xfff3f4f9),surface=Color(0xfffdfdff),onSurface=Color(0xff171b26),
        surfaceVariant=Color(0xffe9ebf2),onSurfaceVariant=Color(0xff62697c),outline=Color(0xffd8dce8))
    val container = if(dark) Color(0xff1b212c) else Color(0xffeceef5)
    val primaryContainer = if(dark) Color(0xff17254d) else Color(0xffe4eaff)
    val onPrimaryContainer = if(dark) Color(0xffbed0ff) else Color(0xff193dba)
    val scheme = base.copy(surfaceTint=Color.Transparent,
        surfaceDim=base.background,surfaceBright=base.surfaceVariant,
        surfaceContainerLowest=base.background,surfaceContainerLow=base.surface,
        surfaceContainer=container,surfaceContainerHigh=container,surfaceContainerHighest=base.surfaceVariant,
        primaryContainer=primaryContainer,onPrimaryContainer=onPrimaryContainer,
        secondary=base.primary,onSecondary=base.onPrimary,secondaryContainer=primaryContainer,onSecondaryContainer=onPrimaryContainer,
        tertiary=base.primary,onTertiary=base.onPrimary,tertiaryContainer=primaryContainer,onTertiaryContainer=onPrimaryContainer)
    CompositionLocalProvider(LocalTerminalPalette provides theme.terminal) {
        MaterialTheme(colorScheme=scheme.withPreset(theme),content=content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexApp(model: RexModel) {
    val client=model.activeClient
    key(model.activeServerId) {RexWorkspace(model,client)}
    if(model.notificationNotice.isNotBlank()) AlertDialog(onDismissRequest={model.notificationNotice=""},title={Text("Notification")},text={Text(model.notificationNotice)},confirmButton={TextButton(onClick={model.notificationNotice=""}) {Text("OK")}})
}

private data class TerminalCloseTarget(val session: String,val id: String,val label: String,val tab: Boolean,val blocks: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexWorkspace(model: RexModel,client: RexConnection) {
    val state by client.chromeState.collectAsState()
    val navigation=client.navigationRequest
    val displayedSession=navigation.session ?: state.selectedSession
    val currentSessionKey=sessionKey(model.activeServerId,displayedSession)
    val sessionStrip=SessionCatalog.strip(model.sessionTargets,SessionTarget(model.activeServer,
        SessionItem(displayedSession,state.sessions.firstOrNull {it.id==displayedSession}?.label ?: if(state.connected) "No session" else state.phase)))
    val displayedBlock=navigation.block ?: state.selectedBlock
    val changingSession=navigation.session!=null && navigation.session!=state.selectedSession
    val windows=if(changingSession) emptyList() else state.windows
    val window=windows.firstOrNull {w->w.blocks.any {it.id==displayedBlock}} ?: windows.firstOrNull {it.blocks.isNotEmpty()}
    val ready=state.connected && !navigation.pending && !state.creating
    val controlled=state.controlled && ready && client.hasScreen
    val keyTarget=client.keyTarget()
    val palette=LocalTerminalPalette.current
    var endpoint by rememberSaveable {mutableStateOf(client.savedEndpoint)}
    var showServer by rememberSaveable {mutableStateOf(true)}
    var servers by remember {mutableStateOf(false)}
    var connectionError by remember {mutableStateOf<String?>(null)}
    var picker by remember {mutableStateOf("")}
    var newSession by remember {mutableStateOf(false)}
    var newName by rememberSaveable {mutableStateOf("")}
    var menu by remember {mutableStateOf(false)}
    var appearance by remember {mutableStateOf(false)}
    var alerts by remember {mutableStateOf(false)}
    var closeTarget by remember {mutableStateOf<TerminalCloseTarget?>(null)}
    val fontSize=model.fontSize
    var fontPicker by remember {mutableStateOf(false)}
    var terminalView by remember {mutableStateOf<TerminalView?>(null)}
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val blocks=window?.blocks ?: if(state.phase=="preview") listOf(BlockItem("preview","Terminal")) else emptyList()
    val chosen=displayedBlock.ifBlank {blocks.firstOrNull()?.id.orEmpty()}
    val liveBlock=state.selectedBlock.ifBlank {if(state.phase=="preview") "preview" else ""}
    fun closePaneTarget(block: BlockItem)=TerminalCloseTarget(state.selectedSession,block.id,
        (state.programs[block.id] ?: ProgramIdentity.Terminal).paneTitle(block.label,state.titles[block.id].orEmpty()),false,listOf(block.id))
    fun closeTabTarget(tab: WindowItem): TerminalCloseTarget {
        val pane=client.tabPaneIndex(tab);val block=tab.blocks.getOrNull(pane)
        val program=state.programs[block?.id] ?: ProgramIdentity.Terminal
        return TerminalCloseTarget(state.selectedSession,tab.id,program.tabTitle(tab.label,block?.label.orEmpty(),1,state.titles[block?.id].orEmpty()),true,tab.blocks.map {it.id})
    }
    LaunchedEffect(state.selectedSession) {closeTarget=null}
    LaunchedEffect(palette) { model.updateTerminalTheme(palette) }
    LaunchedEffect(state.phase) {if(state.connected || state.phase=="preview") showServer=false}
    LaunchedEffect(state.connected,state.selectedSession,state.selectedBlock,state.windows,navigation.pending) {
        client.rememberCurrentTerminal();client.openPreferredTerminal()
    }
    LaunchedEffect(state.connected,state.selectedSession,window?.id,state.selectedBlock) {
        val index=blocks.indexOfFirst {it.id==state.selectedBlock}
        if(index>=0 && blocks.size>1) client.prefetchPanes(listOf(blocks[Math.floorMod(index-1,blocks.size)].id,blocks[(index+1)%blocks.size].id).distinct())
    }
    LaunchedEffect(ready,chosen,client.hasScreen) {if(ready) client.claimActivePane()}
    LaunchedEffect(controlled,client.keyboardRequest,terminalView) {
        if(controlled) {
            terminalView?.requestFocus()
            client.applySize()
            if(client.keyboardRequest==displayedBlock && terminalView!=null) {terminalView?.keyboard();client.keyboardShown()}
        }
    }
    LaunchedEffect(picker) {if(picker=="sessions") model.refreshSessionDetails()}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            SessionHeader(sessionStrip,currentSessionKey,state.connected,model.sessionTargets.isNotEmpty(),
                blocks.map {state.programs[it.id] ?: ProgramIdentity.Terminal},blocks.indexOfFirst {it.id==chosen}.coerceAtLeast(0),state.directories[displayedBlock].orEmpty(),{picker="sessions"},model::selectSessionTarget,model::jumpLastSession,
                working=chosen in state.workingBlocks,title=(state.programs[chosen] ?: ProgramIdentity.Terminal).paneTitle(blocks.firstOrNull {it.id==chosen}?.label.orEmpty(),state.titles[chosen].orEmpty()),expanded=picker=="sessions",menu={
                    Box {
                        ChromeButton(ChromeIcon.MORE,"Terminal menu"){menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            Text(model.activeServer.label,Modifier.padding(horizontal=16.dp,vertical=6.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(state.connected) if(controlled) "In control" else "Viewing terminal" else state.phase,
                                Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            client.screenDimensions?.let {Text("${it.first} × ${it.second}",Modifier.padding(horizontal=16.dp),fontFamily=MonoUi,fontSize=11.sp)}
                            HorizontalDivider(Modifier.padding(vertical=6.dp))
                            OwnershipMenuItem(controlled,ready&&client.hasScreen) {menu=false;if(controlled) client.releaseControl() else client.takeControl()}
                            DropdownMenuItem(text={Text("Background alerts" + if(model.backgroundAlerts) " · On" else "")},onClick={menu=false;alerts=true})
                            DropdownMenuItem(text={Text("Appearance")},onClick={menu=false;appearance=true})
                            DropdownMenuItem(text={Text("Font size · $fontSize sp")},onClick={menu=false;fontPicker=true})
                            DropdownMenuItem(text={Text("Copy viewport")},enabled=client.hasScreen,onClick={
                                menu=false;client.terminal.value?.let {screen->(getClipboard(model.getApplication()) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rex terminal",screen.plainText()))}
                            })
                            DropdownMenuItem(text={Text("Paste")},enabled=controlled,onClick={
                                menu=false;(getClipboard(model.getApplication()) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(model.getApplication())?.toString()?.let(client::paste)
                            })
                            DropdownMenuItem(text={Text("Servers · ${model.connectedCount} connected")},onClick={menu=false;servers=true})
                            DropdownMenuItem(text={Text("Refresh sessions")},enabled=state.connected,onClick={menu=false;client.refresh()})
                        }
                    }
                })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if(showServer || (state.phase=="disconnected"&&!client.hasScreen)) {
                        Surface(Modifier.padding(12.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface) {
                            Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.app_name),fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text(stringResource(R.string.app_subtitle),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Connect over Tailscale",fontWeight=FontWeight.SemiBold)
                                OutlinedTextField(endpoint,{endpoint=it},Modifier.fillMaxWidth(),label={Text("Rex server URL")},singleLine=true,
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),shape=RoundedCornerShape(12.dp))
                                connectionError?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=11.sp)}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={connectionError=model.connect(endpoint)},enabled=state.phase!="connecting" && endpoint.isNotBlank()){Text("Connect")}
                                    TextButton(onClick={servers=true}) {Text("Servers")}
                                    if(state.connected || state.phase=="reconnecting") TextButton(onClick={model.disconnectServer(model.activeServerId)}){Text("Disconnect")}
                                    else TextButton(onClick={model.previewServer(model.activeServerId)}){Text("Try preview")}
                                    if(client.hasScreen) TextButton(onClick={showServer=false}){Text("Hide")}
                                }
                            }
                        }
                    }
                    if(blocks.isEmpty()) ConnectionNotice(state.phase,model.networkAvailable,client::retryNow)
                    if(state.error.isNotBlank() && state.phase !in listOf("connecting","reconnecting")) Text(state.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                        color=MaterialTheme.colorScheme.onErrorContainer,fontSize=11.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(blocks.isNotEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top=3.dp,bottom=7.dp)) {
                        LiveTerminal(client,blocks,chosen,liveBlock,state.programs,controlled,fontSize.toFloat(),
                            client::type,client::scroll,client::wheel,client::takeControl,client::scrollToBottom,client::resize,client::selectBlock,{picker="panes"},{terminalView=it},
                            Modifier.fillMaxSize())
                        ConnectionNotice(state.phase,model.networkAvailable,client::retryNow,Modifier.align(Alignment.TopCenter).padding(8.dp))
                        ReadyNotice(state.connectionEpoch,controlled,ready,Modifier.align(Alignment.TopCenter).padding(8.dp))
                        OtherOwnerNotice(chosen,state.otherOwner && !controlled,ready,client.screenDimensions,client::takeControl,Modifier.align(Alignment.TopEnd).padding(10.dp))
                        }
                    } else Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center) {
                        Text(if(state.connected) "Choose a session, or start a new one." else "Your terminals, anywhere.",
                            fontFamily=MonoUi,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(state.connected || state.phase=="preview") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).background(MaterialTheme.colorScheme.surface)) {
                            TerminalTabs(windows,window?.id.orEmpty(),state.connected&&!state.creating&&!changingSession,client::selectWindow,panePrograms=client::tabPrograms,selectedPane=client::tabPaneIndex,paneTitle={w,pane->state.titles[w.blocks.getOrNull(pane)?.id].orEmpty()},paneWorking={w->w.blocks.any {it.id in state.workingBlocks}},onClose={closeTarget=closeTabTarget(it)},animateWorking=!model.activityVisible,actions={
                                TerminalActions(ready,model.connectedCount>0,blocks.isNotEmpty(),state.selectedBlock.isNotBlank(),
                                    {model.showActivity(true)},{picker="panes"},client::createPane,client::createTab,attentionCount=model.attentionCount)
                            })
                            KeyboardBar(controlled,ready,client.controlModifier,client.altModifier,client.shiftModifier,keyboardVisible,
                                client::toggleControl,client::toggleAlt,client::toggleShift,client::pressShift,client::releaseShift,{client.keyForTarget(it,keyTarget)},readable=state.connected,onControlShortcut=client::controlShortcut,inputKey=keyTarget) {
                                if(!controlled) client.takeControl() else if(keyboardVisible) terminalView?.hideKeyboard() else terminalView?.keyboard()
                            }
                        }
                    }
                }
                // Loading feedback must not resize the terminal grid during navigation.
                if(state.creating || navigation.pending || state.phase=="connecting" || state.phase=="reconnecting")
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                if(picker=="sessions") SessionSwitcher(model.sessionTargets,currentSessionKey,model.lastSessionKey,state.connected&&!state.creating,model.closingSessionKey.ifBlank {model.renamingSessionKey},model.sessionActionError,model::closeSession,model::renameSession,
                    onSelect={picker="";model.selectSessionTarget(it)},onClose={picker=""},onNew={picker="";newSession=true})
            }
        }
    }
    if(model.activityVisible) ActivitySheet(model.activitySources,model::refreshActivity,model::openActivity,onClose={model.showActivity(false)},onSeen=model::markActivitySeen,onMarkAll=model::markAllActivitySeen)
    if(alerts) BackgroundAlertsDialog(model) {alerts=false}
    if(servers) ServersSheet(model){servers=false}
    if(picker=="panes") ModalBottomSheet(onDismissRequest={picker=""}) {
        Column(Modifier.fillMaxWidth().heightIn(max=450.dp).verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
            Text("Panes",Modifier.padding(20.dp),fontWeight=FontWeight.Bold,fontSize=18.sp)
            blocks.forEachIndexed {index,b->
                val program=state.programs[b.id] ?: ProgramIdentity.Terminal
                val label=program.paneTitle(b.label,state.titles[b.id].orEmpty())
                Row(Modifier.fillMaxWidth().clickable{picker="";client.selectBlock(b.id)}
                    .padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ProgramStack(listOf(program),0,framed=true)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(label,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontWeight=if(b.id==chosen) FontWeight.Bold else FontWeight.Medium,
                            color=if(b.id==chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(state.directories[b.id].orEmpty().ifBlank {program.label},fontSize=11.sp,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text(if(b.id==chosen) "✓" else "${index+1}",color=if(b.id==chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    ChromeButton(ChromeIcon.CLOSE,"Close pane $label",ready) {picker="";closeTarget=closePaneTarget(b)}
                }
            }
            TextButton(onClick={picker="";client.createPane()},enabled=ready){Text("New pane")}
        }
    }
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text(if(target.tab) "Close tab?" else "Close pane?")},
            text={Text(if(target.tab) "Close “${target.label}”? This ends every program in its ${target.blocks.size} pane${if(target.blocks.size==1) "" else "s"}."
                else "Close “${target.label}”? This ends the program running in this pane.")},
            confirmButton={TextButton(enabled=ready,onClick={closeTarget=null
                if(target.tab) client.closeTab(target.session,target.id,target.blocks) else client.closePane(target.session,target.id)
            }) {Text("Close",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={closeTarget=null}) {Text("Cancel")}})
    }
    if(appearance) AppearanceSheet(model.appearance,model.themes,model.theme(false),model.theme(true),model::changeAppearance,model::chooseTheme) {appearance=false}
    if(fontPicker) FontSizeDialog(fontSize,model::changeFontSize) {fontPicker=false}
    if(newSession) AlertDialog(onDismissRequest={newSession=false},title={Text("New session")},text={
        OutlinedTextField(newName,{newName=it},singleLine=true,label={Text("Name (optional)")},supportingText={Text("Leave blank for a name from Rex.")})
    },confirmButton={Button(onClick={newSession=false;client.createSession(newName);newName=""},enabled=state.connected&&!state.creating){Text("Create")}},
        dismissButton={TextButton(onClick={newSession=false}){Text("Cancel")}})
}
private fun getClipboard(context: Context)=context.getSystemService(Context.CLIPBOARD_SERVICE)

@Composable private fun SessionSwitcher(targets: List<SessionTarget>,current: String,last: String,canCreate: Boolean,closing: String,error: String,onCloseSession: (SessionTarget)->Unit,onRenameSession: (SessionTarget,String)->Boolean,onSelect: (String)->Unit,onClose: ()->Unit,onNew: ()->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    var closeTarget by remember {mutableStateOf<SessionTarget?>(null)}
    var renameTarget by remember {mutableStateOf<SessionTarget?>(null)}
    BackHandler(onBack=onClose)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.25f)).clickable(onClick=onClose))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.78f)
            .clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal=15.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top=9.dp,bottom=13.dp).size(25.dp,4.dp)
                .background(MaterialTheme.colorScheme.outline,RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Sessions",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold)
                Button(onClick=onNew,enabled=canCreate,contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)) {
                    ChromeSymbol(ChromeIcon.ADD,Modifier.size(15.dp),MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(5.dp));Text("New")
                }
            }
            TextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("Jump to session…",fontSize=12.sp)},singleLine=true,
                leadingIcon={ChromeSymbol(ChromeIcon.SEARCH,color=MaterialTheme.colorScheme.onSurfaceVariant)},shape=RoundedCornerShape(12.dp),
                colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            Spacer(Modifier.height(9.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val groups=SessionCatalog.groups(targets,current,search)
                if(groups.isEmpty()) Text("No matching sessions",Modifier.padding(15.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEach {group->key(group.server.id) {
                    Row(Modifier.fillMaxWidth().padding(start=11.dp,end=11.dp,top=13.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(group.server.label,Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(group.sessions.size.toString(),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    group.sessions.forEach {target->key(target.key) {
                        SessionRow(target,current==target.key,last==target.key,closing==target.key,
                            onSelect={onSelect(target.key)},onClose={closeTarget=target},onRename={renameTarget=target})
                    }}
                }}
            }
            if(error.isNotBlank()) Text(error,Modifier.padding(vertical=10.dp),color=MaterialTheme.colorScheme.error,fontSize=11.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
    renameTarget?.let {target->
        SessionRenameDialog(target,closing.isBlank(),onSave={label->if(onRenameSession(target,label)) renameTarget=null},onDismiss={renameTarget=null})
    }
    closeTarget?.let {target->
        AlertDialog(onDismissRequest={closeTarget=null},title={Text("Close session?")},
            text={Text("Close “${target.session.label}” on ${target.server.label}? All its tabs and panes will close, ending the programs running in them.")},
            confirmButton={TextButton(onClick={closeTarget=null;onCloseSession(target)},enabled=closing.isBlank()) {
                Text("Close session",color=MaterialTheme.colorScheme.error)
            }},dismissButton={TextButton(onClick={closeTarget=null}){Text("Cancel")}})
    }

}

@Composable private fun LiveTerminal(client: RexConnection,blocks: List<BlockItem>,chosen: String,liveBlock: String,
    programs: Map<String,ProgramIdentity>,controlled: Boolean,fontSize: Float,
    onInput: (String)->Unit,onScroll: (Int)->Unit,onWheel: (Int,Int,Int,Int,Int)->Unit,onActivate: ()->Unit,onBottom: ()->Unit,onDimensions: (Int,Int)->Unit,
    onSelect: (String)->Unit,onPanes: ()->Unit,onView: (TerminalView)->Unit,modifier: Modifier=Modifier) {
    val layout by client.displayLayout.collectAsState()
    val screen=client.screenForLayout(layout)
    TerminalCarousel(blocks,chosen,liveBlock,screen,client.paneFrames,programs,controlled,fontSize,
        onInput,onScroll,onWheel,onActivate,onBottom,onDimensions,onSelect,onPanes,onView,modifier,client::bindTerminalView,onMouseClick=client::click,mouseTarget=client::keyTarget)
}

@Composable private fun BackgroundAlertsDialog(model: RexModel,onClose: ()->Unit) {
    val context=LocalContext.current
    var denied by remember {mutableStateOf(false)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted) {model.setBackgroundAlerts(true);denied=false} else denied=true
    }
    AlertDialog(onDismissRequest=onClose,title={Text("Background alerts")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Get notified when a task needs input, approval, sign-in, or fails while Rexlink is in the background or your phone is locked.")
            Text("A quiet monitoring notification stays visible. Stop it there, or turn this off. Closing Rexlink stops monitoring.")
            Text("Your phone may delay alerts while sleeping.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Notify when I’m needed",Modifier.weight(1f))
                Switch(checked=model.backgroundAlerts,onCheckedChange={enabled->
                    if(!enabled) model.setBackgroundAlerts(false)
                    else if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if(BackgroundAlerts.allowed(context)) {model.setBackgroundAlerts(true);denied=false} else denied=true
                },enabled=model.servers.any {model.serverStatuses[it.id]?.maintained==true})
            }
            if(model.servers.none {model.serverStatuses[it.id]?.maintained==true}) Text("Connect a server to enable alerts.")
            if(denied || (model.backgroundAlerts && !BackgroundAlerts.allowed(context))) {
                Text("Allow notifications in Android settings to receive alerts.")
                TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("Notification settings")}
            }
        }
    },confirmButton={TextButton(onClick=onClose) {Text("Done")}})
}
