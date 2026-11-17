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
import android.widget.FrameLayout
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

    private fun session(activate: (DesktopNavigation) -> Unit = { it.active(true) }): Pair<DesktopNavigation, FrameLayout> {
        val activity = activity()
        val host = FrameLayout(activity)
        val navigation = DesktopNavigation(activity) {}
        host.addView(navigation)
        activity.setContentView(host)
        relayout(host, 800)
        activate(navigation)
        ShadowLooper.idleMainLooper()
        relayout(host, 800)
        return navigation to host
    }

    private fun relayout(host: FrameLayout, sizeDp: Int) {
        val size = (sizeDp * host.resources.displayMetrics.density).toInt()
        host.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, size, size)
    }

    @Test fun theCapsuleIsAsWideAsItsBarAndItsButtonsAreTouchTargets() {
        // On the tablet the capsule measured 120x120 with every button at 0,0-0,0: 120 is one hidden handle, so the
        // capsule was sized by its hidden child and the bar never got a frame to draw or hit-test.
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        val density = activity.resources.displayMetrics.density
        val target = (48 * density).toInt()
        var declared = 0
        for (i in 0 until navigation.bar.childCount) {
            val child = navigation.bar.getChildAt(i)
            declared += child.declaredWidth()
            assertEquals("button $i keeps a 48 dp target", target, child.declaredWidth())
            assertEquals(target, child.declaredHeight())
            assertTrue(child.isClickable)
        }
        assertEquals("the five buttons and the handle beside them", 6 * target, declared + navigation.wakeHandle.declaredWidth())
        // The bar is laid out at the size of its five buttons, which a hidden sibling used to deny it.
        navigation.bar.measure(
            View.MeasureSpec.makeMeasureSpec(6 * target, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.AT_MOST),
        )
        assertEquals("the bar is every button's 48 dp", 5 * target, navigation.bar.measuredWidth)
        assertEquals(target, navigation.bar.getChildAt(4).measuredWidth)
    }

    // A view's touch target is what it was declared with, which an unspecified measure leaves to the parent.
    private fun View.declaredWidth(): Int = when (val lp = layoutParams) {
        null -> 0
        else -> if (lp.width > 0) lp.width else measuredWidth
    }

    private fun View.declaredHeight(): Int = when (val lp = layoutParams) {
        null -> 0
        else -> if (lp.height > 0) lp.height else measuredHeight
    }

    @Test fun theCapsuleKeepsItsFrameWhenAFullLayoutPullIsApplied() {
        // aapt sends FORCE_LAYOUT down to every view for pull-to-refresh; a layout from the stale measure is what emptied the bar
        val (navigation, host) = session()
        val width = navigation.width
        navigation.bar.forceLayout()
        navigation.forceLayout()
        host.layout(0, 0, host.width, host.height)
        assertEquals(width, navigation.width)
        assertEquals(width, navigation.bar.width)
        assertTrue(navigation.bar.getChildAt(0).width >= (48 * navigation.resources.displayMetrics.density).toInt())
    }

    @Test fun theGripTapCollapsesEvenWhileTheCapsuleIsPinnedOpen() {
        // Pinned keeps the idle timer from folding the bar; the grip is the user asking for it, and its own tap must not be swallowed.
        val prefs = Robolectric.buildActivity(Activity::class.java).setup().get().getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pinned", true).commit()
        try {
            val (navigation, _) = session()
            navigation.gripButton.performClick()
            ShadowLooper.idleMainLooper()
            assertTrue("the grip tap collapses a pinned capsule", navigation.collapsed)
            assertEquals(View.VISIBLE, navigation.wakeHandle.visibility)
            navigation.wakeHandle.performClick()
            ShadowLooper.idleMainLooper()
            assertFalse("the wake handle brings it back", navigation.collapsed)
            navigation.gripButton.performClick()
            ShadowLooper.idleMainLooper()
            assertTrue(navigation.collapsed)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun theCollapsedHandleLandsOnTheNearestSideWithItsHeightKept() {
        // A collapsed handle is kept at the side it was left on, and its height survives the session it was left in.
        // The first frame a view is measured at is not the screen, so the side is read from where the handle is,
        // relative to the width it was saved against.
        for (collapsedOnLeft in listOf(true, false)) {
            val (navigation, host) = session()
            val saved = host.context.getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
            saved.edit().clear().commit()
            val width = navigation.width
            navigation.x = if (collapsedOnLeft) 0f else (host.width - width).toFloat()
            navigation.y = 300f
            navigation.gripButton.performClick()
            ShadowLooper.idleMainLooper()
            assertTrue(navigation.collapsed)
            assertEquals("the handle keeps the height it was dragged to", 300, saved.getInt("top", -1))
            assertEquals("and the side it was left on", if (collapsedOnLeft) "left" else "right", saved.getString("edge", null))
            navigation.active(false)
            navigation.active(true)
            ShadowLooper.idleMainLooper()
            assertEquals("a restored session keeps that side", if (collapsedOnLeft) "left" else "right", saved.getString("edge", null))
            assertTrue(navigation.collapsed)
            assertEquals(View.VISIBLE, navigation.wakeHandle.visibility)
            assertEquals(View.GONE, navigation.bar.visibility)
        }
    }
}
