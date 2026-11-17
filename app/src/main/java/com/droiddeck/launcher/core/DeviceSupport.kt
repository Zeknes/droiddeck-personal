package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
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
        Build.HARDWARE, gpuRenderer(), context.getString(R.string.gpu_this_gpu),
    )

    internal fun gpuName(soc: String?, hardware: String, glRenderer: String?, fallback: String): String {
        // The Mobile shell and KWin rules key off the Adreno model, which neither SOC_MODEL ("SM8850") nor
        // HARDWARE ("qcom") carries; the renderer "Qualcomm, Adreno (TM) 840, OpenGL ES ..." does.
        val renderer = glRenderer?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("Adreno") }.orEmpty()
        if (renderer.isNotEmpty()) return renderer
        val known = soc?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
        return if (known == null) hardware.ifBlank { fallback } else "$known (${hardware.ifBlank { fallback }})"
    }

    // The GL renderer as SurfaceFlinger reports it: this process has no GL context, and glGetString without one aborts it.
    private fun gpuRenderer(): String? = runCatching {
        val process = ProcessBuilder("dumpsys", "SurfaceFlinger").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.lineSequence().firstOrNull { it.trimStart().startsWith("GLES:") }
    }.getOrNull()

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
