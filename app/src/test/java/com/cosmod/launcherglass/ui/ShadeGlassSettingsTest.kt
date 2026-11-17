package com.cosmod.launcherglass.ui

import com.cosmod.launcherglass.ui.ShadeGlassSettings.Component
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Panel
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Parameter
import org.junit.Assert.*
import org.junit.Test

class ShadeGlassSettingsTest {
    @Test fun panelPreviewOnlyReadsKeysRegisteredForThatPanel() {
        val engineDefaults=GlassEngineSettings.integers
        for(panel in Panel.entries){
            val profile=GlassEngineSettings.read(panel.newEngine) {key,_->engineDefaults.getValue(key)}
            assertEquals(GlassEngineSettings.Optics(),profile)
        }
    }

    @Test fun backdropBrightnessAndBlurLevelAreStoredIndependently() {
        val scope=GlassEngineSettings.ICONS
        val brightness=GlassEngineSettings.Parameter.BACKDROP_BRIGHTNESS
        val strength=GlassEngineSettings.Parameter.BLUR_STRENGTH
        val values=GlassEngineSettings.integers + mapOf(
            GlassEngineSettings.key(scope,brightness) to 156,
            GlassEngineSettings.key(scope,strength) to 175
        )
        val icons=GlassEngineSettings.read(scope) {key,_->values.getValue(key)}
        val folders=GlassEngineSettings.read(GlassEngineSettings.FOLDERS) {key,_->values.getValue(key)}
        assertEquals(156,icons.backdropBrightness)
        assertEquals(20f,icons.blur.radius(20f),.001f)
        assertEquals(100,folders.backdropBrightness)
        assertEquals(20f,folders.blur.radius(20f),.001f)
    }

    @Test fun lensHeightDefaultsToOriginalAndIsIndependentPerSurface() {
        val height=GlassEngineSettings.Parameter.LENS_HEIGHT
        val scope=Panel.CONTROL.newEngine
        val key=GlassEngineSettings.key(scope,height)
        val values=GlassEngineSettings.integers + (key to 165)
        val control=GlassEngineSettings.read(scope) {name,_->values.getValue(name)}
        val notifications=GlassEngineSettings.read(Panel.NOTIFICATIONS.newEngine) {name,_->values.getValue(name)}
        assertEquals(1.65f,control.heightScale,.001f)
        assertEquals(1f,notifications.heightScale,.001f)
        assertEquals(200,OpticalSettings.maximum(key))
        assertTrue(key in ShadeGlassSettings.panelKeys(Panel.CONTROL))
    }

    @Test fun resettingOnePanelIncludesOnlyItsNewEngineOptics(){
        val power=GlassEngineSettings.Parameter.POWER
        val control=GlassEngineSettings.key(Panel.CONTROL.newEngine,power)
        val notifications=GlassEngineSettings.key(Panel.NOTIFICATIONS.newEngine,power)
        val keys=ShadeGlassSettings.panelKeys(Panel.CONTROL)
        assertTrue(control in keys)
        assertFalse(notifications in keys)
    }

    private fun profile(component:Component,enabled:Boolean=true,vararg settings:Pair<String,Int>) =
        (ShadeGlassSettings.integers + settings.toMap()).let { values ->
            ShadeGlassSettings.profile(component.panel,component,enabled,read=values::getValue)
        }

    @Test fun panelsDefaultOffAndUseIndependentKeys() {
        assertTrue(Panel.entries.none { ShadeGlassSettings.booleans.getValue(it.enabled) })
        assertTrue(Panel.entries.none { ShadeGlassSettings.booleans.getValue(it.highRefresh) })
        assertTrue(Panel.entries.none { ShadeGlassSettings.booleans.getValue(it.newEngine) })
        assertTrue(ShadeGlassSettings.panelKeys(Panel.CONTROL).intersect(
            ShadeGlassSettings.panelKeys(Panel.NOTIFICATIONS).toSet()).isEmpty())
        val custom=listOf(Panel.CONTROL.key(Parameter.RADIUS) to 37,
            Panel.NOTIFICATIONS.key(Parameter.RADIUS) to 9).toTypedArray()
        assertEquals(37,profile(Component.ROUND,true,*custom)[Parameter.RADIUS])
        assertEquals(9,profile(Component.COMPACT,true,*custom)[Parameter.RADIUS])
    }

