package com.cosmod.launcherglass.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSettingsTest {
    @Test fun everyRuntimeSurfaceHasPublishedDefaults() {
        for(surface in 0 until EdgeSettings.count) {
            for(field in EdgeSettings.booleanFields.keys) {
                assertTrue(EdgeSettings.key(surface,field) in EdgeSettings.booleans)
            }
            for(field in EdgeSettings.integerFields.keys) {
                assertTrue(EdgeSettings.key(surface,field) in EdgeSettings.integers)
            }
        }
        for(surface in LockscreenSettings.Surface.entries) {
            assertEquals("edge_lock_"+surface.id+"_enabled",EdgeSettings.key(surface.edgeSurface,"enabled"))
        }
        assertFalse("edge_lock_right_enabled" in EdgeSettings.booleans)
    }

    @Test fun oldRightShortcutCanStillSupplyTheSharedProfile() {
        val migrated=LockscreenSettings.sharedShortcutValues(mapOf(
            "lock_left_enabled" to false,
            "lock_right_enabled" to true,
            "edge_lock_right_strength" to 83
        ))
        assertEquals(true,migrated["lock_left_enabled"])
        assertEquals(83,migrated["edge_lock_left_strength"])
    }
}
