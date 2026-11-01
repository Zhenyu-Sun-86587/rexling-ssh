package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val dark=when(model.appearance) {Appearance.SYSTEM->isSystemInDarkTheme();Appearance.DARK->true;Appearance.LIGHT->false}
            SideEffect {WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            RexTheme(dark) {RexApp(model)}
        }
    }
    override fun onStart(){super.onStart();model.resume()}
    override fun onStop(){model.pause();super.onStop()}
}

@Composable private fun RexTheme(dark: Boolean,content: @Composable ()->Unit) {
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
    CompositionLocalProvider(LocalTerminalPalette provides if(dark) TerminalPalette.Dark else TerminalPalette.Light) {
        MaterialTheme(colorScheme=scheme,content=content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexApp(model: RexModel) {
    val client=model.activeClient
    key(model.activeServerId) {RexWorkspace(model,client)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RexWorkspace(model: RexModel,client: RexConnection) {
    val state by client.state.collectAsState()
    val navigation=client.navigationRequest
    val displayedSession=navigation.session ?: state.selectedSession
    val displayedBlock=navigation.block ?: state.selectedBlock
    val changingSession=navigation.session!=null && navigation.session!=state.selectedSession
    val windows=if(changingSession) emptyList() else state.windows
    val window=windows.firstOrNull {w->w.blocks.any {it.id==displayedBlock}} ?: windows.firstOrNull {it.blocks.isNotEmpty()}
    val ready=state.connected && !navigation.pending && !state.creating
    val controlled=state.controlled && ready && state.screen!=null
    val palette=LocalTerminalPalette.current
    var endpoint by rememberSaveable {mutableStateOf(client.savedEndpoint)}
    var showServer by rememberSaveable {mutableStateOf(true)}
    var servers by remember {mutableStateOf(false)}
    var connectionError by remember {mutableStateOf<String?>(null)}
    var picker by remember {mutableStateOf("")}
    var newSession by remember {mutableStateOf(false)}
    var newName by rememberSaveable {mutableStateOf("")}
    var menu by remember {mutableStateOf(false)}
    var add by remember {mutableStateOf(false)}
    var appearance by remember {mutableStateOf(false)}
    var fontSize by rememberSaveable {mutableIntStateOf(10)}
    var terminalView by remember {mutableStateOf<TerminalView?>(null)}
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val blocks=window?.blocks ?: if(state.phase=="preview") listOf(BlockItem("preview","Terminal")) else emptyList()
    val chosen=displayedBlock.ifBlank {blocks.firstOrNull()?.id.orEmpty()}
    val liveBlock=state.selectedBlock.ifBlank {if(state.phase=="preview") "preview" else ""}
    LaunchedEffect(palette) { model.updateTerminalTheme(palette) }
    LaunchedEffect(state.phase) {if(state.connected || state.phase=="preview") showServer=false}
    LaunchedEffect(state.connected,state.selectedSession,state.selectedBlock,state.windows,navigation.pending) {
        client.rememberCurrentTerminal();client.openPreferredTerminal()
    }
    LaunchedEffect(state.connected,state.selectedSession,window?.id,state.selectedBlock) {
        val index=blocks.indexOfFirst {it.id==state.selectedBlock}
        if(index>=0) client.prefetchPanes(listOfNotNull(blocks.getOrNull(index-1)?.id,blocks.getOrNull(index+1)?.id))
    }
    LaunchedEffect(controlled) {if(controlled){client.applySize();terminalView?.keyboard()}}
    LaunchedEffect(picker) {if(picker=="sessions") client.refreshSessionDetails()}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            SessionHeader(state.sessions,displayedSession,state.connected,state.connected&&!state.creating,
                controlled,ready&&state.screen!=null,blocks.map {state.programs[it.id] ?: ProgramIdentity.Terminal},blocks.indexOfFirst {it.id==chosen}.coerceAtLeast(0),state.directories[displayedBlock].orEmpty(),{picker="sessions"},client::selectSession,client::jumpLastSession,
                {if(controlled) client.releaseControl() else client.takeControl()},expanded=picker=="sessions",serverName=model.activeServer.label,menu={
                    Box {
                        ChromeButton(ChromeIcon.MORE,"Terminal menu"){menu=true}
                        DropdownMenu(menu,{menu=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                            Text(model.activeServer.label,Modifier.padding(horizontal=16.dp,vertical=6.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(state.connected) if(controlled) "In control" else "Viewing terminal" else state.phase,
                                Modifier.padding(horizontal=16.dp,vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            state.screen?.let {Text("${it.columns} × ${it.rows}",Modifier.padding(horizontal=16.dp),fontFamily=MonoUi,fontSize=11.sp)}
                            HorizontalDivider(Modifier.padding(vertical=6.dp))
                            DropdownMenuItem(text={Text("Appearance")},onClick={menu=false;appearance=true})
                            DropdownMenuItem(text={Text("Font size · $fontSize sp")},onClick={fontSize=when(fontSize){8->10;10->12;12->14;14->16;16->18;else->8}})
                            DropdownMenuItem(text={Text("Copy viewport")},enabled=state.screen!=null,onClick={
                                menu=false;state.screen?.let {screen->(getClipboard(model.getApplication()) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rex terminal",screen.plainText()))}
                            })
                            DropdownMenuItem(text={Text("Paste")},enabled=controlled,onClick={
                                menu=false;(getClipboard(model.getApplication()) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(model.getApplication())?.toString()?.let(client::paste)
                            })
                            DropdownMenuItem(text={Text("Servers · ${model.connectedCount} connected")},onClick={menu=false;servers=true})
                            DropdownMenuItem(text={Text("Refresh sessions")},enabled=state.connected,onClick={menu=false;client.refresh()})
                            DropdownMenuItem(text={Text("Switch pane")},enabled=blocks.isNotEmpty(),onClick={menu=false;picker="panes"})
                        }
                    }
                })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if(showServer || (state.phase=="disconnected"&&state.screen==null)) {
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
                                    if(state.screen!=null) TextButton(onClick={showServer=false}){Text("Hide")}
                                }
                            }
                        }
                    }
                    if(state.creating || navigation.pending || state.phase=="connecting" || state.phase=="reconnecting")
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    if(state.error.isNotBlank()) Text(state.error,Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
                        color=MaterialTheme.colorScheme.onErrorContainer,fontSize=11.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(blocks.isNotEmpty()) {
                        TerminalCarousel(blocks,chosen,liveBlock,state.screen,client.paneFrames,state.programs,controlled,fontSize.toFloat(),
                            client::type,client::scroll,client::scrollToBottom,client::resize,client::selectBlock,{picker="panes"},{terminalView=it},
                            Modifier.weight(1f).fillMaxWidth().padding(top=3.dp,bottom=7.dp))
                    } else Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center) {
                        Text(if(state.connected) "Choose a session, or start a new one." else "Your terminals, anywhere.",
                            fontFamily=MonoUi,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(state.connected || state.phase=="preview") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).background(MaterialTheme.colorScheme.surface)) {
                            TerminalTabs(windows,window?.id.orEmpty(),state.connected&&!state.creating&&!changingSession,client::selectWindow,{picker="panes"},panePrograms=client::tabPrograms,selectedPane=client::tabPaneIndex,add={
                                Box {
                                    Box(Modifier.size(41.dp).clip(RoundedCornerShape(23.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
                                        ChromeButton(ChromeIcon.ADD,"Add terminal",ready){add=true}
                                    }
                                    DropdownMenu(add,{add=false},containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                                        DropdownMenuItem(text={Text("New tab")},onClick={add=false;client.createTab()})
                                        DropdownMenuItem(text={Text("New pane")},enabled=state.selectedBlock.isNotBlank(),onClick={add=false;client.createPane()})
                                    }
                                }
                            })
                            KeyboardBar(controlled,ready,client.controlModifier,client.altModifier,keyboardVisible,client::toggleControl,client::toggleAlt,client::key) {
                                if(!controlled) client.takeControl() else if(keyboardVisible) terminalView?.hideKeyboard() else terminalView?.keyboard()
                            }
                        }
                    }
                }
                if(picker=="sessions") SessionSwitcher(state,displayedSession,client.lastSessionId,
                    onSelect={picker="";client.selectSession(it)},onClose={picker=""},onNew={picker="";newSession=true})
            }
        }
    }
    if(servers) ServersSheet(model){servers=false}
    if(picker=="panes") ModalBottomSheet(onDismissRequest={picker=""}) {
        Column(Modifier.fillMaxWidth().heightIn(max=450.dp).verticalScroll(rememberScrollState()).padding(bottom=20.dp)) {
            Text("Panes",Modifier.padding(20.dp),fontWeight=FontWeight.Bold,fontSize=18.sp)
            blocks.forEachIndexed {index,b->
                Text("${index+1}  ${b.label}"+(if(b.id==chosen) "  ✓" else ""),Modifier.fillMaxWidth().clickable{picker="";client.selectBlock(b.id)}.padding(20.dp),
                    color=if(b.id==chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick={picker="";client.createPane()},enabled=ready){Text("New pane")}
        }
    }
    if(appearance) AlertDialog(onDismissRequest={appearance=false},title={Text("Appearance")},text={
        Column {Appearance.entries.forEach {mode->
            Row(Modifier.fillMaxWidth().clickable{model.changeAppearance(mode);appearance=false}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(model.appearance==mode,onClick={model.changeAppearance(mode);appearance=false})
                Text(when(mode){Appearance.SYSTEM->"System";Appearance.DARK->"Dark";Appearance.LIGHT->"Light"})
            }
        }}
    },confirmButton={TextButton(onClick={appearance=false}){Text("Done")}})
    if(newSession) AlertDialog(onDismissRequest={newSession=false},title={Text("New session")},text={
        OutlinedTextField(newName,{newName=it},singleLine=true,label={Text("Name (optional)")},supportingText={Text("Leave blank for a name from Rex.")})
    },confirmButton={Button(onClick={newSession=false;client.createSession(newName);newName=""},enabled=state.connected&&!state.creating){Text("Create")}},
        dismissButton={TextButton(onClick={newSession=false}){Text("Cancel")}})
}
private fun getClipboard(context: Context)=context.getSystemService(Context.CLIPBOARD_SERVICE)

@Composable private fun SessionSwitcher(state: AppState,current: String,last: String,onSelect: (String)->Unit,onClose: ()->Unit,onNew: ()->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    BackHandler(onBack=onClose)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.25f)).clickable(onClick=onClose))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.78f)
            .clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal=15.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top=9.dp,bottom=13.dp).size(25.dp,4.dp)
                .background(MaterialTheme.colorScheme.outline,RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Sessions",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold)
                Button(onClick=onNew,enabled=state.connected&&!state.creating,contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)) {
                    ChromeSymbol(ChromeIcon.ADD,Modifier.size(15.dp),MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(5.dp));Text("New")
                }
            }
            TextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("Jump to session…",fontSize=12.sp)},singleLine=true,
                leadingIcon={ChromeSymbol(ChromeIcon.SEARCH,color=MaterialTheme.colorScheme.onSurfaceVariant)},shape=RoundedCornerShape(12.dp),
                colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            Spacer(Modifier.height(9.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                val ordered=state.sessions.sortedBy {if(it.id==current) 0 else 1}.filter{it.label.contains(search,ignoreCase=true)}
                if(ordered.isEmpty()) Text("No matching sessions",Modifier.padding(15.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                ordered.forEach {session->
                    val active=session.id==current
                    val detail=if(session.id==state.selectedSession && state.connected)
                        SessionDetail("this device",state.windows.size,state.windows.sumOf {it.blocks.size}) else state.sessionDetails[session.id]
                    Row(Modifier.fillMaxWidth().padding(vertical=3.dp).clip(RoundedCornerShape(14.dp))
                        .background(if(active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable(enabled=state.connected&&!state.creating){onSelect(session.id)}.padding(horizontal=11.dp,vertical=14.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                        Box(Modifier.size(6.dp).background(if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(4.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(session.label,fontFamily=MonoUi,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(detail?.let{"${it.attachment} · ${it.tabs} tabs · ${it.panes} panes"} ?: "Details not loaded",fontSize=10.sp,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
                        }
                        if(active) Text("current",color=MaterialTheme.colorScheme.primary,fontSize=9.sp,fontWeight=FontWeight.SemiBold)
                        else if(session.id==last) Text("↶ last",Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),fontSize=9.sp)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical=10.dp))
            Text("← →    swipe the session name to cycle",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("hold    long-press the name to jump to last",Modifier.padding(top=6.dp,bottom=20.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
