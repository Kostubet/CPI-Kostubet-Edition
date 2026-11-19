package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {
    @Test fun softLightProfilesHaveIndependentPortableControls() {
        val defaults = SettingsDefaults.portable()
        val surfaces = SoftGlassSettings.Surface.entries
        assertEquals(surfaces.size, surfaces.map { it.enabled }.toSet().size)
        for (surface in surfaces) {
            assertEquals(false, defaults[surface.enabled])
            val exposure = surface.key(SoftGlassSettings.Parameter.EXPOSURE)
            assertEquals(0, defaults[exposure])
            assertEquals(-100L..100L, SettingsTransfer.range(exposure))
        }
    }

    @Test fun cameraTuningIsPortableButActivationAndLoggingRequireLocalOptIn() {
        val zoom = com.cosmod.launcherglass.camera.CameraZoomSettings
        val defaults = SettingsDefaults.portable()
        assertTrue(zoom.ENABLED !in defaults && zoom.DIAGNOSTICS !in defaults)
        assertTrue(zoom.MAX_SPEED in defaults)
        assertEquals(15L..400L, SettingsTransfer.range(zoom.MAX_SPEED))
    }

    @Test fun everyPortableIntegerDefaultPassesImportValidation() {
        SettingsDefaults.portable().forEach { (key, value) ->
            if (value is Int) {
                val portable = SettingsTransfer.legacyNumber(key, value.toLong())
                assertTrue("$key=$portable is outside the import range", portable in SettingsTransfer.range(key))
            }
        }
    }

    @Test fun dockBlurMigratesOldIconRadiusWithoutAcceptingArbitraryValues() {
        val dock = SettingsProvider.KEY_DOCK_BLUR
        assertEquals(40L, SettingsTransfer.legacyNumber(dock, 49))
        assertEquals(39L, SettingsTransfer.legacyNumber(dock, 39))
        assertTrue(SettingsTransfer.legacyNumber(dock, 51) !in SettingsTransfer.range(dock))
        assertEquals(49L, SettingsTransfer.legacyNumber(SettingsProvider.KEY_BLUR_RADIUS, 49))
    }

    @Test fun kyantMaterialFromOldConfigsConvertsToTheSeparateEngineSwitch() {
        val glass = GlassEngineSettings
        val style = SettingsProvider.KEY_BLUR_STYLE
        val width = glass.key(glass.ICONS, GlassEngineSettings.Parameter.EDGE_WIDTH)
        val power = glass.key(glass.ICONS, GlassEngineSettings.Parameter.POWER)
        val light = glass.key(glass.ICONS, GlassEngineSettings.Parameter.EDGE_LIGHT)
        val old = mapOf<String, Any>(style to 3, "kyant_width" to 68,
            "kyant_distortion" to 79, "kyant_highlight" to 30L, light to 44)
        val values = old + glass.legacyIconMaterial(old)
        assertEquals(SettingsProvider.BLUR_STYLE_LIQUID, values[style])
        assertEquals(true, values[glass.ICONS])
        assertEquals(68, values[width])
        assertEquals(79, values[power])
        assertEquals(44, values[light])
        assertTrue(glass.legacyIconMaterial(values).isEmpty())
        assertTrue(glass.legacyIconMaterial(mapOf(style to "3")).isEmpty())
    }
}
