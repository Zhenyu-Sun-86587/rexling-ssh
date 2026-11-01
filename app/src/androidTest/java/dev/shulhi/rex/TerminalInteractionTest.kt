package dev.shulhi.rex

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalInteractionTest {
    @Test fun verticalHistoryDragDoesNotSwitchPaneOrType() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var lines=0;var paneDrag=false;var typed=false
        instrumentation.runOnMainSync {
            val view=TerminalView(instrumentation.targetContext).apply {
                screen=TerminalScreen(20,10,null,null,-1,0,emptyList(),TerminalScroll(100,90,10))
                onScroll={lines+=it};onPaneDrag={_,active->if(active) paneDrag=true};onInput={typed=true}
            }
            fun event(action: Int,x: Float,y: Float,time: Long) {
                val e=MotionEvent.obtain(0,time,action,x,y,0);view.onTouchEvent(e);e.recycle()
            }
            event(MotionEvent.ACTION_DOWN,50f,50f,0)
            event(MotionEvent.ACTION_MOVE,50f,250f,100)
            event(MotionEvent.ACTION_UP,50f,250f,200)
        }
        assertTrue(lines<0);assertFalse(paneDrag);assertFalse(typed)
    }
    @Test fun packagedAdaptiveLauncherIconInflatesAndDraws() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Rexlet",context.applicationInfo.loadLabel(context.packageManager).toString())
        val icon=context.applicationInfo.loadIcon(context.packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        val bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,192,192);icon.draw(Canvas(bitmap))
        var colored=0
        for(y in 0 until 192) for(x in 0 until 192) {
            val pixel=bitmap.getPixel(x,y)
            if(android.graphics.Color.alpha(pixel)>200 && android.graphics.Color.blue(pixel)>android.graphics.Color.red(pixel)+30) colored++
        }
        assertTrue("Dinosaur artwork missing from launcher icon",colored>1000)
        File(context.cacheDir,"rexlet-launcher-preview.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
