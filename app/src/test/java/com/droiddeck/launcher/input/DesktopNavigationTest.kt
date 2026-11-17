package com.droiddeck.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DesktopNavigationTest {
    private val requests = mutableListOf<Int>()
    private val vibrations = mutableListOf<VibrationEffect>()
    private var platformAccepts = true

    @Before fun watchThePlatform() {
        NavigationHaptics.request = { view ->
            requests += HapticFeedbackConstants.VIRTUAL_KEY
            platformAccepts && view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        NavigationHaptics.vibrate = { _, effect ->
            vibrations += effect
            true
        }
        NavigationHaptics.systemSetting = null
    }

    @After fun restore() {
        NavigationHaptics.request = { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        NavigationHaptics.vibrate = { vibrator, effect -> vibrator.vibrate(effect); true }
        NavigationHaptics.systemSetting = null
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun vibrator(activity: Activity): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(VibratorManager::class.java).defaultVibrator
        else activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun drag(view: View, toX: Float, toY: Float) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_MOVE, toX, toY, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, toX, toY, 0))
    }

    @Test fun remainsReachableAfterIdleAndDoesNotClickThroughTheWakeHandle() {
        val activity = activity()
        val actions = mutableListOf<String>()
        val navigation = DesktopNavigation(activity) { actions += it }
        activity.setContentView(navigation)
        navigation.active(true)
        assertEquals(View.VISIBLE, navigation.visibility)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(7, TimeUnit.SECONDS)
        assertTrue(navigation.collapsed)
        navigation.wakeHandle.performClick()
        assertFalse(navigation.collapsed)
        assertTrue(actions.isEmpty())
        navigation.homeButton.performClick()
        assertEquals(listOf("home"), actions)
    }

    @Test fun everyTapRequestsOneLightHapticAndNothingElse() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        navigation.homeButton.performClick()
        navigation.gripButton.performClick()
        assertTrue(navigation.collapsed)
        navigation.wakeHandle.performClick()
        assertFalse(navigation.collapsed)
        assertEquals(List(3) { HapticFeedbackConstants.VIRTUAL_KEY }, requests)
        assertTrue("the platform took the request, so no second vibration", vibrations.isEmpty())
        assertFalse(shadowOf(vibrator(activity)).isVibrating)
    }

    @Test fun aTapWhoseRequestThePlatformDropsVibratesOnceInstead() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        platformAccepts = false
        navigation.homeButton.performClick()
        assertEquals(1, requests.size)
        assertEquals(1, vibrations.size)
        assertEquals(1, vibrations.size)
    }

    @Test fun theFallbackFollowsTheSystemHapticSetting() {
        val activity = activity()
        platformAccepts = false
        NavigationHaptics.systemSetting = false
        assertFalse(NavigationHaptics.tap(View(activity)))
        assertTrue(vibrations.isEmpty())
        NavigationHaptics.systemSetting = true
        shadowOf(vibrator(activity)).setHasVibrator(false)
        assertFalse("a device without a vibrator has nothing to fall back to", NavigationHaptics.tap(View(activity)))
        assertTrue(vibrations.isEmpty())
    }

    @Test fun noHapticIsRequestedWhileTheCapsuleIsDragged() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        drag(navigation.gripButton, 60f, 90f)
        assertEquals(0, requests.size)
        assertTrue(vibrations.isEmpty())
    }

    @Test fun aDragCollapsesToTheNearestSideAndKeepsTheHeight() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        ShadowLooper.idleMainLooper()
        navigation.x = 40f
        navigation.y = 120f
        navigation.gripButton.performClick()
        ShadowLooper.idleMainLooper()
        assertTrue(navigation.collapsed)
        assertEquals(View.VISIBLE, navigation.wakeHandle.visibility)
    }

    @Test fun aCollapsedHandleDragsToTheOtherSideAndStaysThere() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        ShadowLooper.idleMainLooper()
        navigation.gripButton.performClick()
        ShadowLooper.idleMainLooper()
        val host = navigation.parent as View
        requests.clear()
        drag(navigation.wakeHandle, host.width + 200f, 200f)
        ShadowLooper.idleMainLooper()
        assertTrue(navigation.collapsed)
        assertEquals(0, requests.size)
    }

    @Test fun aCollapsedHandleKeepsItsSideAcrossALayoutPass() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        navigation.gripButton.performClick()
        ShadowLooper.idleMainLooper()
        navigation.snapToEdge()
        ShadowLooper.idleMainLooper()
        navigation.wakeHandle.performClick()
        assertFalse(navigation.collapsed)
        assertEquals(View.GONE, navigation.wakeHandle.visibility)
    }

    @Test fun clampsDragAndKeepsTouchTargetsWhenVisualsShrink() {
        assertEquals(0f, NavigationPlacement.clamp(-30f, 100f))
        assertEquals(100f, NavigationPlacement.clamp(130f, 100f))
        assertEquals(0f, NavigationPlacement.clamp(30f, -10f))
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        navigation.active(true)
        assertTrue(navigation.homeButton.layoutParams.width >= (48 * activity.resources.displayMetrics.density).toInt())
        navigation.active(false)
        assertEquals(View.GONE, navigation.visibility)
    }
}
