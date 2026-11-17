package com.droiddeck.launcher.input

import android.app.Activity
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DesktopNavigationTest {
    @Test fun remainsReachableAfterIdleAndDoesNotClickThroughTheWakeHandle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
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

    @Test fun clampsDragAndKeepsTouchTargetsWhenVisualsShrink() {
        assertEquals(0f, NavigationPlacement.clamp(-30f, 100f))
        assertEquals(100f, NavigationPlacement.clamp(130f, 100f))
        assertEquals(0f, NavigationPlacement.clamp(30f, -10f))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val navigation = DesktopNavigation(activity) {}
        navigation.active(true)
        assertTrue(navigation.homeButton.layoutParams.width >= (48 * activity.resources.displayMetrics.density).toInt())
        navigation.active(false)
        assertEquals(View.GONE, navigation.visibility)
    }
}
