package dev.shulhi.rex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.Choreographer
import kotlin.coroutines.resume

/** State is decoder-local: row deltas never cross a connection or an unknown base. */
internal class TerminalUpdates {
    var revision: Long=0;private set
    private var state=AppState()
    fun decode(bytes: ByteArray): AppState {
        require(bytes.size in 17..134217728) {"Invalid terminal packet size"}
        val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.int==0x31545852) {"Unsupported terminal packet"}
        val nextRevision=data.long;require(nextRevision>revision)
        fun text(): String {val n=data.int;require(n>=0 && n<=data.remaining());val raw=ByteArray(n);data.get(raw);return raw.toString(Charsets.UTF_8)}
        val metadata=text()
        var next=if(metadata.isNotEmpty()) AppState.parse(metadata) else state.copy(previewFrames=emptyMap())
        val frame=when(val flag=data.get().toInt()) {
            0->state.screen
            1->null
            2,3->{
                val columns=data.short.toInt() and 0xffff;val rows=data.short.toInt() and 0xffff
                require(columns>0 && rows>0 && columns.toLong()*rows<=2_000_000)
                val x=data.int;val y=data.int;val fg=data.int;val bg=data.int
                val scroll=TerminalScroll(data.long,data.long,data.long)
                val previous=state.screen
                if(flag==2) require(previous!=null && previous.columns==columns && previous.rows==rows) {"Missing terminal delta base"}
                val lines=if(flag==2) previous!!.lines.toMutableList() else MutableList<List<TerminalCell>>(rows){emptyList()}
                val changed=data.short.toInt() and 0xffff;require(changed<=rows)
                val seen=HashSet<Int>()
                repeat(changed) {
                    val row=data.short.toInt() and 0xffff;require(row<rows && seen.add(row))
                    val count=data.short.toInt() and 0xffff;require(count<=columns)
                    lines[row]=List(count) {
                        val col=data.short.toInt() and 0xffff;val width=data.get().toInt() and 0xff
                        val attrs=data.int;val foreground=data.int;val background=data.int
                        require(col<columns && width<=2)
                        TerminalCell(col,row,text(),foreground,background,attrs,width)
                    }
                }
                if(flag==3) require(changed==rows)
                TerminalScreen(columns,rows,x.takeIf {it>=0},y.takeIf {it>=0},fg,bg,lines.flatten(),scroll,lines)
            }
            else->error("Invalid terminal frame flag $flag")
        }
        require(!data.hasRemaining()) {"Trailing terminal packet data"}
        next=next.copy(revision=nextRevision,screen=frame)
        state=next;revision=nextRevision
        return next
    }
}
internal suspend fun awaitDisplayFrame()=suspendCancellableCoroutine<Unit> {continuation->
    val clock=Choreographer.getInstance()
    val callback=Choreographer.FrameCallback {if(continuation.isActive) continuation.resume(Unit)}
    clock.postFrameCallback(callback)
    continuation.invokeOnCancellation {clock.removeFrameCallback(callback)}
}
