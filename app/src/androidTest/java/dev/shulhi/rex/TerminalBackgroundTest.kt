package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalBackgroundTest {
    @Test fun aFullScreenApplicationBackgroundHasNoCellSeamsAtFractionalOffsets() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expected=0xff394457.toInt()
        for(offset in listOf(.25f,.5f,.75f)) {
            lateinit var result: Bitmap
            instrumentation.runOnMainSync {
                val context=instrumentation.targetContext
                val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
                val columns=18;val rows=4
                val cells=(0 until rows).flatMap {y->(0 until columns).map {x->TerminalCell(x,y,"",-1,expected,0,1)}}
                val frame=TerminalScreen(columns,rows,null,null,-1,0xff000000.toInt(),cells)
                val view=TerminalView(context).apply {screen=frame}
                val w=(columns*grid.cellWidth).toInt();val h=(rows*grid.cellHeight).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
                view.layout(0,0,w,h)
                result=Bitmap.createBitmap(w+3,h+3,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(result);canvas.translate(offset,offset);view.draw(canvas)
            }
            val y=result.height/2
            for(x in 2 until result.width-4) assertEquals("background seam at x=$x offset=$offset",expected,result.getPixel(x,y))
        }
    }
    @Test fun wideCharacterSpacersDoNotEraseTheLeadingCellsBackground() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expected=0xff394457.toInt()
        lateinit var result: Bitmap
        var cellWidth=0f
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val grid=TerminalFonts(context).configure(android.graphics.Paint(),10f)
            cellWidth=grid.cellWidth
            val view=TerminalView(context).apply {screen=TerminalScreen(3,1,null,null,-1,0, listOf(
                TerminalCell(0,0,"",-1,expected,0,2),TerminalCell(1,0,"",-1,0,256,0),TerminalCell(2,0,"",-1,expected,0,1)))}
            val w=(3*grid.cellWidth).toInt();val h=grid.cellHeight.toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,w,h)
            result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))
        }
        assertEquals(expected,result.getPixel((cellWidth*1.5f).toInt(),result.height/2))
    }
}
