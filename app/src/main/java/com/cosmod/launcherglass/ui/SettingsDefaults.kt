package com.cosmod.launcherglass.ui

/** Defaults for absent keys only. Existing installations retain their saved values. */
object SettingsDefaults {
    @Volatile private var factory: Map<String, Any>? = null

    @Synchronized
    fun initialize(context: android.content.Context, prefs: android.content.SharedPreferences) {
        if (factory == null) {
            factory = runCatching {
                context.assets.open("factory-settings.json").use {
                    SettingsTransfer.readDocument(it, acceptOutdated = true).values
                }
            }.onFailure { android.util.Log.e("CPI.Settings", "Factory settings unavailable", it) }.getOrDefault(emptyMap())
        }
        EditionMigration.copy(context,prefs)
        val hadSavedValues = prefs.all.isNotEmpty()
        if(!prefs.getBoolean("island_view_discovery_v2",false))prefs.edit()
            .putBoolean("island_glass_enabled",false).putBoolean("island_view_discovery_v2",true).commit()
        val removed=listOf("refraction_before_blur","folder_refraction_before_blur","photo_widget_refraction_before_blur")
        if(removed.any(prefs::contains))prefs.edit().apply {removed.forEach {remove(it)}}.apply()
        if(prefs.contains("blur_dither") || prefs.contains("blur_dither_strength"))prefs.edit().apply {
            if(!prefs.contains(SettingsProvider.KEY_COLOR_DEPTH_ENABLED))putBoolean(SettingsProvider.KEY_COLOR_DEPTH_ENABLED,prefs.getBoolean("blur_dither",false))
            remove("blur_dither");remove("blur_dither_strength")
        }.apply()
        if(!prefs.getBoolean("neutral_dock_revision_1",false))prefs.edit()
            .putInt(SettingsProvider.KEY_DOCK_LIQUID_DISTORTION,80)
            .putBoolean("neutral_dock_revision_1",true).apply()
        if(!prefs.getBoolean("icon_dock_revision_1",false))prefs.edit()
            .putInt(SettingsProvider.KEY_DOCK_LIQUID_DISTORTION,prefs.getInt(SettingsProvider.KEY_LIQUID_DISTORTION,50))
            .putInt(SettingsProvider.KEY_DOCK_LIQUID_WIDTH,prefs.getInt(SettingsProvider.KEY_LIQUID_WIDTH,40))
            .putInt(SettingsProvider.KEY_DOCK_BLUR,prefs.getInt(SettingsProvider.KEY_BLUR_RADIUS,10).coerceIn(0,40))
            .putBoolean("icon_dock_revision_1",true).apply()
        initializePhoto(prefs)
        GlassDispersionSettings.migrate(prefs)
        EdgeSettings.migrate(prefs)
        LockscreenSettings.migrateShortcuts(prefs)
        if(!prefs.getBoolean("glass_engine_type_revision",false))prefs.edit().apply {
            GlassEngineSettings.inheritLegacy(prefs.all).forEach { (key,value)->putBoolean(key,value) }
            putBoolean("glass_engine_type_revision",true)
        }.apply()
        WidgetSettings.migrate(prefs)
        val motion=com.cosmod.launcherglass.util.Ba31kAnimationSettings
        if(prefs.contains("app_tilt_enabled"))prefs.edit().apply {
            motion.migrateClassic(prefs.all).forEach { (key,value)->
                when(value){is Boolean->putBoolean(key,value);is Int->putInt(key,value)}
            }
            motion.classicKeys.forEach {remove(it)}
        }.apply()
        if(!prefs.getBoolean("glass_blur_controls_revision",false))prefs.edit().apply {
            GlassEngineSettings.legacyRadii(prefs.all).forEach { (key,value)->putInt(key,value) }
            putBoolean("glass_blur_controls_revision",true)
        }.apply()
        if (prefs.getInt("defaults_revision", 0) >= 38) {
            initializeColors(prefs); initializeTone(prefs)
            applyFactoryDefaults(prefs, fresh = false)
            migrateShadeMaterial(prefs)
            return
        }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val upgrade = info.firstInstallTime != info.lastUpdateTime || hadSavedValues
        val editor = prefs.edit()
        if (upgrade) {
            // Preserve legacy appearance defaults for absent keys. A missing blur
            // mode uses Live; explicitly saved choices are never overwritten.
            val oldBooleans = booleans + mapOf(SettingsProvider.KEY_ENABLED to true,
                SettingsProvider.KEY_FOLDER_BLUR_ENABLED to true,
                SettingsProvider.KEY_DRAWER_BLUR_ENABLED to true)
            val oldIntegers = mapOf(SettingsProvider.KEY_BG_OPACITY to 35,
                SettingsProvider.KEY_BLUR_RADIUS to 25, SettingsProvider.KEY_BLUR_MODE to SettingsProvider.BLUR_MODE_LIVE,
                SettingsProvider.KEY_LIVE_FPS to 30, SettingsProvider.KEY_STROKE_OPACITY to 70,
                SettingsProvider.KEY_GRADIENT_OPACITY to 60)
            oldBooleans.forEach { (key, value) -> if (!prefs.contains(key)) editor.putBoolean(key, value) }
            oldIntegers.forEach { (key, value) -> if (!prefs.contains(key)) editor.putInt(key, value) }
        }
        editor.remove("force_themed").putInt("defaults_revision", 38).apply()
        initializeColors(prefs)
        initializeTone(prefs)
        applyFactoryDefaults(prefs, fresh = !upgrade)
        migrateShadeMaterial(prefs)
    }

