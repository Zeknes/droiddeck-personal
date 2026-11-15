package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GnomeImeProtocolTest {
    private fun state(protocol: GnomeImeProtocol, generation: Int, ready: Boolean, inner: Boolean = true,
                      sensitive: Boolean = false) = protocol.state(
        """{"type":"state","generation":$generation,"connected":true,"inner":$inner,"outer":false,
           "sensitive":$sensitive,"shell":true,"ready":$ready}""",
    )

    @Test fun stateDrivesGenerationAndReady() {
        val protocol = GnomeImeProtocol()
        assertNull(protocol.focus())
        val state = state(protocol, 7, ready = true)!!
        assertEquals(7, state.generation)
        assertTrue(state.inner)
        assertFalse(state.sensitive)
        assertTrue(protocol.focus()!!.contains("\"type\":\"focus\""))
        assertTrue(protocol.focus()!!.contains("\"generation\":7"))
        assertTrue(protocol.commit("中文")!!.contains("中文"))
    }

    @Test fun textRequiresReady() {
        val protocol = GnomeImeProtocol()
        state(protocol, 4, ready = false, sensitive = true)
        assertNull(protocol.commit("密码"))
        assertNull(protocol.preedit("mi", 2))
        assertTrue(protocol.blur()!!.contains("\"generation\":4"))
    }

    @Test fun newerStateReplacesTheGeneration() {
        val protocol = GnomeImeProtocol()
        state(protocol, 2, ready = true)
        state(protocol, 5, ready = true)
        val commit = protocol.commit("ok")!!
        assertTrue(commit.contains("\"type\":\"commit\""))
        assertTrue(commit.contains("\"generation\":5"))
        assertTrue(commit.contains("\"text\":\"ok\""))
        assertTrue(protocol.blur()!!.contains("\"generation\":5"))
    }

    @Test fun ignoresMalformedAndNonStateLines() {
        val protocol = GnomeImeProtocol()
        assertNull(protocol.state("not json"))
        assertNull(protocol.state("""{"type":"other"}"""))
        assertNull(protocol.state("""{"type":"state","generation":-1}"""))
        assertNull(protocol.focus())
    }

    @Test fun focusAndBlurWorkBeforeReady() {
        val protocol = GnomeImeProtocol()
        state(protocol, 1, ready = false)
        assertTrue(protocol.focus()!!.contains("\"type\":\"focus\""))
        assertTrue(protocol.focus()!!.contains("\"generation\":1"))
        assertTrue(protocol.blur()!!.contains("\"type\":\"blur\""))
    }
}
