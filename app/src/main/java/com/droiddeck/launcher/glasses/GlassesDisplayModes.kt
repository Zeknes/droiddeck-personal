package com.droiddeck.launcher.glasses

data class GlassesResolution(val width: Int, val height: Int) {
    val key: String get() = "${width}x$height"
}

enum class GlassesDisplayProfile { TWO_D, THREE_D }

internal object GlassesDisplayModes {
    fun profile(stereo: Boolean): GlassesDisplayProfile =
        if (stereo) GlassesDisplayProfile.THREE_D else GlassesDisplayProfile.TWO_D

    fun choice(settings: GlassesSettings, profile: GlassesDisplayProfile): String = when (profile) {
        GlassesDisplayProfile.TWO_D -> settings.resolution2d
        GlassesDisplayProfile.THREE_D -> settings.resolution3d
    }

    fun selected(settings: GlassesSettings, state: GlassesState,
        stereo: Boolean = state.sbs): GlassesResolution? {
        val selected = choice(settings, profile(stereo))
        return state.resolutions.firstOrNull { it.key == selected }
    }
}
