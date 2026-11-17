package com.cosmod.launcherglass.util
import org.junit.Assert.*
import org.junit.Test
class WidgetPreviewKindTest {
    @Test fun nativeCaptionIdentifiesAnObfuscatedWeatherCard(){
        assertEquals(WidgetPreviewKind.WEATHER_SQUARE,WidgetPreviewKind.classify("a.b TitleCardView",false,caption="Текущая погода"))
    }
    @Test fun weatherMentionInAClockCaptionDoesNotSelectTheClock(){
        assertNull(WidgetPreviewKind.classify("com.oneplus.deskclock ClockCard",false,caption="Часы и погода"))
    }
    @Test fun weatherOwnedByTheClockPackageIsStillWeather(){
        assertEquals(WidgetPreviewKind.WEATHER_SQUARE,WidgetPreviewKind.classify(
            "com.oneplus.deskclock/com.coloros.widget.smallweather.AllTimeWeatherProvider",false))
    }
    @Test fun weatherComponentAndPlainClockRemainDistinct(){
        assertEquals(WidgetPreviewKind.WEATHER_WIDE,WidgetPreviewKind.classify(
            "com.oneplus.deskclock/com.oplus.widgets.WeatherWidgetProvider",true))
        assertNull(WidgetPreviewKind.classify("com.oneplus.deskclock/com.oplus.widgets.ClockWidgetProvider",false))
    }
    @Test fun onlyFiveRealFormsAreExposed(){
        assertEquals(5,WidgetPreviewKind.entries.size)
        assertNull(WidgetPreviewKind.classify("com.oplus.battery",true))
        assertEquals(WidgetPreviewKind.BATTERY,WidgetPreviewKind.classify("com.oplus.battery",false))
    }
    @Test fun recognizesNativeProvidersAndNeverSelectsAClock(){
        assertEquals(WidgetPreviewKind.WEATHER_WIDE,WidgetPreviewKind.classify("com.coloros.weather",true))
        assertEquals(WidgetPreviewKind.PHOTO_SQUARE,WidgetPreviewKind.classify("com.coloros.gallery3d",false))
        assertNull(WidgetPreviewKind.classify("com.oplus.clock.weather",false))
    }
    @Test fun removedBatteryRectangleMigratesToTheSquare(){
        assertEquals(WidgetPreviewKind.BATTERY,WidgetPreviewKind.fromLegacy(1))
        assertEquals(WidgetPreviewKind.PHOTO_SQUARE,WidgetPreviewKind.fromLegacy(2))
        assertEquals(WidgetPreviewKind.WEATHER_WIDE,WidgetPreviewKind.fromLegacy(5))
    }
}
