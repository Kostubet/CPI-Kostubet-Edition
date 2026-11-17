package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class WidgetTintPolicyTest {
    @Test fun excludesLargeBackgroundImage(){assertFalse(WidgetTintPolicy.logo(400,200,400,200))}
    @Test fun acceptsSmallWeatherOrBatteryLogo(){assertTrue(WidgetTintPolicy.logo(48,48,400,200))}
    @Test fun excludesUnlaidOutContent(){assertFalse(WidgetTintPolicy.logo(0,0,400,200));assertFalse(WidgetTintPolicy.logo(48,48,0,0))}
    @Test fun excludesFullWidthOrHeightBackgroundStrips(){
        assertFalse(WidgetTintPolicy.logo(400,40,400,200))
        assertFalse(WidgetTintPolicy.logo(40,200,400,200))
    }
    @Test fun excludesNamedBackgroundsEvenWhenSmall(){
        for(name in listOf("weather_background","small_bg","photo_thumbnail","widget_cover"))
            assertFalse(name,WidgetTintPolicy.logo(48,48,400,200,name))
        assertTrue(WidgetTintPolicy.logo(48,48,400,200,"weather_icon"))
    }
}
