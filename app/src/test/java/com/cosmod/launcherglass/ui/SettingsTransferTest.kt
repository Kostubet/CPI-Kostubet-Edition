package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {
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
}
