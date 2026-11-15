package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class KeyboardHostTest {
    private lateinit var imm: InputMethodManager
    private lateinit var host: KeyboardHost

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        host = KeyboardHost(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(host) })
        idle()
    }

    @Config(sdk = [33])
    @Test fun toggleShowsAKeyboardTheSystemDismissed() {
        host.show()
        idle()
        imm.hideSoftInputFromWindow(host.windowToken, 0)
        idle()
        host.toggle()
        idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(host.shown)
    }

    @Config(sdk = [28])
    @Test fun toggleHidesAKeyboardThatIsShowing() {
        host.show()
        idle()
        host.toggle()
        idle()
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertFalse(host.shown)
    }

    @Test fun sensitiveFieldsUseKeysAndNeverTheBridge() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var commits = 0
        var preedits = 0
        var keys = 0
        val secret = KeyboardHost(activity, sendKeyEvent = { keys++; true },
            commitText = { commits++; true }, preeditText = { _, _ -> preedits++; true },
            sensitive = { true })
        secret.show()
        val connection = secret.onCreateInputConnection(EditorInfo())!!
        connection.setComposingText("ab", 1)
        connection.commitText("ab", 1)
        idle()
        assertEquals(0, commits)
        assertEquals(0, preedits)
        assertTrue(keys > 0)
    }

    @Test fun showAndHideNotifyTheBridgeOnce() {
        var shown = 0
        var hidden = 0
        host.onShown = { shown++ }
        host.onHidden = { hidden++ }
        host.show()
        host.show()
        host.hide()
        host.hide()
        idle()
        assertEquals(1, shown)
        assertEquals(1, hidden)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
