package com.droiddeck.launcher.session

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CrashHandlerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("logs", true).commit()
    }

    @After fun tearDown() {
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
    }

    @Test fun theTraceNamesTheThreadAndTheError() {
        val folder = tmp.newFolder("session")
        CrashHandler.writeTrace(folder, Thread.currentThread(), IllegalStateException("boom"))
        val written = File(folder, "crash-app.txt").readText()
        assertTrue(written.contains(Thread.currentThread().name))
        assertTrue(written.contains("IllegalStateException: boom"))
    }

    @Test fun theFallbackFolderIsTheOneTheLogBrowserOffersAfterASessionEnded() {
        val folder = SessionPaths.beginOrCurrent(context)
        SessionPaths.take()?.let { SessionPaths.release(context, it) }
        assertTrue(folder in SessionLogShare.sessions(context))
        assertEquals(folder.canonicalFile, CrashHandler.folderFor(context)?.canonicalFile)
    }
}
