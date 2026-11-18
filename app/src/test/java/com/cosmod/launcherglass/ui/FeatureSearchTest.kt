package com.cosmod.launcherglass.ui
import com.cosmod.launcherglass.R
import org.junit.Assert.*
import org.junit.Test
class FeatureSearchTest {
    @Test fun everyFeatureSectionIsIndexed(){
        for(section in FeatureSections.sections)assertTrue("Missing $section",FeatureSearch.labels(section).isNotEmpty())
    }
    @Test fun indexesNativePreviewSelectorsAndCurrentMotionOptions(){
        assertTrue(R.string.ui_preview_widget in FeatureSearch.labels("PHOTO"))
        assertTrue(R.string.ui_preview_style in FeatureSearch.labels("BANNERS"))
        val motion=FeatureSearch.labels("MOTION")
        assertTrue(R.string.ui_genie_trajectory in motion)
        assertTrue(R.string.ui_ios_tilt_strength in motion)
        assertTrue(R.string.ui_ios_deformation_strength in motion)
        assertTrue(FeatureSearch.labels("BA31K_MOTION").isEmpty())
        assertTrue(R.string.ui_folder_icons_blur in FeatureSearch.labels("FOLDERS"))
    }
    @Test fun iconsIndexComesFromTheFeatureRatherThanThePreview(){
        val icons=FeatureSearch.labels("ICONS")
        assertTrue(R.string.ui_icon_size in icons)
        assertTrue(R.string.glass_engine_power in icons)
        assertFalse(R.string.ui_preview_widget in icons)
        assertFalse(R.string.ui_preview_style in icons)
        assertFalse(R.string.ui_glass_preview in FeatureSearch.labels("PERFORMANCE"))
    }

    @Test fun findsIndividualSettingsInEitherLanguage(){
        val icons=FeatureSearch.Entry(
            listOf("Иконки","Icons"),listOf("значки рабочий стол"),
            listOf(1 to listOf("Размер иконок лаунчера","Launcher icon size"),
                2 to listOf("Стекло до 120 к/с","Glass at up to 120 FPS")))
        assertEquals(1,FeatureSearch.Query("launcher icon size").match(icons)?.label)
        assertEquals(1,FeatureSearch.Query("иконки размер").match(icons)?.label)
        assertEquals(2,FeatureSearch.Query("120fps").match(icons)?.label)
        assertNull(FeatureSearch.Query("размер 120fps").match(icons))
        assertNull(FeatureSearch.Query("иконки").match(icons)?.label)
    }
    @Test fun lockscreenUsesTheLabelsFromItsSharedProfileDefinitions(){
        assertTrue(R.string.lock_glass_clock in FeatureSearch.labels("LOCKSCREEN"))
        assertTrue(R.string.lock_glass_widgets in FeatureSearch.labels("LOCKSCREEN"))
    }
    @Test fun shadeSearchIndexesBothPanelsAndMaterialOptions(){
        val labels=FeatureSearch.labels("SHADE")
        assertTrue(R.string.shade_glass_control in labels)
        assertTrue(R.string.shade_glass_notifications in labels)
        assertTrue(R.string.glass_blur_group in labels)
        assertTrue(R.string.shade_glass_blur_opacity in labels)
        assertTrue(R.string.shade_glass_dispersion_strength in labels)
        assertTrue(R.string.shade_glass_control_background in labels)
    }
}
