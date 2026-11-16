package com.droiddeck.launcher.input

import android.os.Looper
import android.view.View
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionKeyboardPadTest {
    private lateinit var activity: SessionActivity
    private lateinit var controls: OnScreenControls
    private val directory = Files.createTempDirectory("keyboard-pad").toFile()
    private val previousRunning = SessionState.running
    private val previousMode = SessionState.mode
    private var previousHardKeyboardHidden = 0

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        previousHardKeyboardHidden = activity.resources.configuration.hardKeyboardHidden
        SessionState.mode = SessionService.MODE_STEAM
        controls = OnScreenControls(activity, null).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "onScreenControls", controls)
    }

    @After fun tearDown() {
        steamKeyboard(false)
        AgentGuest.stop()
        shadowOf(Looper.getMainLooper()).idle()
        SteamTouchDevice.release()
        directory.deleteRecursively()
        SessionState.mode = previousMode
        SessionState.running = previousRunning
        activity.resources.configuration.hardKeyboardHidden = previousHardKeyboardHidden
    }

    @Test fun padStepsAsideWhileEitherKeyboardIsUpInSteam() {
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_ALWAYS)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        steamKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
        steamKeyboard(false)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        pcKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
        pcKeyboard(false)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
    }

    @Test fun steamsKeyboardLeavesThePadAloneOutsideSteam() {
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_ALWAYS)
        steamKeyboard(true)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        pcKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
    }

    @Test fun steamTouchControllerHidesAndHandsTouchBackWhileSteamsKeyboardIsUp() {
        SteamTouchDevice.prepare(directory)
        val steam = SteamTouchControls(activity, SteamTouchDevice.current!!, {}, {})
        ReflectionHelpers.setField(activity, "steamTouchControls", steam)
        ReflectionHelpers.setField(activity, "steamTouchOptions",
            SteamTouchConfig.Options(inputMode = SteamTouchConfig.INPUT_CONTROLLER))
        SessionPrefs.setTouchMode(activity, SessionPrefs.TOUCH_DIRECT)
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_STEAM_TOUCH)
        update()
        assertEquals(View.VISIBLE, steam.visibility)
        assertEquals(SessionPrefs.TOUCH_OFF, routedTouchMode())
        steamKeyboard(true)
        update()
        assertEquals(View.GONE, steam.visibility)
        assertTrue(SteamTouchDevice.current!!.plugged)
        assertEquals(SessionPrefs.TOUCH_DIRECT, routedTouchMode())
        steamKeyboard(false)
        update()
        assertEquals(View.VISIBLE, steam.visibility)
        assertEquals(SessionPrefs.TOUCH_OFF, routedTouchMode())
    }

    @Test fun endingTheAgentForgetsSteamsKeyboard() {
        steamKeyboard(true)
        AgentGuest.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    @Test fun desktopFocusAppliesThePasswordTypeBeforeShowingTheKeyboard() {
        val host = desktopKeyboard()
        desktopField(autoShow = true, purpose = ImeContentType.PURPOSE_PASSWORD, hint = ImeContentType.HINT_HIDDEN_TEXT)
        assertTrue(host.shown)
        assertEquals(ImeContentType.PURPOSE_PASSWORD, host.purpose)
        assertEquals(ImeContentType.HINT_HIDDEN_TEXT, host.hint)
    }

    @Test fun suppressedMobileFocusDoesNotShowButRepeatedExplicitFieldRequestsDo() {
        val host = desktopKeyboard()
        desktopField(autoShow = false)
        assertFalse(host.shown)
        desktopField(autoShow = true)
        assertTrue(host.shown)
        host.hide()
        desktopField(autoShow = true)
        assertTrue(host.shown)
        desktopField(autoShow = false, focused = false)
        assertFalse(host.shown)
    }

    @Test fun aLateDesktopReportCannotOpenTheKeyboardWithoutARecentPress() {
        val host = desktopKeyboard()
        ReflectionHelpers.setField(activity, "lastUserPressMs", android.os.SystemClock.uptimeMillis() - 2_000L)
        desktopField(autoShow = true, press = false)
        assertFalse(host.shown)
    }

    private fun desktopKeyboard(): KeyboardHost {
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.running = true
        activity.resources.configuration.hardKeyboardHidden = android.content.res.Configuration.HARDKEYBOARDHIDDEN_YES
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "resumed\$delegate").value = true
        return KeyboardHost(activity) { true }.also { ReflectionHelpers.setField(activity, "keyboard", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun desktopField(autoShow: Boolean, focused: Boolean = true, purpose: Int = 0, hint: Int = 0, press: Boolean = true) {
        for ((name, value) in mapOf("desktopAutoShow" to autoShow, "desktopPurpose" to purpose, "desktopHint" to hint)) {
            val field = AgentGuest::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
            (field.get(AgentGuest) as MutableState<Any>).value = value
        }
        if (press) ReflectionHelpers.setField(activity, "lastUserPressMs", android.os.SystemClock.uptimeMillis())
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "onDesktopTextField", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, focused))
    }

    private fun update() = ReflectionHelpers.callInstanceMethod<Any?>(activity, "updateOnScreenControls")

    private fun routedTouchMode() = ReflectionHelpers.getField<String>(activity, "routedTouchMode")

    private fun pcKeyboard(open: Boolean) {
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "pcKeyboardOpen\$delegate").value = open
    }

    @Suppress("UNCHECKED_CAST")
    private fun steamKeyboard(showing: Boolean) {
        val field = AgentGuest::class.java.getDeclaredField("steamKeyboard\$delegate").apply { isAccessible = true }
        (field.get(AgentGuest) as MutableState<Boolean>).value = showing
    }
}
