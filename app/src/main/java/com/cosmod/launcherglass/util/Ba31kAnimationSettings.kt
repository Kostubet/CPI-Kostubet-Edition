package com.cosmod.launcherglass.util

internal object Ba31kAnimationSettings {
    const val ENABLED = "ba31k_enabled"
    const val OPEN = "ba31k_open"
    const val CLOSE = "ba31k_close"
    const val GENIE = "ba31k_genie"
    const val DROPLET = "ba31k_droplet"
    const val SHADE = "ba31k_shade"
    const val SHADE_BLUR = "ba31k_shade_blur"
    const val CLEAR_SHADE = "ba31k_clear_shade"
    const val OPEN_SPEED = "ba31k_open_speed"
    const val CLOSE_SPEED = "ba31k_close_speed"
    const val GENIE_STRENGTH = "ba31k_genie_strength"
    const val DROPLET_STRENGTH = "ba31k_droplet_strength"
    const val BOUNCE = "ba31k_bounce"
    const val SPREAD = "ba31k_spread"
    const val SPEED = "ba31k_speed"
    const val STIFFNESS = "ba31k_stiffness"
    const val DAMPING = "ba31k_damping"
    const val INITIAL_VELOCITY = "ba31k_initial_velocity"
    const val DURATION = "ba31k_duration"
    const val APP_BLUR = "ba31k_app_blur"

    val booleans = mapOf(
        ENABLED to false, OPEN to true, CLOSE to true, GENIE to true, DROPLET to true,
        SHADE to false, SHADE_BLUR to true, CLEAR_SHADE to false,
        APP_BLUR to true,
    )
    val integers = mapOf(
        OPEN_SPEED to 100, CLOSE_SPEED to 100, GENIE_STRENGTH to 100,
        DROPLET_STRENGTH to 100, BOUNCE to 100, SPREAD to 100, SPEED to 100,
        STIFFNESS to 100, DAMPING to 88, INITIAL_VELOCITY to 100, DURATION to 100,
    )
    fun minimum(key: String) = when (key) {
        OPEN_SPEED, CLOSE_SPEED, STIFFNESS, DURATION -> 50
        DAMPING -> 65
        SPEED -> 25
        else -> 0
    }
    fun maximum(key: String) = when (key) {
        OPEN_SPEED, CLOSE_SPEED, GENIE_STRENGTH -> 200
        SPREAD -> 300
        SPEED, DROPLET_STRENGTH, BOUNCE, DAMPING -> 100
        else -> 200
    }
}
