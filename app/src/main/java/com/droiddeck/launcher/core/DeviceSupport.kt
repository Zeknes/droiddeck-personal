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
        Build.HARDWARE, gpuRenderer(), context.getString(R.string.gpu_this_gpu), GpuInfo.platformModel(hardware = Build.HARDWARE),
    )

    internal fun gpuName(soc: String?, hardware: String, glRenderer: String?, fallback: String, platformModel: Int = 0): String {
        // The Mobile shell and KWin rules key off the Adreno model, which neither SOC_MODEL ("SM8850") nor
        // HARDWARE ("qcom") carries; the GL name or the chip's known model does.
        val parts = glRenderer?.split(',')?.map { it.trim() }.orEmpty()
        // The GL name ("Qualcomm, Adreno (TM) 840, ...") or the kernel's ("Adreno830v2") both read as one label.
        val model = GpuInfo.threeDigits(parts.firstOrNull { it.startsWith("Adreno") }.orEmpty()).takeIf { it > 0 }
            ?: platformModel.takeIf { it > 0 }
        if (model != null) return "Adreno (TM) $model"
        val known = soc?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
        return if (known == null) hardware.ifBlank { fallback } else "$known (${hardware.ifBlank { fallback }})"
    }

    // This process has no GL context and glGetString without one aborts it, so the GL name comes from the
    // kernel (kgsl) or from the platform the chip and its codename are known by instead.
    private fun gpuRenderer(): String? = runCatching {
        File("/sys/class/kgsl/kgsl-3d0/gpu_model").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
