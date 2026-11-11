package dev.shulhi.rex

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.Executors

/** Opt-in bounded app-private logs. Callers record metadata, never text or credentials. */
internal object DevelopLog {
    @Volatile var enabled=false
        private set
    @Volatile private var writeFailed=false
    private var directory: File?=null
    private val writer=Executors.newSingleThreadExecutor {Thread(it,"rex-develop-log").apply {isDaemon=true}}
    fun configure(context: Context,value: Boolean) {
        directory=File(context.applicationContext.filesDir,"diagnostics").apply {mkdirs()}
        enabled=value
        NativeBridge.setDevelop(value,File(directory,"native.log").absolutePath)
        event("develop","enabled=$value build=${BuildConfig.VERSION_CODE} Android=${Build.VERSION.SDK_INT}")
    }
    fun event(category: String,message: String) {
        if(!enabled) return
        val line="${System.currentTimeMillis()} android $category ${message.take(1200)}\n"
        writer.execute {
            runCatching {
                val dir=directory ?: return@runCatching
                val file=File(dir,"develop.log")
                if(file.length()>2*1024*1024) {val previous=File(dir,"develop.previous.log");previous.delete();file.renameTo(previous)}
                file.appendText(line)
            }.onFailure {writeFailed=true}
        }
    }
    fun d(tag: String,message: String): Int {event(tag,message);return if(enabled) android.util.Log.d(tag,message) else 0}
    fun w(tag: String,message: String): Int {event(tag,message);return if(enabled) android.util.Log.w(tag,message) else 0}
    fun clipboardText(): String {
        val logs=snapshot()
        return "Rexling SSH build ${BuildConfig.VERSION_CODE} (recent logs; export a file for the complete log)\n\nAndroid\n"+
            logs.substringBefore("\nNative diagnostics\n").takeLast(110_000)+
            "\nNative\n"+logs.substringAfter("\nNative diagnostics\n","").takeLast(110_000)
    }
    /** Runs off the UI thread; waits for earlier writes before exporting. */
    fun snapshot(): String=writer.submit<String> {
        val dir=directory
        buildString {
            append("Rexling SSH diagnostics\nBuild ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME})\n")
            append("Android ${Build.VERSION.SDK_INT}; model ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Enabled: $enabled; writeFailure=$writeFailed; timestamps: Unix milliseconds\nInput contents and credentials are not recorded.\n\n")
            if(dir!=null) for(name in listOf("develop.previous.log","develop.log")) {
                val file=File(dir,name);if(file.exists()) append(file.readText()).append('\n')
            }
            append("\nNative diagnostics\n")
            if(dir!=null && File(dir,"native.log").exists()) {
                for(name in listOf("native.previous.log","native.log")) {
                    val file=File(dir,name);if(file.exists()) append(file.readText()).append('\n')
                }
            } else append(NativeBridge.readDevelopLog())
        }
    }.get()
}
