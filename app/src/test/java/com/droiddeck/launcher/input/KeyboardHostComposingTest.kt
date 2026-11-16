package com.droiddeck.launcher.input

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class KeyboardHostComposingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<KeyEvent>()

    private fun connection(): android.view.inputmethod.InputConnection {
        val host = KeyboardHost(context) { event -> sent += event; true }
        host.content(0, 0)
        host.show()
        return host.onCreateInputConnection(EditorInfo())!!
    }

    private val typed: String
        get() = sent.filter { it.action == KeyEvent.ACTION_DOWN }
            .joinToString("") { event ->
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\n"
                    KeyEvent.KEYCODE_DEL -> "\b"
                    KeyEvent.KEYCODE_FORWARD_DEL -> "\u007f"
                    else -> event.getUnicodeChar().takeIf { it != 0 }?.toChar()?.toString().orEmpty()
                }
            }

    @Test fun composingIsHeldUntilTheImeFinishesIt() {
        val ic = connection()
        ic.setComposingText("plasma", 1)
        assertEquals("the IME's preview must not reach the guest as typed text", "", typed)
        ic.finishComposingText()
        assertEquals("plasma", typed)
    }

    @Test fun choosingACandidateTypesOnlyTheChosenText() {
        val ic = connection()
        ic.setComposingText("pinyin", 1)
        ic.commitText("中文", 1)
        assertEquals("中文", typed)
    }

    @Test fun enterCommitsThePendingTextBeforeTheNewline() {
        val ic = connection()
        ic.setComposingText("echo", 1)
        ic.performEditorAction(EditorInfo.IME_ACTION_NONE)
        assertEquals("echo\n", typed)
    }

    @Test fun backspaceWhileComposingDoesNotDeleteGuestText() {
        val ic = connection()
        ic.setComposingText("echx", 1)
        ic.deleteSurroundingText(1, 0)
        assertEquals("a backspace inside the IME's own word must not reach the guest", "", typed)
        ic.finishComposingText()
        assertEquals("ech", typed)
    }

    @Test fun backspacePastTheComposingTextReachesTheGuest() {
        val ic = connection()
        ic.setComposingText("ab", 1)
        ic.deleteSurroundingText(3, 0)
        assertEquals("\b", typed)
        ic.finishComposingText()
        assertEquals("\b", typed)
    }

    @Test fun anEnterKeyEventFromTheImeAlsoFinishesComposing() {
        val ic = connection()
        ic.setComposingText("ls", 1)
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        assertEquals("ls\n", typed)
    }

    @Test fun aPlainCommitStillTypesTheText() {
        val ic = connection()
        ic.commitText("konsole", 1)
        assertEquals("konsole", typed)
    }
}
