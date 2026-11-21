package com.cosmod.launcherglass.ui

import com.cosmod.launcherglass.ui.DesktopAppearance.Mode
import com.cosmod.launcherglass.ui.DesktopAppearance.Profile
import com.cosmod.launcherglass.ui.DesktopAppearance.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAppearanceTest {
    private val clear = Profile(Mode.CLEAR, Theme.LIGHT, hue = 40, saturation = 72)

    @Test
    fun darkModeUsesAlwaysOrAutomatic() {
        val dark = clear.selectMode(Mode.DARK)
        assertEquals(listOf(Theme.DARK, Theme.AUTO), dark.themeChoices)
        assertEquals(Theme.DARK, dark.theme)
        assertTrue(dark.iconDark(systemDark = false))
        assertFalse(dark.copy(theme = Theme.AUTO).iconDark(systemDark = false))
        assertTrue(dark.copy(theme = Theme.AUTO).iconDark(systemDark = true))
    }

    @Test
    fun lightClearStyleUsesSubtleNeutralLiftAndDarkKeepsTint() {
        val dark = clear.copy(theme = Theme.DARK)
        val auto = clear.copy(theme = Theme.AUTO)
        assertTrue(clear.usesLightGlass(systemDark = true))
        assertEquals(8, clear.clearOpacity(0, systemDark = true))
        assertEquals(8, clear.clearOpacity(100, systemDark = true))
        assertEquals(32, dark.clearOpacity(0, systemDark = false))
        assertEquals(54, dark.clearOpacity(100, systemDark = false))
        assertEquals(8, auto.clearOpacity(40, systemDark = false))
        assertEquals(40, auto.clearOpacity(40, systemDark = true))
        assertFalse(clear.selectMode(Mode.TINTED).usesLightGlass(systemDark = false))
    }

    @Test
    fun lightClearFadesWidgetContentWithoutIncreasingExistingOpacity() {
        val auto = clear.copy(theme = Theme.AUTO)
        assertEquals(32, clear.widgetContentOpacity(100, systemDark = true))
        assertEquals(15, clear.widgetContentOpacity(15, systemDark = true))
        assertEquals(32, auto.widgetContentOpacity(55, systemDark = false))
        assertEquals(55, auto.widgetContentOpacity(55, systemDark = true))
    }

    @Test
    fun colorTransparencyControlsOnlyTheTintAndPreservesLegacyDefaults() {
        val tinted = clear.selectMode(Mode.TINTED)
        assertEquals(40, tinted.tintOpacity(40))
        assertEquals(48, tinted.widgetTintTransparency(false))
        val transparent = tinted.copy(tintTransparency = 100)
        assertEquals(0, transparent.tintOpacity(40))
        assertEquals(100, transparent.widgetTintTransparency(true))
        assertEquals(100, transparent.selectMode(Mode.DARK).selectMode(Mode.TINTED).tintTransparency)
        assertEquals(0, transparent.selectMode(Mode.CLEAR).widgetTintTransparency(false))
    }

    @Test
    fun switchingStylesKeepsTheSelectedColor() {
        val stock = clear.selectMode(Mode.STOCK)
        assertTrue(stock.themeChoices.isEmpty())
        assertFalse(stock.hasTintControls)
        val tinted = stock.selectMode(Mode.TINTED)
        assertTrue(tinted.hasTintControls)
        assertEquals(listOf(Theme.LIGHT, Theme.DARK, Theme.AUTO), tinted.themeChoices)
        assertEquals(clear.copy(mode = Mode.TINTED), tinted)
    }
}
