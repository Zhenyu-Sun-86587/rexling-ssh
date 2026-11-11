package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One independent authentication form per hop; all secrets share the encrypted profile envelope. */
@Stable internal class SshAuthState(initialMethod: String="key") {
    var method by mutableStateOf(initialMethod)
    var privateKey by mutableStateOf("")
    var publicKey by mutableStateOf("")
    var passphrase by mutableStateOf("")
    var password by mutableStateOf("")
    var certificate by mutableStateOf("")
    fun restore(value: JSONObject?) {
        if(value==null) return
        method=value.optString("method",method);privateKey=value.optString("privateKey")
        passphrase=value.optString("passphrase");password=value.optString("password");certificate=value.optString("certificate")
    }
    fun validate(label: String) {
        require(if(method=="password") password.isNotEmpty() else privateKey.isNotBlank()) {"$label: enter a key or password"}
    }
    fun json()=JSONObject().put("method",method).put("privateKey",if(method=="key") privateKey else "")
        .put("passphrase",if(method=="key") passphrase else "").put("certificate",if(method=="key") certificate else "")
        .put("password",if(method=="password") password else "")
    fun clear() {privateKey="";publicKey="";passphrase="";password="";certificate=""}
}

@Composable internal fun SshAuthentication(
    auth: SshAuthState,busy: Boolean,onBusy: (Boolean)->Unit,onError: (String?)->Unit,advanced: Boolean=false,
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    fun importFile(uri: Uri?,apply: (String)->Unit) {
        if(uri==null) return
        scope.launch {
            onBusy(true)
            try {
                val text=withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {input->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(4096)
                        while(true) {
                            val n=input.read(buffer);if(n<0) break
                            require(output.size()+n<=131072) {"Choose a key or certificate smaller than 128 KiB"}
                            output.write(buffer,0,n)
                        }
                        val bytes=output.toByteArray()
                        try {bytes.toString(Charsets.UTF_8)} finally {bytes.fill(0);buffer.fill(0)}
                    } ?: error("Could not read selected file")
                }
                apply(text);onError(null)
            } catch(e: Exception) {onError(e.message)}
            finally {onBusy(false)}
        }
    }
    val keyPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->importFile(uri) {auth.privateKey=it;auth.publicKey=""}}
    val certPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->importFile(uri) {auth.certificate=it}}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        SshChoiceRow(listOf("Private key","Password"),if(auth.method=="key") 0 else 1,!busy) {auth.method=if(it==0) "key" else "password"}
        if(auth.method=="key") {
            SshField(auth.privateKey,{auth.privateKey=it;auth.publicKey=""},Modifier.fillMaxWidth(),
                title="Private key",singleLine=false,minLines=2,maxLines=4,enabled=!busy)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={keyPicker.launch(arrayOf("*/*"))},enabled=!busy) {Text("Import key",maxLines=1,softWrap=false)}
                TextButton(enabled=!busy,onClick={
                    scope.launch {
                        onBusy(true)
                        try {
                            val generated=withContext(Dispatchers.IO) {
                                val bytes=NativeBridge.sshGenerate()
                                try {JSONObject(bytes.toString(Charsets.UTF_8))} finally {bytes.fill(0)}
                            }
                            auth.privateKey=generated.getString("privateKey");auth.publicKey=generated.getString("publicKey")
                            auth.passphrase="";auth.certificate="";onError(null)
                        } catch(e: Exception) {onError(e.message)}
                        finally {onBusy(false)}
                    }
                }) {Text("Generate",maxLines=1,softWrap=false)}
            }
            if(auth.publicKey.isNotBlank()) {
                Text(auth.publicKey,fontFamily=MonoUi,fontSize=10.sp)
                TextButton(onClick={
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SSH public key",auth.publicKey))
                }) {Text("Copy public key")}
                Text("Authorize this key for this hop's user, or import its CA-signed user certificate below.",fontSize=11.sp)
            }
            if(advanced) {
            SshField(auth.passphrase,{auth.passphrase=it},Modifier.fillMaxWidth(),title="Passphrase (optional)",
                singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy)
            SshField(auth.certificate,{auth.certificate=it},Modifier.fillMaxWidth(),title="Certificate (optional)",
                singleLine=false,minLines=1,maxLines=3,enabled=!busy)
            TextButton(onClick={certPicker.launch(arrayOf("*/*"))},enabled=!busy) {Text("Import certificate")}
            }
        } else {
            SshField(auth.password,{auth.password=it},Modifier.fillMaxWidth(),title="Password",singleLine=true,
                visualTransformation=PasswordVisualTransformation(),enabled=!busy)
        }
    }
}
