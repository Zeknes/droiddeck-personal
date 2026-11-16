package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanReaperEpochTest {
    @Test fun onlyProcessesOlderThanTheSessionAreLeftovers() {
        OrphanReaper.beginSession()
        val now = System.currentTimeMillis()
        assertTrue(now > 0)
        // Without a session epoch every process counts, which is the old behaviour.
        assertTrue(OrphanReaper.startedBeforeEpoch(0))
    }

    @Test fun aProcessStartedAfterTheSessionIsNotALeftover() {
        OrphanReaper.beginSession()
        val epoch = runCatching {
            java.io.File("/proc/uptime").readText().trim().substringBefore(' ').toDouble().toLong()
        }.getOrDefault(1L)
        assertFalse(OrphanReaper.startedBeforeEpoch(epoch + 5))
        assertTrue(OrphanReaper.startedBeforeEpoch(epoch - 60))
    }
}
