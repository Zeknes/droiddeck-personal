package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, powerVrReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = false))
    }

    @Test fun gpuNameCarriesTheAdrenoModelTheShellRulesMatch() {
        // ROG Phone 9 Pro reports SOC_MODEL SM8850 and HARDWARE qcom; only the GL renderer names the Adreno model.
        assertEquals("Adreno (TM) 840",
            DeviceSupport.gpuName("SM8850", "qcom", "Qualcomm, Adreno (TM) 840, OpenGL ES 3.2 V@0842.44.3", "This GPU"))
        // Where the platform knows the chip's GPU (ROG Phone 9 Pro: platform "canoe" is the Adreno 830), that names it too.
        assertEquals("Adreno (TM) 830", DeviceSupport.gpuName("SM8850", "canoe", null, "This GPU", platformModel = 830))
        // A kernel name ("Adreno830v2") is the same label the shell rules read for the model.
        assertEquals("Adreno (TM) 830", DeviceSupport.gpuName("SM8850", "canoe", "Adreno830v2", "This GPU"))
    }

    @Test fun gpuNameFallsBackToTheSoCWhenNoRendererIsAvailable() {
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", null, "This GPU"))
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", "  ", "This GPU"))
        assertEquals("qcom", DeviceSupport.gpuName(null, "qcom", null, "This GPU"))
        assertEquals("This GPU", DeviceSupport.gpuName(android.os.Build.UNKNOWN, "", null, "This GPU"))
    }
}
