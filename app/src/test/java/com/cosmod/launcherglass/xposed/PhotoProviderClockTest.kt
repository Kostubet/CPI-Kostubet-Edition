package com.cosmod.launcherglass.xposed
import org.junit.Assert.*
import org.junit.Test
class PhotoProviderClockTest {
    @Test fun separateWeatherProvidersInsideDeskClockAreSupported(){
        assertFalse(PhotoProviders.isClock("com.oneplus.deskclock","com.coloros.widget.smallweather.AllTimeWeatherProvider"))
        assertFalse(PhotoProviders.isClock("com.oneplus.deskclock","com.oplus.widget.WeatherWidgetProvider"))
    }
    @Test fun realClockProvidersStayExcluded(){assertTrue(PhotoProviders.isClock("com.oneplus.deskclock","com.oplus.widget.ClockWidgetProvider"))}
    @Test fun clockWithWeatherStillRemainsAClock(){assertTrue(PhotoProviders.isClock("com.oneplus.deskclock","com.oplus.widget.ClockWeatherWidgetProvider"))}
}
