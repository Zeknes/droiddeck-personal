package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DesktopStartupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun theStartupPreferenceNeverLaunchesAGame() {
        assertEquals(SessionPrefs.STARTUP_OFF, SessionPrefs.startupSession(context))
        SessionPrefs.setStartupSession(context, SessionPrefs.STARTUP_DESKTOP)
        assertEquals(SessionPrefs.STARTUP_DESKTOP, SessionPrefs.startupSession(context))
    }

    @Test fun theOldRunSteamAtStartupFlagIsIgnored() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putBoolean("runSteamAtStartup", true).commit()
        assertEquals(SessionPrefs.STARTUP_OFF, SessionPrefs.startupSession(context))
    }

    @Test fun onlyTheDesktopCanBeChosen() {
        SessionPrefs.setStartupSession(context, "steam")
        assertEquals(SessionPrefs.STARTUP_OFF, SessionPrefs.startupSession(context))
        SessionPrefs.setStartupSession(context, SessionPrefs.STARTUP_OFF)
        assertEquals(SessionPrefs.STARTUP_OFF, SessionPrefs.startupSession(context))
    }
}
