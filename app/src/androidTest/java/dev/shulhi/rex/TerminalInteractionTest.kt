package dev.shulhi.rex

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
}
