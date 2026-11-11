package com.cosmod.launcherglass.ui

object OpticalSettings {
    val booleans=mapOf("indicator_keep_visible" to true,"edge_light_enabled" to true,"edge_light_motion" to true,"island_glass_enabled" to false,"volume_glass_enabled" to false,
        "banner_glass_enabled" to false,"banner_glass_motion" to true,"banner_plate_enabled" to false,
        "app_tilt_enabled" to false,"app_tilt_open" to true,"app_tilt_close" to true,
        "performance_light_drawer" to false,"performance_live_transition_glass" to false,
        "performance_adaptive_alerts" to true,"coloros_ui_effects" to true,"performance_ltpo_idle" to true,
        "performance_high_refresh_pacing" to false,
        "banner_motion_soft" to false,
        "large_folder_glass_own" to false,com.cosmod.launcherglass.util.IconPalette.FOLLOW_WALLPAPER to true,WidgetSettings.WALLPAPER_TINT to false) + EdgeSettings.booleans + LockscreenSettings.booleans
    val integers=linkedMapOf("edge_light_strength" to 55,"edge_light_width" to 25,"edge_light_sensitivity" to 50,
        "edge_light_smoothing" to 75,"island_glass_blur" to 12,"island_glass_width" to 40,
        "island_glass_distortion" to 80,"island_glass_opacity" to 25,"island_glass_corner" to 30,
        "island_glass_mix" to 100,"island_glass_rim" to 55,"photo_widget_fade_height" to 65,
        "edge_light_softness" to 65,"edge_light_spread" to 55,"photo_widget_fade_smoothness" to 90,"performance_profile" to 0,
        "banner_glass_blur" to 14,"banner_glass_distortion" to 85,"banner_glass_opacity" to 42,
        "banner_glass_width" to 42,"banner_glass_corner" to 38,"banner_glass_rim" to 55,"banner_motion_blur" to 16,
        "banner_rim_width" to 25,"banner_rim_softness" to 65,"banner_shadow_strength" to 42,"banner_shadow_size" to 12,
        "banner_open_speed" to 100,"banner_close_speed" to 100,"banner_plate_hue" to 0,
        "banner_plate_saturation" to 0,"banner_plate_brightness" to 10,"banner_plate_opacity" to 45,
        "banner_plate_bottom_fade" to 65,"banner_top_darkness" to 0,
        "app_tilt_reference_strength" to 100,"app_tilt_reference_perspective" to 100,
        "app_tilt_reference_center" to 0,"app_tilt_enter" to 100,"app_tilt_exit" to 100,
        "app_tilt_yaw" to 100,"app_tilt_smoothness" to 70,
        "indicator_glass_style" to 0,"indicator_glass_blur" to 12,"indicator_glass_opacity" to 26,
        "indicator_glass_corner" to 50,"indicator_glass_width" to 42,"indicator_glass_distortion" to 80,
        "indicator_glass_dispersion" to 0,"volume_glass_blur" to 14,"volume_glass_style" to 1,
        "volume_glass_brightness" to 0,"volume_glass_rim" to 100,"volume_glass_height" to 100,"volume_glass_width" to 42,"volume_glass_distortion" to 90,"volume_glass_opacity" to 18,"volume_glass_dispersion" to 0,
        "launcher_icon_size" to 100,"dock_width_percent" to 112,"dock_height_percent" to 105,
        "performance_resolution" to 100,"performance_alert_rate" to 0,
        "coloros_feature_background" to 1,"photo_widget_blur_radius" to 10,"photo_widget_tint_hue" to 0,
        "photo_widget_tint_lightness" to 0,WidgetSettings.TINT_SATURATION to 32,WidgetSettings.TINT_TRANSPARENCY to 0,
        "banner_plate_lightness" to 0,
        WidgetSettings.SCOPE to WidgetSettings.ALL,"large_folder_glass_style" to 1,"large_folder_glass_width" to 40,
        "large_folder_glass_strength" to 100,"large_folder_glass_opacity" to 15) +
        GlassDispersionSettings.defaults + EdgeSettings.integers + LockscreenSettings.integers
    fun maximum(key:String)=LockscreenSettings.maximums[key] ?: when(key){
        in EdgeSettings.darkWidthKeys->EdgeSettings.MAX_DARK_QUARTERS
        in EdgeSettings.spreadKeys->EdgeSettings.MAX_SPREAD
        "performance_profile"->3
        "performance_alert_rate"->4
        "coloros_feature_background","indicator_glass_style"->2
        "volume_glass_rim"->200
        "volume_glass_height"->160
        "volume_glass_blur"->32
        "volume_glass_style"->1
        "volume_glass_distortion"->300
        "island_glass_distortion","banner_glass_distortion","large_folder_glass_strength","indicator_glass_distortion"->300
        "large_folder_glass_style",WidgetSettings.SCOPE->1
        in EdgeSettings.directionKeys->360
        "photo_widget_blur_radius","island_glass_blur","island_glass_corner","banner_glass_blur","banner_glass_corner","indicator_glass_blur","indicator_glass_corner"->50
        "banner_motion_blur","banner_shadow_size"->24
        "banner_open_speed","banner_close_speed"->180
        "launcher_icon_size"->130
        "dock_width_percent","dock_height_percent"->160
        "app_tilt_reference_strength"->200
        "app_tilt_reference_perspective","app_tilt_enter","app_tilt_exit","app_tilt_yaw"->150
        "banner_plate_hue","photo_widget_tint_hue"->360
        else->100
    }
    fun minimum(key:String)=when(key){
        LockscreenSettings.NOTIFICATION_CORNER->-1
        LockscreenSettings.UNLOCK_SPEED->LockscreenSettings.MIN_UNLOCK_SPEED
        "volume_glass_height"->60
        "volume_glass_brightness","photo_widget_tint_lightness","banner_plate_lightness"->-100
        in EdgeSettings.darkWidthKeys->1
        "launcher_icon_size"->70
        "dock_width_percent","dock_height_percent"->55
        else->0
    }
}
