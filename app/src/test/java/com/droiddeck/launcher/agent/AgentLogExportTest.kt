package com.droiddeck.launcher.agent

import android.os.Bundle
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.installJvmSecureSourceOpener
import com.droiddeck.launcher.session.resetSecureSourceOpener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AgentLogExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousLogDirectory: File? = null

    @Before fun prepare() {
        previousLogDirectory = SessionState.logDirectory
        installJvmSecureSourceOpener()
    }

    @After fun cleanup() {
        SessionState.logDirectory = previousLogDirectory
        resetSecureSourceOpener()
    }

    @Test fun rejectsUnexpectedFrameContent() {
        val session = File(context.cacheDir, "unsafe-agent-session").apply { mkdirs() }
        try {
            File(session, "wayland.log.mobile-frames.log").writeText("12.123456789 1 create\npassword=secret\n")
            SessionState.logDirectory = session
            val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
            val response = JSONObject(provider.call("logs", null, Bundle()).getString("json")!!)
            assertEquals(false, response.optBoolean("ok"))
        } finally { session.deleteRecursively() }
    }

    @Test fun independentLogRequestsKeepBothExportDirectories() {
        val session = File(context.cacheDir, "agent-session").apply { mkdirs() }
        File(session, "session.log").writeText("secret-must-not-export\n")
        File(session, "wayland.log.mobile-frames.log").writeText("12.123456789 1 create\n")
        SessionState.logDirectory = session
        val provider = Robolectric.buildContentProvider(AgentBridgeProvider::class.java).create().get()
        var firstDir: File? = null
        var secondDir: File? = null

        try {
            fun requestLogs() = JSONObject(provider.call("logs", null, Bundle()).getString("json")!!)
            val first = requestLogs()
            val second = requestLogs()
            firstDir = File(first.getString("cleanupDir"))
            secondDir = File(second.getString("cleanupDir"))
            val firstZip = File(first.getString("zip"))
            val secondZip = File(second.getString("zip"))

            assertNotEquals(firstDir, secondDir)
            assertEquals(firstDir, firstZip.parentFile)
            assertEquals(secondDir, secondZip.parentFile)
            assertTrue(firstZip.isFile)
            assertTrue(secondZip.isFile)
            java.util.zip.ZipFile(firstZip).use { zip ->
                assertEquals(listOf("mobile-frames.log"), zip.entries().toList().map { it.name })
                assertEquals("12.123456789 1 create\n", zip.getInputStream(zip.entries().nextElement()).bufferedReader().readText())
            }
        } finally {
            firstDir?.deleteRecursively()
            secondDir?.deleteRecursively()
            session.deleteRecursively()
        }
    }
}
