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
import androidx.core.view.children
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
        requests.clear(); vibrations.clear(); platformAccepts = true
        NavigationHaptics.request = { view ->
            requests += HapticFeedbackConstants.VIRTUAL_KEY
            platformAccepts && view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        NavigationHaptics.vibrate = { _, effect ->
            vibrations += effect
            true
        }
        NavigationHaptics.systemSetting = null
        activity().getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() {
        NavigationHaptics.request = { it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        NavigationHaptics.vibrate = { vibrator, effect -> vibrator.vibrate(effect); true }
        NavigationHaptics.systemSetting = null
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A tap is a finger arriving and leaving on the same button: the effect plays on arrival. */
    private fun tap(navigation: DesktopNavigation, name: String): VibrationEffect {
        val button = navigation.bar.children.first { it.contentDescription == name }
        press(button)
        release(button)
        return vibrations.last()
    }

    @Test fun eachBarButtonAndTheHandlePlayTheTapEffect() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        assertEquals(5, navigation.bar.childCount)
        for (name in listOf("Back in desktop", "Plasma desktop", "Desktop windows", "Show or hide keyboard")) tap(navigation, name)
        tap(navigation, "Collapse controls; drag to move; long press for options")
        press(navigation.wakeHandle)
        release(navigation.wakeHandle)
        assertEquals(List(6) { NavigationHaptics.TAP }, vibrations)
    }

    @Test fun anArrivingFingerPlaysTheTapEffectThatReleasingItPlays() {
        // The reported miss: pressing the handle or a button without lifting the finger in the same instant. A drag
        // that never happens must feel exactly like a tap, and the release must not add a second one.
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        for (target in listOf(navigation.homeButton, navigation.gripButton, navigation.wakeHandle)) {
            vibrations.clear()
            press(target)
            assertEquals("one tap on touch-down of ${target.contentDescription}", listOf(NavigationHaptics.TAP), vibrations)
            release(target)
            assertEquals("the release adds nothing", listOf(NavigationHaptics.TAP), vibrations)
        }
        assertFalse(navigation.collapsed)
    }

    @Test fun aDragPlaysTheTapEffectOnceWhenItBecomesADrag() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        drag(navigation.gripButton, 60f, 90f)
        assertEquals("a drag is one touch, played once", listOf(NavigationHaptics.TAP), vibrations)
    }

    private fun press(view: View) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
    }

    private fun release(view: View) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP, 10f, 10f, 0))
    }

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

    @Test fun everyTapVibratesTheClickAndTheHandleStillOpensAndCloses() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        press(navigation.homeButton); release(navigation.homeButton)
        press(navigation.gripButton); release(navigation.gripButton)
        assertTrue(navigation.collapsed)
        press(navigation.wakeHandle); release(navigation.wakeHandle)
        assertFalse(navigation.collapsed)
        assertTrue(requests.isEmpty())
        assertEquals(List(3) { NavigationHaptics.TAP }, vibrations)
    }

    @Test fun aTapDoesNotRelyOnTheWindowDrawingAFeedbackEffect() {
        val activity = activity()
        val navigation = DesktopNavigation(activity) {}
        activity.setContentView(navigation)
        navigation.active(true)
        press(navigation.gripButton); release(navigation.gripButton)
        press(navigation.wakeHandle); release(navigation.wakeHandle)
        assertTrue(requests.isEmpty())
        assertEquals(List(2) { NavigationHaptics.TAP }, vibrations)
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

    @Test fun theTapVibrationFollowsTheSystemHapticSettingAndNeedsAVibrator() {
        val activity = activity()
        NavigationHaptics.systemSetting = false
        NavigationHaptics.playTap(activity)
        assertTrue(vibrations.isEmpty())
        NavigationHaptics.systemSetting = true
        NavigationHaptics.playTap(activity)
        assertEquals(listOf(NavigationHaptics.TAP), vibrations)
        vibrations.clear()
        shadowOf(vibrator(activity)).setHasVibrator(false)
        NavigationHaptics.playTap(activity)
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

    @Test fun everyCapsuleTapRequestsTheMaximumAmplitude() {
        // The request as the platform is handed it, not a callback: every control and the handle build one one-shot of
        // 40 ms at amplitude 255, and none asks for the system default (-1) that leaves strength to the setting.
        val built = mutableListOf<Pair<Long, Int>>()
        val activity = activity()
        val original = tapEffect
        tapEffect = { duration, amplitude ->
            built += duration to amplitude
            original(duration, amplitude)
        }
        try {
            val navigation = DesktopNavigation(activity) {}
            activity.setContentView(navigation)
            navigation.active(true)
            for (name in listOf("Show or hide keyboard", "Back in desktop", "Plasma desktop", "Desktop windows")) {
                val button = navigation.bar.children.first { it.contentDescription == name }
                press(button); release(button)
            }
            press(navigation.gripButton); release(navigation.gripButton)
            assertTrue(navigation.collapsed)
            press(navigation.wakeHandle); release(navigation.wakeHandle)
            assertEquals("one per control, the handle included", 6, vibrations.size)
            assertEquals("the request each one is built from", List(6) { TAP_MS to MAX_AMPLITUDE }, built)
            assertTrue("the default amplitude (-1) is never asked for", built.none { it.second == VibrationEffect.DEFAULT_AMPLITUDE })
            assertTrue("the amplitude is the maximum one-shot amplitude", built.all { it.second == 255 })
        } finally {
            tapEffect = original
        }
    }

    @Test fun theKeyboardSitsAtThePhysicalLeftEndAndTheGripAtTheRightOnEitherSide() {
        for (rtl in listOf(false, true)) {
            val (navigation, host) = session()
            for (edge in listOf("left", "right")) {
                activity().getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE).edit()
                    .putString("edge", edge).apply()
                if (rtl) navigation.layoutDirection = View.LAYOUT_DIRECTION_RTL else navigation.layoutDirection = View.LAYOUT_DIRECTION_LTR
                navigation.active(false)
                navigation.active(true)
                press(navigation.wakeHandle); release(navigation.wakeHandle)
                relayout(host, 800)
                val keyboard = navigation.keyboardButton
                val grip = navigation.gripButton
                assertEquals("keyboard is the leftmost button", 0, keyboard.left)
                assertTrue("grip is at the right end", grip.left > navigation.homeButton.left)
                assertTrue(navigation.bar.width - (grip.left + grip.width) <= navigation.bar.paddingRight)
                assertEquals("collapse control sits right of the keyboard", true, grip.left > keyboard.right)
            }
            navigation.active(false)
        }
    }

    @Test fun theInnerSequenceOfBackHomeAndOverviewIsUnchanged() {
        val (navigation, host) = session()
        relayout(host, 800)
        val order = (0 until navigation.bar.childCount).map { (navigation.bar.getChildAt(it) as View).contentDescription }
        assertEquals(listOf("Show or hide keyboard", "Back in desktop", "Plasma desktop", "Desktop windows",
            "Collapse controls; drag to move; long press for options"), order)
        val back = navigation.bar.getChildAt(1) as View
        val overview = navigation.bar.getChildAt(3) as View
        assertTrue("back follows the keyboard", back.left >= navigation.keyboardButton.right)
        assertTrue("home follows back", navigation.homeButton.left >= back.right)
        assertTrue("overview follows home", overview.left >= navigation.homeButton.right)
        assertTrue("the grip closes the row", navigation.gripButton.left >= overview.right)
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

    @Test fun collapsedBoundsShrinkToOneTargetAndTheVacatedAreaPassesThrough() {
        val activity = activity()
        val host = FrameLayout(activity)
        var underneath = 0
        host.addView(View(activity).apply {
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_UP) underneath++; true }
        }, FrameLayout.LayoutParams(-1, -1))
        val navigation = DesktopNavigation(activity) {}
        host.addView(navigation)
        navigation.active(true)
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 1000, 700)
        }
        layout()
        val target = (48 * activity.resources.displayMetrics.density).toInt()
        assertEquals(target * 5, navigation.width)
        assertEquals(target, navigation.height)
        assertEquals(target * 5, navigation.bar.width)
        navigation.x = 300f
        navigation.y = 200f
        navigation.gripButton.performClick()
        layout()
        assertEquals("collapsed host bounds must be one target", target, navigation.width)
        assertEquals(target, navigation.wakeHandle.width)
        assertEquals(0f, navigation.x, 0.01f)
        assertEquals(200f, navigation.y, 0.01f)
        val x = navigation.x + target + 10
        host.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, 210f, 0))
        host.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, x, 210f, 0))
        assertEquals("the old bar area belongs to the desktop", 1, underneath)
        navigation.wakeHandle.performClick()
        layout()
        assertEquals(target * 5, navigation.width)
        assertEquals(target, navigation.homeButton.width)
    }

    @Test fun theGripTapCollapsesEvenWhileTheCapsuleIsPinnedOpen() {
        // Pinned keeps the idle timer from folding the bar; the grip is the user asking for it, and its own tap must not be swallowed.
        val prefs = Robolectric.buildActivity(Activity::class.java).setup().get().getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pinned", true).commit()
        try {
            val (navigation, _) = session()
            press(navigation.gripButton); release(navigation.gripButton)
            ShadowLooper.idleMainLooper()
            assertTrue("the grip tap collapses a pinned capsule", navigation.collapsed)
            assertEquals(View.VISIBLE, navigation.wakeHandle.visibility)
            press(navigation.wakeHandle); release(navigation.wakeHandle)
            ShadowLooper.idleMainLooper()
            assertFalse("the wake handle brings it back", navigation.collapsed)
            press(navigation.gripButton); release(navigation.gripButton)
            ShadowLooper.idleMainLooper()
            assertTrue(navigation.collapsed)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun rightEdgeAndHeightSurviveRotationInsetsAndRecreation() {
        val activity = activity()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        val navigation = DesktopNavigation(activity) {}
        host.addView(navigation)
        navigation.active(true)
        fun layout(w: Int, h: Int) {
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
        }
        layout(1000, 700)
        navigation.x = 740f
        navigation.y = 300f
        navigation.gripButton.performClick()
        layout(1000, 700)
        assertEquals(952f, navigation.x, 0.01f)
        navigation.wakeHandle.performClick()
        layout(1000, 700)
        assertEquals(760f, navigation.x, 0.01f)
        navigation.gripButton.performClick()
        layout(1000, 700)
        layout(700, 1000)
        assertEquals(652f, navigation.x, 0.01f)
        assertEquals(300f, navigation.y, 0.01f)
        val insets = android.view.WindowInsets.Builder().setInsets(
            android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 750))
            .setVisible(android.view.WindowInsets.Type.ime(), true).build()
        assertEquals(750, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom)
        navigation.onApplyWindowInsets(insets)
        val safeField = DesktopNavigation::class.java.getDeclaredField("safeBottom").apply { isAccessible = true }
        assertEquals("IME reaches host controls", 750, safeField.getInt(navigation))
        assertEquals("host=${host.width}x${host.height}, nav=${navigation.width}x${navigation.height}, combined=${insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())}", 202f, navigation.y, 0.01f)
        navigation.onApplyWindowInsets(android.view.WindowInsets.Builder().build())
        assertEquals("removing the IME restores saved height", 300f, navigation.y, 0.01f)
        host.removeView(navigation)
        val restored = DesktopNavigation(activity) {}
        host.addView(restored)
        restored.active(true)
        layout(700, 1000)
        assertTrue(restored.collapsed)
        assertEquals(48, restored.width)
        assertEquals(652f, restored.x, 0.01f)
        assertEquals(300f, restored.y, 0.01f)
    }

    @Test fun theCollapsedHandleLandsOnTheNearestSideWithItsHeightKept() {
        // A collapsed handle is kept at the side it was left on, and its height survives the session it was left in.
        // The first frame a view is measured at is not the screen, so the side is read from where the handle is,
        // relative to the width it was saved against.
        for (collapsedOnLeft in listOf(true, false)) {
            activity().getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE).edit().clear().commit()
            val (navigation, host) = session()
            val saved = host.context.getSharedPreferences("desktop-navigation", Context.MODE_PRIVATE)
            val width = navigation.width
            navigation.x = if (collapsedOnLeft) 0f else (host.width - width).toFloat()
            navigation.y = 300f
            navigation.gripButton.performClick()
            relayout(host, 800)
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
