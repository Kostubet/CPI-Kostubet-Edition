package com.cosmod.launcherglass.util

import org.junit.Assert.*
import org.junit.Test

class SearchWidgetPolicyTest {
    @Test fun supportsLegacyCurrentAndPremiumGoogleSearch(){
        val pkg="com.google.android.googlequicksearchbox"
        assertTrue(SearchWidgetPolicy.googleSearch(pkg,"$pkg.SearchWidgetProvider"))
        assertTrue(SearchWidgetPolicy.googleSearch(pkg,"com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider"))
        assertTrue(SearchWidgetPolicy.googleSearch(pkg,"com.google.android.apps.gsa.staticplugins.searchwidget.PremiumSearchWidgetProvider"))
    }
    @Test fun leavesOtherGoogleWidgetsAndOtherProvidersAlone(){
        assertFalse(SearchWidgetPolicy.googleSearch("com.google.android.googlequicksearchbox","com.google.android.apps.gsa.staticplugins.smartspace.widget.SmartspaceWidgetProvider"))
        assertFalse(SearchWidgetPolicy.googleSearch("com.example","com.example.SearchWidgetProvider"))
        assertFalse(SearchWidgetPolicy.googleSearch(null,null))
    }
    @Test fun selectsPlateRatherThanFullCellProtection(){
        assertTrue(SearchWidgetPolicy.background("googleapp_search_widget_background_dark_mode"))
        assertFalse(SearchWidgetPolicy.background("googleapp_search_widget_background_protection"))
        assertFalse(SearchWidgetPolicy.background("googleapp_search_plate"))
    }
    @Test fun protectsControlsWithoutRestoringTheOpaquePlate(){
        assertTrue(SearchWidgetPolicy.control("googleapp_voice_search",48,48,320,48))
        assertFalse(SearchWidgetPolicy.control("googleapp_search_widget_background",320,48,320,160))
        assertFalse(SearchWidgetPolicy.control("googleapp_search_widget_background_protection",320,48,320,160))
        assertFalse(SearchWidgetPolicy.control("",320,48,320,48))
    }
}
