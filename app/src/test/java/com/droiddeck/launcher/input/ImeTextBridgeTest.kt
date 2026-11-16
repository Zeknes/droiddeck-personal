package com.droiddeck.launcher.input

import com.droiddeck.launcher.agent.AgentGuest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ImeTextBridgeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun eachRequestCarriesItsOwnSequenceAndOnlyTheOwnerMayReadIt() {
        val dir = AgentGuest.dir(app).apply { mkdirs() }
        try {
            assertTrue(ImeTextBridge.send(app, "中文"))
            val target = File(dir, "ime-text.json")
            val first = JSONObject(target.readText())
            assertEquals("中文", first.getString("text"))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
            assertTrue(ImeTextBridge.send(app, "second"))
            val second = JSONObject(target.readText())
            assertEquals("second", second.getString("text"))
            assertTrue("the guest must see a newer request", second.getLong("seq") > first.getLong("seq"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun nothingIsWrittenWithoutASessionDirectory() {
        val dir = AgentGuest.dir(app)
        dir.deleteRecursively()
        assertFalse(ImeTextBridge.send(app, "x"))
    }
}
