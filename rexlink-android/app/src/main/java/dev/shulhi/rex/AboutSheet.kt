package dev.shulhi.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class AppBuildInfo(val version: String,val number: Int,val type: String,val revision: String,val modified: Boolean) {
    val commit: String get()=revision+if(modified) " (local changes)" else ""
    val description: String get()="Rexlink $version (build $number)\n$type · $commit"
    companion object {
        val current get()=AppBuildInfo(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,BuildConfig.BUILD_TYPE,BuildConfig.GIT_REVISION,BuildConfig.LOCAL_CHANGES)
        const val repository="https://github.com/shulhi/rexlink"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AboutSheet(onClose: ()->Unit) {
    val context=LocalContext.current
    val uri=LocalUriHandler.current
    ModalBottomSheet(onDismissRequest=onClose,containerColor=MaterialTheme.colorScheme.surface) {
        AboutContent(AppBuildInfo.current,onClose,onCopy={text->
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Rexlink build",text))
        },onOpen={url->
            runCatching {uri.openUri(url)}.onFailure {Toast.makeText(context,"No browser available",Toast.LENGTH_SHORT).show()}
        })
    }
}

@Composable internal fun AboutContent(info: AppBuildInfo,onClose: ()->Unit,onCopy: (String)->Unit,onOpen: (String)->Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max=620.dp).verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("About",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold)
            ChromeButton(ChromeIcon.CLOSE,"Close about",onClick=onClose)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.rexlink_dinosaur),contentDescription=null,
                modifier=Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(4.dp))
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name),fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text(stringResource(R.string.app_subtitle),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            BuildInfoRow("Version",info.version)
            BuildInfoRow("Build","${info.number} · ${info.type}")
            BuildInfoRow("Commit",info.commit)
        }
        OutlinedButton(onClick={onCopy(info.description)}) {ChromeSymbol(ChromeIcon.COPY,Modifier.size(16.dp));Spacer(Modifier.width(8.dp));Text("Copy build info")}
        Column {
            TextButton(onClick={onOpen(AppBuildInfo.repository)}) {Text("Source code on GitHub")}
            TextButton(onClick={onOpen("${AppBuildInfo.repository}/blob/main/LICENSE")}) {Text("Apache License 2.0")}
            TextButton(onClick={onOpen("${AppBuildInfo.repository}/blob/main/THIRD_PARTY_NOTICES.md")}) {Text("Open-source notices")}
        }
        Text("Built with Kotlin, Jetpack Compose, Rust and libghostty-vt.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Provided on a best-effort basis, without warranty.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BuildInfoRow(label: String,value: String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.width(64.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,fontSize=13.sp,fontWeight=FontWeight.Medium)
    }
}
