package dev.shulhi.rex

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TerminalTextInputTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()

    @Test fun normalTerminalUsesTheSameEditableInputPathAsChineseWithoutSuggestions() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            val view=TerminalView(instrumentation.targetContext).apply {inputAvailable=true;onInput={sent+=it}}
            val info=EditorInfo();val input=view.onCreateInputConnection(info)!!
            assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL,info.inputType and InputType.TYPE_MASK_VARIATION)
            assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS!=0)
            input.setComposingText("abc",1);assertTrue(sent.isEmpty())
            input.commitText("abc",1);input.finishComposingText()
            input.commitText("\n",1);input.deleteSurroundingText(1,0)
            assertEquals(listOf("abc","\r","\u007f"),sent)
        }
    }
    @Test fun terminalInputRetainsUpstreamFlagsAndWorksAcrossOwnership() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            val view=TerminalView(instrumentation.targetContext).apply {inputAvailable=true;secureKeyboard=true;onInput={sent+=it}}
            val info=EditorInfo();val input=view.onCreateInputConnection(info)!!
            assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,info.inputType)
            assertTrue(input.commitText("a",1))
            view.controlled=true
            assertTrue(input.commitText("\n",1))
            assertTrue(input.deleteSurroundingText(1,0))
            assertEquals(listOf("a","\r","\u007f"),sent)
        }
    }
    @Test fun terminalCompositionAndRepeatedConnectionProbesDoNotLoseInput() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            val view=TerminalView(instrumentation.targetContext).apply {inputAvailable=true;secureKeyboard=true;onInput={sent+=it}}
            val input=view.onCreateInputConnection(EditorInfo())!!
            assertTrue(input.setComposingText("abc",1))
            val probe=view.onCreateInputConnection(EditorInfo())!!
            probe.closeConnection()
            assertTrue(input.finishComposingText())
            assertTrue(input.finishComposingText())
            assertTrue(view.onCreateInputConnection(EditorInfo())!!.commitText("你好",1))
            assertEquals(listOf("abc","你好"),sent)
            view.controlled=true;view.controlled=false
            assertTrue(input.commitText("z",1))
            assertEquals(listOf("abc","你好","z"),sent)
        }
    }
    @Test fun chineseCompositionCommitsOnlyTheSelectedCandidateOnce() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>()
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;textInput=true;onInput={sent+=it}}
            val info=EditorInfo();val input=view.onCreateInputConnection(info)!!
            assertEquals(0,info.inputType and InputType.TYPE_MASK_VARIATION)
            assertEquals(0,info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            input.setComposingText("ni",1)
            assertEquals("ni",input.getTextBeforeCursor(20,0).toString())
            input.setComposingText("你好",1)
            assertTrue(sent.isEmpty())
            input.commitText("你好",1);input.finishComposingText()
            assertEquals(listOf("你好"),sent)
            input.setComposingText("😀",1);input.deleteSurroundingTextInCodePoints(1,0)
            input.finishComposingText()
            assertEquals(listOf("你好"),sent)
        }
    }
    @Test fun modeAndPaneChangesRejectStaleImeCommits() {
        instrumentation.runOnMainSync {
            val sent=mutableListOf<String>();var pane="a"
            val view=TerminalView(instrumentation.targetContext).apply {controlled=true;secureKeyboard=true;textInput=true;mouseTarget={pane};onInput={sent+=it}}
            val old=view.onCreateInputConnection(EditorInfo())!!
            old.setComposingText("ni",1);view.textInput=false
            assertFalse(old.commitText("你",1));assertFalse(old.finishComposingText())
            val info=EditorInfo();val direct=view.onCreateInputConnection(info)!!
            assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,info.inputType and InputType.TYPE_MASK_VARIATION)
            direct.commitText("ls",1)
            pane="b";assertFalse(direct.commitText("wrong pane",1))
            assertEquals(listOf("ls"),sent)
        }
    }
}
