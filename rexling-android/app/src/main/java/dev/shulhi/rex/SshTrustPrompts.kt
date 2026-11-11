package dev.shulhi.rex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun SshTrustPrompts(model: RexModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var challenge by remember {mutableStateOf<SshHostChallenge?>(null)}
    var dismissed by remember {mutableStateOf(setOf<Long>())}
    var error by remember {mutableStateOf("")}
    var saving by remember {mutableStateOf(false)}
    LaunchedEffect(Unit) {
        while(true) {
            if(!saving) challenge=withContext(Dispatchers.IO) {SshTransport.challenge(dismissed)}
            delay(500)
        }
    }
    val current=challenge ?: return
    fun dismiss() {
        dismissed=dismissed+current.transport;challenge=null
        model.disconnectServer(current.profile.id)
        // A later explicit reconnect must receive a fresh challenge after cancellation.
        SshTransport.forget(current.profile.id)
    }
    AlertDialog(onDismissRequest={if(!saving) dismiss()},title={Text(if(current.changed) "SSH host key changed" else "Trust this SSH host?")},
        text={Column {
            Text(if(current.hop=="jump") "Jump host · "+current.profile.label else "Target host · "+current.profile.label)
            Text(current.endpoint)
            Text(current.algorithm,fontSize=12.sp)
            SelectionContainer {Text(current.fingerprint,fontFamily=MonoUi,fontSize=12.sp)}
            Text(if(current.changed) "Connection blocked. Verify this key on the host. To replace trust, edit the server and select Reset saved host trust."
                else "Compare this SHA256 fingerprint with the server's SSH host key before trusting it.",fontSize=12.sp)
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        }},
        confirmButton={
            if(!current.changed) TextButton(enabled=!saving,onClick={
                scope.launch {
                    saving=true
                    try {
                        withContext(Dispatchers.IO) {SshTransport.trust(context,current)}
                        challenge=null;error="";model.retrySsh(current.profile.id)
                    } catch(e: Exception) {error=e.message ?: "Could not save host trust"}
                    finally {saving=false}
                }
            }) {Text("Trust & connect")}
            else TextButton(onClick={dismiss()}) {Text("Close")}
        },
        dismissButton={if(!current.changed) TextButton(enabled=!saving,onClick={dismiss()}) {Text("Cancel")}})
}