    @Test fun componentInheritsOrOverridesOrUsesStock() {
        val radius=Panel.CONTROL.key(Parameter.RADIUS)
        val tile=Component.LARGE
        assertEquals(32,profile(tile,true,radius to 32,tile.radius to 54)[Parameter.RADIUS])
        val custom=profile(tile,true,radius to 32,tile.mode to 1,tile.radius to 54,tile.opacity to 0)
        assertEquals(54,custom[Parameter.RADIUS])
        assertEquals(0,custom[Parameter.OPACITY])
        assertEquals(32,profile(tile,true,radius to 32,tile.mode to 1,tile.radius to -1)[Parameter.RADIUS])
        assertFalse(profile(tile,true,tile.mode to 2).enabled)
        assertFalse(profile(tile,false,tile.mode to 1).enabled)
    }

    @Test fun allPortableValuesAndPresetsAreInRange() {
        ShadeGlassSettings.integers.forEach { (key,default)->
            assertTrue(default in checkNotNull(ShadeGlassSettings.range(key)))
            assertEquals(default,SettingsDefaults.portable()[key])
        }
        ShadeGlassSettings.Preset.entries.forEach { preset->
            preset.values().forEach { (key,value)->assertTrue(value in key.range) }
        }
    }

    @Test fun transparencyDoesNotChangeTheBlurRadiusAndIsIndependentPerSurface() {
        val key=GlassEngineSettings.blurOpacityKey(GlassEngineSettings.VOLUME)
        val values=mapOf(key to 40)
        val volume=GlassEngineSettings.read(GlassEngineSettings.VOLUME,values::getOrDefault).blur
        val icons=GlassEngineSettings.read(GlassEngineSettings.ICONS,values::getOrDefault).blur
        assertEquals(40,volume.opacity)
        assertEquals(100,icons.opacity)
        assertEquals(20f,volume.radius(20f),.001f)
        assertEquals(120,volume.cadence(60))
        assertEquals(0f,icons.copy(opacity=0).radius(20f),.001f)
    }

    @Test fun oldDisabledBlurDoesNotComeBackWhenImported() {
        val radius=SettingsProvider.KEY_BLUR_RADIUS
        val migrated=GlassEngineSettings.legacyRadii(mapOf(
            SettingsProvider.KEY_BLUR_RADIUS_ENABLED to false, radius to 25))
        assertEquals(0,migrated.getValue(radius))
    }

    @Test fun legacyEngineSelectionIsInheritedOnceWithoutOverridingPerSurfaceChoices() {
        val clock=LockscreenSettings.Surface.CLOCK.key("new_engine")
        val widgets=LockscreenSettings.Surface.WIDGETS.key("new_engine")
        val inherited=GlassEngineSettings.inheritLegacy(mapOf(
            GlassEngineSettings.LOCKSCREEN to true,GlassEngineSettings.FOLDERS to true,clock to false))
        assertTrue(inherited[widgets]==true)
        assertFalse(clock in inherited)
        assertTrue(inherited[GlassEngineSettings.LARGE_FOLDERS]==true)
        for((key,value) in GlassEngineSettings.integers)assertEquals(value,SettingsDefaults.portable()[key])
    }

    @Test fun controlBackgroundDoesNotInheritTileBlurRadiusOrOpacity() {
        val values=ShadeGlassSettings.integers + mapOf(
            Panel.CONTROL.key(Parameter.RADIUS) to 3,
            Panel.CONTROL.key(Parameter.OPACITY) to 70,
            Panel.CONTROL.key(Parameter.QUALITY) to 2,
            ShadeGlassSettings.CONTROL_BACKGROUND_RADIUS to 46,
            ShadeGlassSettings.CONTROL_BACKGROUND_DARKNESS to 17,
            ShadeGlassSettings.CONTROL_BACKGROUND_BRIGHTNESS to 132
        )
        val background=ShadeGlassSettings.controlBackground(true,highRefresh=true,read=values::getValue)
        assertEquals(132,background[Parameter.BRIGHTNESS])
        assertTrue(background.on(Parameter.HIGH_REFRESH))
        assertEquals(46,background[Parameter.RADIUS])
        assertEquals(17,background[Parameter.OPACITY])
        assertEquals(2,background[Parameter.QUALITY])
        assertFalse(background.on(Parameter.REFRACTION))
        assertFalse(ShadeGlassSettings.controlBackground(false,read=values::getValue).enabled)
        assertFalse(ShadeGlassSettings.CONTROL_BACKGROUND in ShadeGlassSettings.panelKeys(Panel.NOTIFICATIONS))
    }
}
