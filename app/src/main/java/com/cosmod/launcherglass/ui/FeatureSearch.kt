package com.cosmod.launcherglass.ui

import com.cosmod.launcherglass.R

/** Labels indexed in both supported languages by the feature search. */
internal object FeatureSearch {
    fun labels(section: String): IntArray = when (section) {
        "ICONS" -> intArrayOf(
            R.string.ui_icon_size, R.string.ui_flux_only_title, R.string.ui_flux_only_warning,
            R.string.ui_background_blur, R.string.ui_blur, R.string.ui_blur_radius,
            R.string.ui_blur_quality, R.string.ui_live_blur_fps, R.string.ui_drawer_blur,
            R.string.ui_folder_blur, R.string.ui_folder_blur_description, R.string.ui_icon_blur,
            R.string.ui_icon_design, R.string.ui_icon_contour, R.string.ui_contour_opacity,
            R.string.ui_logo_shadow, R.string.ui_logo_shadow_opacity, R.string.ui_icon_depth,
            R.string.ui_depth_strength, R.string.ui_blur_style, R.string.ui_liquid_width,
            R.string.ui_liquid_distortion, R.string.ui_coloring, R.string.ui_detailed, R.string.ui_coloring_wallpaper,
            R.string.ui_system, R.string.ui_color_mode_accessibility, R.string.ui_icon_color_style,
            R.string.ui_color_preview, R.string.ui_hue, R.string.ui_saturation,
            R.string.ui_brightness, R.string.ui_logo_outline_opacity, R.string.ui_logo_outline_width,
            R.string.ui_logo_outline_brightness, R.string.ui_logo_outline, R.string.ui_blur_tone,
            R.string.ui_plate_opacity, R.string.ui_blur_saturation, R.string.ui_blur_vibrancy,
            R.string.ui_blur_brightness, R.string.ui_glass_dispersion
        )
        "FOLDERS" -> intArrayOf(
            R.string.ui_folders, R.string.ui_folder_own, R.string.ui_folder_mode_own,
            R.string.ui_folder_mode_icons, R.string.ui_blur, R.string.ui_blur_radius,
            R.string.ui_blur_quality, R.string.ui_blur_tone, R.string.ui_plate_opacity,
            R.string.ui_blur_saturation, R.string.ui_blur_vibrancy, R.string.ui_blur_brightness,
            R.string.ui_blur_style, R.string.ui_liquid_width, R.string.ui_liquid_distortion,
            R.string.ui_glass_dispersion
        )
        "PHOTO" -> intArrayOf(
            R.string.ui_widget_glass, R.string.ui_widget_content_transparency, R.string.ui_widget_scope, R.string.ui_widget_frame,
            R.string.ui_widget_tint, R.string.ui_tint_lightness,
            R.string.ui_photo_widget_blur, R.string.ui_photo_widget_opacity, R.string.ui_blur_radius,
            R.string.ui_photo_style, R.string.ui_photo_original, R.string.ui_photo_timer,
            R.string.ui_blur_style, R.string.ui_glass_corner, R.string.ui_glass_shade,
            R.string.ui_glass_rim, R.string.ui_glass_glow, R.string.ui_photo_fade_height,
            R.string.ui_photo_fade_smoothness, R.string.ui_photo_widget_tint, R.string.ui_hue,
            R.string.ui_liquid_width, R.string.ui_liquid_distortion, R.string.ui_glass_dispersion
        )
        "DOCK" -> intArrayOf(
            R.string.ui_blur_style, R.string.ui_glass_off, R.string.ui_dock_width,
            R.string.ui_dock_height, R.string.ui_blur_radius, R.string.ui_dock_rounding,R.string.ui_page_indicator_glass,R.string.ui_page_indicator_keep_visible,
            R.string.ui_dock_inset, R.string.ui_liquid_width, R.string.ui_liquid_distortion,
            R.string.ui_glass_dispersion
        )
        "BANNERS" -> intArrayOf(
            R.string.banner_title, R.string.banner_test_channel_settings, R.string.banner_motion,
            R.string.ui_soft_motion, R.string.banner_opening_blur, R.string.banner_open_speed,
            R.string.banner_close_speed, R.string.ui_blur_radius, R.string.ui_liquid_width,
            R.string.ui_liquid_distortion, R.string.island_opacity, R.string.island_lens_corner,
            R.string.banner_test,
            R.string.banner_shadow, R.string.banner_shadow_size, R.string.banner_top_darkness,
            R.string.banner_plate, R.string.ui_hue, R.string.ui_saturation,
            R.string.ui_brightness, R.string.banner_plate_transparency, R.string.banner_plate_fade,
            R.string.ui_glass_dispersion
        )
        "VOLUME" -> intArrayOf(
            R.string.ui_volume_glass, R.string.ui_volume_background_brightness, R.string.ui_volume_height, R.string.ui_volume_rim, R.string.ui_blur_style, R.string.ui_blur_radius, R.string.ui_liquid_width,
            R.string.ui_liquid_distortion, R.string.island_opacity, R.string.ui_glass_dispersion
        )
        "LIVE" -> intArrayOf(
            R.string.island_title, R.string.ui_liquid_width, R.string.ui_liquid_distortion,
            R.string.ui_blur_radius, R.string.island_mix, R.string.island_opacity,
            R.string.island_lens_corner, R.string.ui_glass_dispersion
        )
        "LOCKSCREEN" -> intArrayOf(
            R.string.lock_glass_title, R.string.lock_glass_pin, R.string.lock_glass_clock, R.string.lock_aod_blur,
            R.string.lock_glass_notifications, R.string.lock_glass_shortcuts, R.string.lock_glass_capsule,
            R.string.ui_coloring_wallpaper, R.string.ui_tint_transparency,
            R.string.lock_glass_widgets, R.string.lock_glass_clear_bouncer, R.string.lock_unlock_animation,
            R.string.lock_unlock_speed, R.string.lock_unlock_smoothness, R.string.lock_unlock_travel, R.string.lock_unlock_impulse, R.string.lock_unlock_blur, R.string.ui_saturation, R.string.ui_blur_style, R.string.ui_blur_radius,
            R.string.edge_title, R.string.ui_dark_contour, R.string.ui_glass_dispersion
        )
        "LIGHT" -> intArrayOf(
            R.string.ui_soft_light_title, R.string.ui_haze_strength, R.string.ui_haze_feather,
            R.string.ui_diffusion, R.string.ui_wallpaper_glow_radius, R.string.ui_wallpaper_glow_threshold,
            R.string.ui_wallpaper_glow_saturation, R.string.ui_haze_icons, R.string.ui_haze_folders,
            R.string.ui_haze_widgets, R.string.ui_haze_dock, R.string.ui_haze_banners, R.string.ui_haze_live,
            R.string.ui_dark_contour, R.string.ui_dark_contour_strength, R.string.ui_dark_contour_width,
            R.string.ui_dark_contour_softness, R.string.ui_dark_contour_overlap, R.string.edge_title, R.string.edge_motion,
            R.string.edge_strength, R.string.edge_width, R.string.ui_edge_softness,
            R.string.ui_edge_spread, R.string.ui_edge_direction
        )
        "MOTION" -> intArrayOf(
            R.string.ui_tilt_effect, R.string.app_tilt_open, R.string.app_tilt_close,
            R.string.app_tilt_strength, R.string.ui_tilt_open_amount, R.string.ui_tilt_close_amount,
            R.string.ui_tilt_yaw, R.string.ui_tilt_perspective,
            R.string.ui_tilt_smoothness
        )
        "PERFORMANCE" -> intArrayOf(
            R.string.ui_performance, R.string.ui_profile_auto, R.string.ui_profile_quality,
            R.string.ui_profile_eco, R.string.ui_profile_balanced, R.string.ui_performance_resolution,
            R.string.ui_performance_alert_rate, R.string.ui_adaptive_alert_capture, R.string.ui_transition_live_glass,
            R.string.ui_ltpo_idle, R.string.ui_165hz_pacing, R.string.ui_performance_drawer,
            R.string.ui_blur_update, R.string.ui_blur_mode, R.string.ui_static_blur,
            R.string.ui_live_blur, R.string.ui_live_blur_description, R.string.ui_static_blur_description,
            R.string.ui_energy_saving, R.string.ui_energy_saving_description
        )
        "SETTINGS" -> intArrayOf(
            R.string.ui_settings_display, R.string.ui_theme_light, R.string.ui_theme_dark,
            R.string.ui_theme_system, R.string.ui_theme, R.string.ui_coloros_effects,
            R.string.ui_background_off, R.string.ui_background_classic, R.string.ui_background_flowing,
            R.string.ui_feature_background, R.string.ui_language, R.string.ui_catalog_tools,
            R.string.ui_boot_restart, R.string.ui_boot_restart_toast, R.string.ui_developer_mode,
            R.string.ui_gpu_effects, R.string.ui_color_depth, R.string.ui_color_depth_bits,
            R.string.ui_disable_edge_optimization, R.string.ui_optimize_mini_icons, R.string.ui_color_depth_stage,
            R.string.ui_depth_before_blur, R.string.ui_depth_after_blur, R.string.ui_depth_after_refraction,
            R.string.ui_depth_finished, R.string.ui_material_order
        )
        else -> intArrayOf()
    }
}
