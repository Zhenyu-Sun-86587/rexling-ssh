package dev.shulhi.rex

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TerminalUpdatesTest {
    private fun packet(revision: Long,flag: Int,changed: Map<Int,String>,metadata: String=""): ByteArray {
        val out=ByteArrayOutputStream()
        fun number(value: Long,size: Int) {repeat(size){out.write((value ushr (8*it)).toInt() and 255)}}
        fun text(value: String) {val bytes=value.toByteArray();number(bytes.size.toLong(),4);out.write(bytes)}
        out.write("RXT1".toByteArray());number(revision,8);text(metadata);number(flag.toLong(),1)
        if(flag>=2) {
            number(2,2);number(2,2);number(-1,4);number(-1,4);number(-1,4);number(0xff000000,4)
            repeat(3){number(2,8)};number(changed.size.toLong(),2)
            changed.forEach {(row,value)->number(row.toLong(),2);number(1,2);number(0,2);number(1,1);number(0,4);number(-1,4);number(0xff000000,4);text(value)}
        }
        return out.toByteArray()
    }
    @Test fun deltasReuseUntouchedRowsAndPreserveUnicode() {
        val decoder=TerminalUpdates()
        val first=decoder.decode(packet(1,3,mapOf(0 to "λ",1 to "🦀"),"""{"phase":"connected","selectedBlock":"a"}"""))
        val second=decoder.decode(packet(4,2,mapOf(1 to "\uDB80\uDF1D")))
        assertSame(first.screen!!.lines[0],second.screen!!.lines[0])
        assertEquals("\uDB80\uDF1D",second.screen.lines[1][0].text)
        assertEquals("a",second.selectedBlock);assertEquals(4L,decoder.revision)
        val unchanged=decoder.decode(packet(5,0,emptyMap(),"""{"phase":"paused"}"""))
        assertSame(second.screen,unchanged.screen)
        assertNull(decoder.decode(packet(6,1,emptyMap())).screen)
    }
    @Test fun unknownBaseTruncationAndDuplicateRevisionAreRejectedWithoutAdvancing() {
        val decoder=TerminalUpdates()
        assertTrue(runCatching {decoder.decode(packet(1,2,mapOf(0 to "x")))}.isFailure)
        val full=packet(1,3,mapOf(0 to "x",1 to "y"))
        assertTrue(runCatching {decoder.decode(full.copyOf(full.size-1))}.isFailure)
        assertEquals(0L,decoder.revision)
        decoder.decode(full)
        assertTrue(runCatching {decoder.decode(full)}.isFailure)
        assertEquals(1L,decoder.revision)
    }
}
