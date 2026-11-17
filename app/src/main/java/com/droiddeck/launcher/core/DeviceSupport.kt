package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

object DeviceSupport {
    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    fun adreno(): Boolean = adrenoGpu

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun graphicsAvailable(adreno: Boolean, powerVrReady: Boolean): Boolean = adreno || powerVrReady

    fun vkBridge(context: Context): Boolean =
        bridgeUsable() && !com.droiddeck.launcher.session.VenusComponent.wanted(context) &&
            com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun mali(): Boolean = isMali

    private val isMali by lazy {
        !adreno() && (File("/dev/mali0").exists() ||
            com.droiddeck.launcher.gpu.GpuInfo.detect().family.name.startsWith("MALI"))
    }

    fun bridgeUsable(): Boolean = !adreno() && !SystemVulkanDriver.isAvailable() && File("/dev/mali0").exists()

    fun openDriverActive(context: Context): Boolean {
        if (adreno()) return false
        if (com.droiddeck.launcher.session.VenusComponent.wanted(context)) return false
        if (bridgeUsable() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)) return false
        val linux = com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context).ifEmpty { return false }
        return com.droiddeck.launcher.gpu.DriverPairs.isNonAdreno(com.droiddeck.launcher.gpu.DriverBundle.containing(context, linux, true)?.id)
    }

    fun canDraw(context: Context): Boolean = graphicsAvailable() || openDriverActive(context) || vkBridge(context) ||
        com.droiddeck.launcher.session.VenusComponent.wanted(context)

    fun gpuName(context: Context): String = gpuName(
        if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null,
        Build.HARDWARE, gpuRenderer(), platformModel(), vulkanName(), context.getString(R.string.gpu_this_gpu),
    )

    /**
     * The GPU name the guest's shell rules read ("Adreno (TM) 840"), or the SoC when nothing names the GPU.
     * Only what this process can read identifies the GPU: the kernel's kgsl name, the model the chip's board
     * or codename is known by, and the Vulkan name when the guest's driver has already reported it. A GL call
     * is not one of them, because this process has no GL context.
     */
    internal fun gpuName(soc: String?, hardware: String, kgslName: String?, platformModel: Int = 0,
                         vulkanName: String? = null, fallback: String = "this GPU"): String {
        val model = GpuInfo.threeDigits(kgslName.orEmpty()).takeIf { it > 0 }
            ?: platformModel.takeIf { it > 0 }
            ?: vulkanModel(vulkanName)
        if (model > 0) return "Adreno (TM) $model"
        val known = soc?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
        return if (known == null) hardware.ifBlank { fallback } else "$known (${hardware.ifBlank { fallback }})"
    }

    internal fun vulkanModel(vulkanName: String?): Int = GpuInfo.threeDigits(vulkanName.orEmpty())

    private fun gpuRenderer(): String? = runCatching {
        File("/sys/class/kgsl/kgsl-3d0/gpu_model").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun platformModel(): Int = runCatching { GpuInfo.platformModel(hardware = Build.HARDWARE) }.getOrDefault(0)

    private fun vulkanName(): String? = runCatching { com.droiddeck.launcher.gpu.VulkanInfo.cachedOrNull()?.get("device") }.getOrNull()

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