    private fun migrateShadeMaterial(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("soft_glass_shade_revision", false)) return
        prefs.edit().apply {
            for ((panel, surface) in listOf(ShadeGlassSettings.Panel.CONTROL to SoftGlassSettings.Surface.CONTROL,
                ShadeGlassSettings.Panel.NOTIFICATIONS to SoftGlassSettings.Surface.SHADE)) {
                if (!prefs.contains(surface.enabled) && prefs.getBoolean(panel.enabled, false)) {
                    putBoolean(surface.enabled, true)
                }
            }
            putBoolean("soft_glass_shade_revision", true)
        }.apply()
    }

    private fun applyFactoryDefaults(prefs: android.content.SharedPreferences, fresh: Boolean) {
        if (prefs.getBoolean("factory_profile_422", false)) return
        val values = factory.orEmpty()
        if (values.isEmpty()) return
        val editor = prefs.edit()
        for ((key, value) in values) {
            if (!fresh && (prefs.contains(key) || SettingsTransfer.isOptionalFolder(key))) continue
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        editor.putBoolean("factory_profile_422", true).apply()
    }
    private fun initializePhoto(prefs:android.content.SharedPreferences){
        val editor=prefs.edit()
        for((key,base) in listOf(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH to SettingsProvider.KEY_LIQUID_WIDTH,
            SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION to SettingsProvider.KEY_LIQUID_DISTORTION)){
            if(!prefs.contains(key))editor.putInt(key,prefs.getInt(base,integer(base)))
        }
        editor.apply()
    }
    private fun initializeTone(prefs:android.content.SharedPreferences) {
        if(prefs.getBoolean("tone_group_migrated",false))return
        val editor=prefs.edit()
        // Preserve the effective appearance of legacy individual toggles.
        val opacity=prefs.getBoolean("bg_opacity_enabled",true)
        val saturation=prefs.getBoolean("blur_saturation_enabled",false)
        val brightness=prefs.getBoolean("blur_lightening_enabled",false)
        if(!prefs.contains(SettingsProvider.KEY_BLUR_TONE_ENABLED))editor.putBoolean(SettingsProvider.KEY_BLUR_TONE_ENABLED,opacity || saturation || brightness)
        if(!opacity)editor.putInt(SettingsProvider.KEY_BG_OPACITY,0)
        if(!saturation)editor.putInt(SettingsProvider.KEY_BLUR_SATURATION,100)
        if(!brightness)editor.putInt(SettingsProvider.KEY_BLUR_LIGHTENING,50)
        editor.remove("bg_opacity_enabled").remove("blur_saturation_enabled").remove("blur_lightening_enabled")
            .putBoolean("tone_group_migrated",true).apply()
    }
    private fun initializeBlurBrightness(prefs: android.content.SharedPreferences) {
        if (prefs.getInt("blur_brightness_revision", 0) >= 1) return
        val editor = prefs.edit()
        if (prefs.contains(SettingsProvider.KEY_BLUR_LIGHTENING)) {
            val old = prefs.getInt(SettingsProvider.KEY_BLUR_LIGHTENING,0).coerceIn(0,100)
            editor.putInt(SettingsProvider.KEY_BLUR_LIGHTENING,50+(old+1)/2)
        }
        editor.putInt("blur_brightness_revision",1).apply()
    }
    private fun initializeColors(prefs: android.content.SharedPreferences) {
        initializeBlurBrightness(prefs)
        if(prefs.getInt("outline_sliders_revision",0)<1) {
            val editor=prefs.edit()
            if(!prefs.getBoolean("glyph_stroke_width_enabled",false))editor.putInt(SettingsProvider.KEY_GLYPH_STROKE_WIDTH,35)
            if(!prefs.getBoolean("glyph_stroke_brightness_enabled",false))editor.putInt(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,45)
            editor.remove("glyph_stroke_width_enabled").remove("glyph_stroke_brightness_enabled")
                .putInt("outline_sliders_revision",1).apply()
        }
        if (prefs.getInt("color_profiles_revision", 0) >= 1) {
            if (prefs.contains("color_theme")) prefs.edit().remove("color_theme").apply()
            return
        }
        val activeDark = prefs.getInt("color_theme", 1) == 1
        val legacy = listOf(SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_COLOR_SATURATION, SettingsProvider.KEY_COLOR_BRIGHTNESS)
        val editor = prefs.edit()
        listOf(false, true).forEach { dark ->
            SettingsProvider.colorKeys(dark).forEachIndexed { index, key ->
                if (!prefs.contains(key)) {
                    val value = if (prefs.contains(legacy[index]) && (index != 2 || dark == activeDark))
                        prefs.getInt(legacy[index], integer(key)) else integer(key)
                    editor.putInt(key, value)
                }
            }
        }
        editor.remove("color_theme").putInt("color_profiles_revision", 1).apply()
    }
    private val booleans = OpticalSettings.booleans + com.cosmod.launcherglass.camera.CameraZoomSettings.booleans + mapOf(
        SettingsProvider.KEY_BOOT_RESTART to true,
        SettingsProvider.KEY_BOOT_RESTART_TOAST to true,
        SettingsProvider.KEY_DEVELOPER_MODE to false,
        SettingsProvider.KEY_GPU_EFFECTS to true,
        SettingsProvider.KEY_COLOR_DEPTH_ENABLED to false,
        WallpaperGlowSettings.ENABLED to true,
        SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION to true,
        SettingsProvider.KEY_OPTIMIZE_MINI_ICONS to true,
        // Experimental and untested on devices: explicit developer opt-in only.
        SettingsProvider.KEY_MD3_UI to false,
        SettingsProvider.KEY_PHOTO_WIDGET_BLUR to false,
        SettingsProvider.KEY_PHOTO_WIDGET_TINT to false,
        SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED to true,
        SettingsProvider.KEY_BLUR_RADIUS_ENABLED to true,
        SettingsProvider.KEY_BLUR_TONE_ENABLED to true,
        SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED to true,
        SettingsProvider.KEY_GLYPH_STROKE to false,
        SettingsProvider.KEY_ENABLED to false,
        SettingsProvider.KEY_BLUR_ENABLED to true,
        SettingsProvider.KEY_FOLDER_BLUR_ENABLED to false,
        SettingsProvider.KEY_DRAWER_BLUR_ENABLED to false,
        SettingsProvider.KEY_IDLE_PAUSE to true,
        SettingsProvider.KEY_ICON_STROKE to true,
        SettingsProvider.KEY_DESIGN_ENABLED to true,
        SettingsProvider.KEY_VOLUME_ENABLED to true
    )
    private val integers = OpticalSettings.integers + WallpaperGlowSettings.defaults + com.cosmod.launcherglass.camera.CameraZoomSettings.integers + mapOf(
        SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL to 1,
        SettingsProvider.KEY_DOCK_BLUR to 14,

        SettingsProvider.KEY_PHOTO_WIDGET_STYLE to 0,
        SettingsProvider.KEY_PHOTO_WIDGET_CORNER to 28,
        SettingsProvider.KEY_PHOTO_WIDGET_SHADE to 35,
        SettingsProvider.KEY_PHOTO_WIDGET_GLOW to 40,
        SettingsProvider.KEY_PHOTO_WIDGET_RIM to 35,
        SettingsProvider.KEY_DOCK_STYLE to 0,
        SettingsProvider.KEY_DOCK_OPACITY to 40,
        SettingsProvider.KEY_DOCK_CORNER to 45,
        SettingsProvider.KEY_DOCK_INSET to 8,
        SettingsProvider.KEY_DOCK_LIQUID_WIDTH to 40,
        SettingsProvider.KEY_DOCK_LIQUID_DISTORTION to 50,

        SettingsProvider.KEY_COLOR_DEPTH_BITS to 8,
        SettingsProvider.KEY_COLOR_DEPTH_STAGE to 3,
        SettingsProvider.KEY_BLUR_STYLE to SettingsProvider.BLUR_STYLE_FROSTED,
        SettingsProvider.KEY_BLUR_QUALITY to 0,
        SettingsProvider.KEY_LIQUID_WIDTH to 40,
        SettingsProvider.KEY_LIQUID_DISTORTION to 50,
        SettingsProvider.KEY_GLYPH_STROKE_WIDTH to 35,
        SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS to 45,
        SettingsProvider.KEY_GLYPH_STROKE_OPACITY to 40,
        SettingsProvider.KEY_BLUR_VIBRANCY to 0,
        SettingsProvider.KEY_PHOTO_WIDGET_OPACITY to 55,
        SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH to 40,
        SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION to 50,
        SettingsProvider.KEY_BLUR_SATURATION to 100,
        SettingsProvider.KEY_BLUR_LIGHTENING to 50,
        SettingsProvider.KEY_ICON_APPEARANCE to 1,
        SettingsProvider.KEY_CLEAR_BRIGHTNESS to 70,
        SettingsProvider.KEY_LIGHT_HUE to 30,
        SettingsProvider.KEY_LIGHT_SATURATION to 25,
        SettingsProvider.KEY_LIGHT_BRIGHTNESS to 85,
        SettingsProvider.KEY_DARK_HUE to 30,
        SettingsProvider.KEY_DARK_SATURATION to 25,
        SettingsProvider.KEY_DARK_BRIGHTNESS to 30,
        SettingsProvider.KEY_COLOR_MODE to 0,
        SettingsProvider.KEY_COLOR_HUE to 30,
        SettingsProvider.KEY_COLOR_SATURATION to 25,
        SettingsProvider.KEY_COLOR_BRIGHTNESS to 30,
        SettingsProvider.KEY_BG_OPACITY to 40,
        SettingsProvider.KEY_BLUR_RADIUS to 10,
        SettingsProvider.KEY_BLUR_MODE to SettingsProvider.BLUR_MODE_LIVE,
        SettingsProvider.KEY_LIVE_FPS to 60,
        SettingsProvider.KEY_STROKE_OPACITY to 40,
        SettingsProvider.KEY_GRADIENT_OPACITY to 50
    )
    /** Only portable module preferences. Device reports, language and migration keys stay local. */
    internal fun schema(): Map<String, Any> = ((booleans - SettingsProvider.KEY_BOOT_RESTART - SettingsProvider.KEY_BOOT_RESTART_TOAST - SettingsProvider.KEY_MD3_UI) + integers + mapOf(FolderSettingsKeys.OWN to false) +
        FolderSettingsKeys.booleans.associate { FolderSettingsKeys.key(it) to booleans.getValue(it) } +
        FolderSettingsKeys.integers.associate { FolderSettingsKeys.key(it) to integers.getValue(it) }) - retiredBlurKeys -
        com.cosmod.launcherglass.camera.CameraZoomSettings.ENABLED - com.cosmod.launcherglass.camera.CameraZoomSettings.DIAGNOSTICS

    fun portable(): Map<String, Any> = schema() + factory.orEmpty()
    internal fun hasFactoryValue(key: String) = factory?.containsKey(key) == true

    private val retiredBlurKeys = setOf(SettingsProvider.KEY_BLUR_RADIUS_ENABLED, SettingsProvider.KEY_BLUR_MODE,
        SettingsProvider.KEY_BLUR_QUALITY, SettingsProvider.KEY_LIVE_FPS, SettingsProvider.KEY_IDLE_PAUSE,
        FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED), FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_QUALITY),
        "performance_resolution", "performance_alert_rate", "performance_adaptive_alerts", "performance_glass_120fps",
        ShadeGlassSettings.CONTROL_NATIVE_BLUR)
    fun boolean(key: String): Boolean = (factory?.get(key) as? Boolean) ?: if(key==FolderSettingsKeys.OWN)false else booleans[key] ?: if(key.startsWith("folder_"))boolean(key.removePrefix("folder_"))else booleans.getValue(key)
    fun integer(key: String): Int = (factory?.get(key) as? Int) ?: integers[key] ?: if(key.startsWith("folder_"))integer(key.removePrefix("folder_"))else integers.getValue(key)
    fun value(key: String): Any = factory?.get(key) ?: if(key==FolderSettingsKeys.OWN)false else booleans[key] ?: integers[key] ?: if(key.startsWith("folder_"))value(key.removePrefix("folder_"))else integers.getValue(key)
}
