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

    @Test fun theKernelNameIdentifiesTheGpuBeforeAnyGuess() {
        // kgsl's own name: "Adreno850v2" is the 850, and the shell's Adreno 840 rule then sees an 850.
        assertEquals("Adreno (TM) 850", DeviceSupport.gpuName("SM8975", "qcom", "Adreno850v2"))
        assertEquals("Adreno (TM) 840", DeviceSupport.gpuName("SM8850", "qcom", "Adreno (TM) 840"))
        // The Vulkan name, when the guest's driver has already reported one, ranks below the kernel and the chip.
        assertEquals("Adreno (TM) 830", DeviceSupport.gpuName("SM8850", "qcom", null, vulkanName = "Adreno (TM) 830"))
        assertEquals("Adreno (TM) 840", DeviceSupport.gpuName("SM8850", "qcom", "Adreno840v2",
            vulkanName = "Adreno (TM) 830"))
    }

    @Test fun aChipNamesItsGpuOnlyWhenThePlatformKnowsIt() {
        // This tablet's own fields: board "canoe", SM8850, qcom; the kernel says Adreno840v2 and the table agrees.
        assertEquals(840, com.droiddeck.launcher.gpu.GpuInfo.platformModel("canoe", "", "SM8850", "qcom"))
        assertEquals((840 to "kernel"), com.droiddeck.launcher.gpu.GpuInfo.modelFromNames("Adreno840v2", { null }, { 840 }))
        // SM8850 is the tablet's chip: the table records its Adreno 840, and an unknown chip maps to nothing.
        assertEquals(840, com.droiddeck.launcher.gpu.GpuInfo.platformModel("", "", "SM8850", "qcom"))
        assertEquals(0, com.droiddeck.launcher.gpu.GpuInfo.platformModel("", "", "not-a-chip", "qcom"))
    }

    @Test fun anUnnamedGpuKeepsItsSocInsteadOfAGuess() {
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", null))
        assertEquals("SM8850 (qcom)", DeviceSupport.gpuName("SM8850", "qcom", ""))
        assertEquals("qcom", DeviceSupport.gpuName(null, "qcom", null))
        assertEquals("this GPU", DeviceSupport.gpuName(android.os.Build.UNKNOWN, "", null))
    }
}
