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
class KeyboardHostTextTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val log = ArrayList<String>()

    private fun host(purpose: Int = 0, hint: Int = 0, textAccepted: Boolean = true): KeyboardHost {
        val host = KeyboardHost(context, sendKeyEvent = { event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                log += "key:" + (event.getUnicodeChar().takeIf { it != 0 }?.toChar()?.toString() ?: "?")
            }
            true
        }, sendText = { text ->
            log += "text:$text"
            textAccepted
        })
        host.content(purpose, hint)
        host.show()
        return host
    }

    @Test fun charactersTheKeyMapCannotTypeReachTheDesktopAsText() {
        val ic = host().onCreateInputConnection(EditorInfo())!!
        ic.commitText("中文", 1)
        assertEquals(listOf("text:中文"), log)
    }

    @Test fun aMixedCommitKeepsItsOrderThroughOneRequest() {
        val ic = host().onCreateInputConnection(EditorInfo())!!
        ic.commitText("ab中文cd", 1)
        assertEquals(listOf("text:ab中文cd"), log)
    }

    @Test fun aPasswordFieldNeverHandsItsTextToTheDesktop() {
        val ic = host(ImeContentType.PURPOSE_PASSWORD).onCreateInputConnection(EditorInfo())!!
        ic.commitText("中文", 1)
        assertTrue("no text may be written for a password field: $log", log.none { it.startsWith("text:") })
    }

    @Test fun hiddenTextIsTreatedTheSameWay() {
        val ic = host(0, ImeContentType.HINT_HIDDEN_TEXT).onCreateInputConnection(EditorInfo())!!
        ic.commitText("中文", 1)
        assertTrue("no text may be written for a hidden field: $log", log.none { it.startsWith("text:") })
    }

    @Test fun withoutATextChannelTheCharactersBecomeKeyEvents() {
        val ic = host(textAccepted = false).onCreateInputConnection(EditorInfo())!!
        ic.commitText("中", 1)
        assertTrue("the character must not disappear: $log", log.any { it.startsWith("key:") })
    }
}
