package com.cosmod.launcherglass.util

internal object Ba31kAnimationSettings {
    const val ENABLED = "ba31k_enabled"
    const val OPEN = "ba31k_open"
    const val CLOSE = "ba31k_close"
    const val GENIE = "ba31k_genie"
    const val DROPLET = "ba31k_droplet"
    const val OPEN_SPEED = "ba31k_open_speed"
    const val CLOSE_SPEED = "ba31k_close_speed"
    const val TILT = "ba31k_tilt_strength"
    const val TRAJECTORY = "app_genie_trajectory"
    const val GENIE_STRENGTH = "ba31k_genie_strength"
    const val DROPLET_STRENGTH = "ba31k_droplet_strength"
    const val BOUNCE = "ba31k_bounce"
    const val SPEED = "ba31k_speed"
    const val STIFFNESS = "ba31k_stiffness"
    const val DAMPING = "ba31k_damping"
    const val INITIAL_VELOCITY = "ba31k_initial_velocity"
    const val DURATION = "ba31k_duration"
    const val APP_BLUR = "ba31k_app_blur"

    val booleans = mapOf(
        ENABLED to false, OPEN to true, CLOSE to true, GENIE to true, DROPLET to true,
        APP_BLUR to true, TRAJECTORY to false,
    )
    val integers = mapOf(
        OPEN_SPEED to 100, CLOSE_SPEED to 100, GENIE_STRENGTH to 100, TILT to 100,
        DROPLET_STRENGTH to 100, BOUNCE to 100, SPEED to 100,
        STIFFNESS to 100, DAMPING to 88, INITIAL_VELOCITY to 100, DURATION to 100,
    )
    private val classicMapping=mapOf(
        "app_tilt_enabled" to ENABLED, "app_tilt_open" to OPEN, "app_tilt_close" to CLOSE,
        "app_tilt_reference_strength" to GENIE_STRENGTH, "app_genie_droplet" to DROPLET,
        "app_genie_open_speed" to OPEN_SPEED, "app_genie_close_speed" to CLOSE_SPEED,
    )
    val classicKeys get()=classicMapping.keys

    fun migrateClassic(saved:Map<String,Any?>):Map<String,Any> {
        if(saved["app_tilt_enabled"]!=true || ENABLED in saved)return emptyMap()
        return buildMap {
            put(ENABLED,true)
            for((old,key) in classicMapping)when(val value=saved[old]){
                is Int->if(key !in saved && key in integers)put(key,value.coerceIn(minimum(key),maximum(key)))
                is Boolean->if(key !in saved && key in booleans)put(key,value)
            }
        }
    }

    fun minimum(key: String) = when (key) {
        OPEN_SPEED, CLOSE_SPEED, STIFFNESS, DURATION -> 50
        DAMPING -> 65
        SPEED -> 25
        else -> 0
    }
    fun maximum(key: String) = when (key) {
        OPEN_SPEED, CLOSE_SPEED, GENIE_STRENGTH -> 200
        TILT -> 250
        SPEED, DROPLET_STRENGTH, BOUNCE, DAMPING -> 100
        else -> 200
    }
}
