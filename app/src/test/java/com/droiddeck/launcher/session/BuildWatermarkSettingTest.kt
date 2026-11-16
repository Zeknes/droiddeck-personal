package com.droiddeck.launcher.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import com.droiddeck.launcher.ui.BuildWatermark
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class BuildWatermarkSettingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val originalLineProvider = BuildWatermark.lineProvider

    @Before fun clear() {
        BuildWatermark.lineProvider = {
            BuildWatermark.lines(pr = 1234, ci = true, commit = "abcdef0123", title = "Test build", branch = "")
        }
        listOf("build-watermark", "build-watermark.new", "build-watermark.bak", "build-watermark.lock").forEach {
            File(context.filesDir, it).delete()
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanUp() {
        try {
            clear()
        } finally {
            BuildWatermark.lineProvider = originalLineProvider
        }
    }

    @Test fun defaultsOffAndPreservesLegacyChoice() {
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", true).commit()
        assertTrue(SessionPrefs.buildWatermarkEnabled(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", false).commit()
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun initialUiStateAndResetKeepTheWatermarkOff() {
        val state = com.droiddeck.launcher.ui.FrontEndState(
            installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
            desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null,
            logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
        )
        assertFalse(state.buildWatermark)
        SessionPrefs.setBuildWatermarkEnabled(context, true)
        assertTrue(SettingsReset.resetAll(context, gameProfiles = false))
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun sharedFileOverridesCachedPreferencesAndNotifiesOtherProcesses() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", false).commit()
        SessionPrefs.setBuildWatermarkEnabled(context, true)
        assertTrue(SessionPrefs.buildWatermarkEnabled(context))
        val change = shadowOf(context).broadcastIntents.last { it.action == BuildWatermarkSetting.CHANGED }
        assertEquals(context.packageName, change.`package`)
        File(context.filesDir, "build-watermark").writeText("off")
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun lockOpenFailureFallsBackToPreferences() {
        val lock = File(context.filesDir, "build-watermark.lock")
        assertTrue(lock.mkdir())
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", true).commit()
        assertTrue(SessionPrefs.buildWatermarkEnabled(context))
        assertThrows(java.io.IOException::class.java) { SessionPrefs.setBuildWatermarkEnabled(context, true) }
    }

    @Test fun settingReadFailureFallsBackToPreferences() {
        assertTrue(File(context.filesDir, "build-watermark").mkdir())
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", false).commit()
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun remoteChangeRefreshesExistingAndRecreatedActivities() {
        BuildWatermark.install(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            SessionPrefs.setBuildWatermarkEnabled(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            File(context.filesDir, "build-watermark").writeText("off")
            context.sendBroadcast(Intent(BuildWatermarkSetting.CHANGED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            activity.recreate()
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            SessionPrefs.setBuildWatermarkEnabled(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
