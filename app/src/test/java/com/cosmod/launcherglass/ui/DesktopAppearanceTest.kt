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
    fun transparentLightHidesThePlateAndTransparentDarkKeepsIt() {
        assertTrue(clear.hideIconPlate(systemDark = true))
        assertFalse(clear.copy(theme = Theme.DARK).hideIconPlate(systemDark = false))
        assertTrue(clear.copy(theme = Theme.AUTO).hideIconPlate(systemDark = false))
        assertFalse(clear.copy(theme = Theme.AUTO).hideIconPlate(systemDark = true))
        assertFalse(clear.selectMode(Mode.TINTED).hideIconPlate(systemDark = false))
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
