package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.Cardboard
import org.junit.Assert.*
import org.junit.Test

class GlassesRenderingTest {
    @Test fun modelTwoDRatesLimitChoicesAndSavedRatesFallBackToAnOfferedOne() {
        val rokid = GlassesState(displayId = 2, width = 1920, height = 1080, hz = 60f, rates2d = listOf(60, 120))
        assertEquals(listOf(60, 120), GlassesRendering.refreshRates(GlassesSettings(), rokid))
        assertEquals(60, GlassesRendering.outputHz(GlassesSettings(refreshHz = 90), rokid))
        assertEquals(120, GlassesRendering.outputHz(GlassesSettings(refreshHz = 120), rokid))
        assertEquals(listOf(60, 90, 120), GlassesRendering.refreshRates(GlassesSettings(), rokid.copy(rates2d = listOf(60, 90, 120))))
    }

    private val ultra = GlassesState(
        model = "VITURE Luma Ultra", connected = true, controllable = true,
        displayId = 2, width = 1920, height = 1200,
    )
    private val base = Cardboard(ipdMm = 67)

    @Test fun thirtyHzRequiresANativeModeAndDedicatedGlassesOutput() {
        val settings = GlassesSettings(refreshHz = 30, sbsHz = 30)
        for (stereo in listOf(false, true)) for (sbs90 in listOf(false, true)) {
            val state = ultra.copy(sbs = stereo, sbs90 = sbs90, supports30Hz = true)
            assertTrue(GlassesRendering.refreshRates(settings, state).contains(30))
            assertEquals(30, GlassesRendering.outputHz(settings, state))
            assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, state.copy(hz = 60f), false))
            assertEquals(30, GlassesRendering.mirrorRefreshLimit(settings, state.copy(hz = 29.97f), false))
            for (unavailable in listOf(state.copy(supports30Hz = false), state.copy(noSignal = true), state.copy(displayId = -1))) {
                assertFalse(GlassesRendering.refreshRates(settings, unavailable).contains(30))
                assertEquals(60, GlassesRendering.outputHz(settings, unavailable))
            }
            val mirrored = settings.copy(gameOnGlasses = false)
            assertFalse(GlassesRendering.refreshRates(mirrored, state).contains(30))
            assertEquals(60, GlassesRendering.outputHz(mirrored, state))
            assertEquals(60, GlassesRendering.mirrorRefreshLimit(mirrored, state, false))
        }
    }

    @Test fun mirroredRefreshUsesTheSelectedGlassesRateEvenBeforeTheDisplaySwitches() {
        val settings = GlassesSettings(gameOnGlasses = false, refreshHz = 60)
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(hz = 120f), false))
        assertEquals(90, GlassesRendering.mirrorRefreshLimit(settings.copy(refreshHz = 90), ultra, false))
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(connected = false), false))
    }

    @Test fun mirroredStereoUsesTheStereoRefreshAndItsHardwareLimit() {
        val settings = GlassesSettings(refreshHz = 120, sbsHz = 90)
        assertEquals(60, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(sbs = true), false))
        assertEquals(90, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(sbs = true, sbs90 = true), false))
    }

    @Test fun disconnectOrDedicatedOutputRemovesTheMirrorCap() {
        val settings = GlassesSettings(refreshHz = 60)
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, GlassesState(), false))
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, ultra.copy(noSignal = true), false))
        assertEquals(0, GlassesRendering.mirrorRefreshLimit(settings, ultra, true))
    }

    private fun render(settings: GlassesSettings, state: GlassesState = ultra) =
        GlassesRendering.cardboard(base, settings, state, outputActive = true)

    @Test fun automatic2dIsFlatSoVirtualScreenSettingsDoNotApply() {
        val settings = GlassesSettings(screenWidthDm = 40, screenDistanceDm = 80)
        assertEquals(GlassesRendering.Picture.FLAT, GlassesRendering.picture(settings, false))
        assertEquals(Cardboard.OFF, render(settings))
        assertEquals(Cardboard.OFF, render(settings.copy(screenWidthDm = 10, screenDistanceDm = 10)))
    }

    @Test fun selectingVirtualScreenMakesSizeDistanceAndTrackingReachTheRendererIn2d() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
            screenWidthDm = 40, screenDistanceDm = 80)
        val projection = render(settings)
        assertTrue(projection.enabled)
        assertTrue(projection.mono)
        assertTrue(projection.headTracking)
        assertEquals(Cardboard.SOURCE_SCREEN, projection.source)
        assertEquals(40, projection.screenWidthDm)
        assertEquals(80, projection.screenDistanceDm)
        assertEquals(67, projection.ipdMm)
        val changed = render(settings.copy(screenWidthDm = 15, screenDistanceDm = 20, headTracking = false))
        assertEquals(15, changed.screenWidthDm)
        assertEquals(20, changed.screenDistanceDm)
        assertFalse(changed.headTracking)
    }

    @Test fun automaticSbsDuplicatesTheWholeFrameWithoutEnablingHeadTracking() {
        val settings = GlassesSettings()
        val stereo = ultra.copy(sbs = true, width = 3840)
        assertEquals(GlassesRendering.Picture.DUPLICATED, GlassesRendering.picture(settings, true))
        assertEquals(Cardboard.SOURCE_FLAT, render(settings, stereo).source)
        assertFalse(render(settings, stereo).headTracking)
        assertFalse(render(settings, stereo).mono)
    }

    @Test fun flatSbsDoesNotDependOnTheTrackingPreferenceOrUsbControl() {
        for (tracking in listOf(false, true)) for (connected in listOf(false, true)) {
            for (height in listOf(1080, 1200)) {
                val state = ultra.copy(sbs = true, width = 3840, height = height, connected = connected)
                val settings = GlassesSettings(headTracking = tracking)
                val flat = render(settings, state)
                assertTrue(flat.enabled)
                assertFalse(flat.mono)
                assertFalse(flat.headTracking)
                assertEquals(Cardboard.SOURCE_FLAT, flat.source)
                assertEquals(Cardboard.SOURCE_SBS, render(settings.copy(picture3d = GlassesSettings.PICTURE_SBS), state).source)
            }
        }
    }

    @Test fun virtualScreenTracksFlatContentIndependentlyOfTheHardwareSbsMode() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN)
        assertTrue(settings.screen3dof)
        assertFalse(settings.copy(headTracking = false).screen3dof)
        for (height in listOf(1080, 1200)) {
            for (sbs in listOf(false, true)) {
                val projection = render(settings, ultra.copy(width = if (sbs) 3840 else 1920, height = height, sbs = sbs))
                assertTrue(projection.enabled)
                assertTrue(projection.headTracking)
                assertEquals(Cardboard.SOURCE_SCREEN, projection.source)
                assertEquals(!sbs, projection.mono)
            }
        }
    }

    @Test fun explicitPictureChoiceOverridesAutomaticResolution() {
        val virtual = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN)
        val direct = GlassesSettings(picture3d = GlassesSettings.PICTURE_SBS)
        for (stereo in listOf(false, true)) {
            assertEquals(GlassesRendering.Picture.VIRTUAL_SCREEN, GlassesRendering.picture(virtual, stereo))
            assertEquals(GlassesRendering.Picture.STEREO, GlassesRendering.picture(direct, stereo))
        }
    }

    @Test fun mirrorModeKeepsThePhoneProjectionInsteadOfApplyingDormantGlassesSettings() {
        val phone = base.copy(enabled = true, screenWidthDm = 60, screenDistanceDm = 20)
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN,
            gameOnGlasses = false, screenWidthDm = 10, screenDistanceDm = 50)
        assertSame(phone, GlassesRendering.cardboard(phone, settings, ultra, false))
    }

    @Test fun missingUsbControlOnlyDisablesTrackingNotScreenSize() {
        val settings = GlassesSettings(picture3d = GlassesSettings.PICTURE_SCREEN, screenWidthDm = 35)
        val projection = render(settings, ultra.copy(connected = false))
        assertTrue(projection.enabled)
        assertFalse(projection.headTracking)
        assertEquals(35, projection.screenWidthDm)
    }

    @Test fun outputActiveDecidesWhetherTheGlassesRender() {
        assertTrue(GlassesRendering.usesGlasses(ultra, true))
        assertFalse(GlassesRendering.usesGlasses(ultra, false))
        assertFalse(GlassesRendering.usesGlasses(GlassesState(), true))
    }
}
