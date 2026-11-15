package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class NavTabsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        NavTabs.load(ctx)
        com.droiddeck.launcher.agent.AgentTab.load(ctx)
    }

    @Test fun personalNavigationContainsOnlyDesktopAndSetup() {
        assertEquals(listOf("desktop", "setup"), navigationKeys(state()))
        assertEquals(emptyList<String>(), NavTabs.more(state()))
        assertFalse(NavTabs.hideable("desktop"))
        assertFalse(NavTabs.hideable("setup"))
    }

    @Test fun savedOrderNeverRestoresGameDestinations() {
        assertEquals(listOf("desktop", "setup"), NavTabs.arrange(listOf("games", "setup", "steam")))
    }

    @Test fun backFromAShelvedTabReturnsToMore() {
        place("desktop", TabPlace.MORE)
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DroidDeckTheme { FrontEndScreen(state(), actions(), page = null) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("rail-more").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertDoesNotExist()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertIsDisplayed()
    }

    @Test fun startupSelectsDesktop() {
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidDeckTheme { FrontEndScreen(state(), actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNode(hasTestTag("rail-desktop") and androidx.compose.ui.test.isSelected()).assertExists()
        compose.onNodeWithTag("rail-games").assertDoesNotExist()
        compose.onNodeWithTag("rail-steam").assertDoesNotExist()
    }

    private fun pad(code: Int) = androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))

    private fun place(key: String, where: TabPlace, s: FrontEndState = state()) {
        NavTabs.setHidden(ctx, key, where == TabPlace.HIDDEN)
        if (where == TabPlace.HIDDEN) return
        val keys = NavTabs.listed(s)
        val more = keys.filter { if (it == key) where == TabPlace.MORE else it in NavTabs.shelved }
        NavTabs.rearrange(ctx, s, keys - more.toSet(), more)
    }

    private fun moveTab(key: String, step: Int, s: FrontEndState = state()) {
        val keys = NavTabs.listed(s)
        val rows = moveTabRow(tabRows(keys.filter { it !in NavTabs.shelved }, keys.filter { it in NavTabs.shelved }), key, step) ?: return
        val split = rows.indexOf(rows.first { it !in keys })
        NavTabs.rearrange(ctx, s, rows.subList(0, split), rows.subList(split + 1, rows.size))
    }

    private fun hasParentTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))

    private fun state(storesOn: Boolean = true, downloads: Int = 0, setupReady: Boolean = true) = FrontEndState(
        installed = "r9", ready = setupReady, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
        steamGames = emptyList(), emulators = emptyList(), running = null,
        gameStoresEnabled = storesOn, storeDownloadsActive = downloads,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
        onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
        onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
        onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
    )
}
