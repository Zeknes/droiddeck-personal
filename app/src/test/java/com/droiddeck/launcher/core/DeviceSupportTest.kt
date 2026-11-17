package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.os.Build
import org.junit.Test

class DeviceSupportTest {
    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, powerVrReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = false))
    }

    @Test fun gpuNameCarriesTheAdrenoModelTheShellRulesMatch() {
        // ROG Phone 9 Pro reports SOC_MODEL SM8850 and HARDWARE qcom; only the GL renderer names the Adreno 840.
        assertEquals("Adreno (TM) 840",
            DeviceSupport.gpuName("SM8850", "qcom", " Qualcomm, Adreno (TM) 840, OpenGL ES 3.2 V@0842.44.3", "This GPU"))
    }

    @Test fun gpuNameFallsBackToTheSoCWhenNoRendererIsAvailable() {
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", null, "This GPU"))
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", "  ", "This GPU"))
        assertEquals("qcom", DeviceSupport.gpuName(null, "qcom", null, "This GPU"))
        assertEquals("This GPU", DeviceSupport.gpuName(Build.UNKNOWN, "", null, "This GPU"))
    }
}
