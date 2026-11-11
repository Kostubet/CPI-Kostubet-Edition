package com.cosmod.launcherglass.ui

import android.content.SharedPreferences

internal object EdgeSettings {
    const val LOCK_START=6
    private val surfaces=listOf("icons","folders","photo","dock","banners","live") +
        LockscreenSettings.Surface.entries.map {"lock_"+it.id} + "page_indicator"
    val count get()=surfaces.size
    fun key(surface: Int, field: String) = "edge_${surfaces[surface]}_$field"
    const val DARK_OVERLAP="dark_overlap"
    const val DEFAULT_DARK_OPACITY=65
    const val DEFAULT_DARK_QUARTERS=8
    const val MAX_DARK_QUARTERS=32
    const val MAX_SPREAD=200
    val spreadKeys=surfaces.indices.map {key(it,"spread")}.toSet()
    val directionKeys=surfaces.indices.map {key(it,"direction")}.toSet()
    val legacyDarkWidths=surfaces.indices.associate {key(it,"dark_width") to key(it,"dark_width_qdp")} +
        ("edge_lock_right_dark_width" to "edge_lock_right_dark_width_qdp")
    val darkWidthKeys=surfaces.indices.map {key(it,"dark_width_qdp")}.toSet()

    val booleanFields = mapOf("enabled" to true, "motion" to true)
    val integerFields = mapOf("strength" to 52, "width" to 25, "softness" to 65, "spread" to 55)
    val booleans = surfaces.indices.flatMap { surface ->
        booleanFields.map { (field, value) -> key(surface, field) to
            if(field=="motion" && surface>=LOCK_START)false else value }
    }.toMap() + surfaces.indices.associate { key(it, "dark_enabled") to false } +
        surfaces.indices.associate {key(it,DARK_OVERLAP) to true}
    val integers = surfaces.indices.flatMap { surface ->
        integerFields.map { (field, value) -> key(surface, field) to value }
    }.toMap() + surfaces.indices.associate { key(it, "dark_opacity") to DEFAULT_DARK_OPACITY } +
        darkWidthKeys.associateWith { DEFAULT_DARK_QUARTERS } + directionKeys.associateWith {315} +
        surfaces.indices.associate {key(it,"dark_softness") to 0}

    fun migrate(prefs: SharedPreferences) {
        if(legacyDarkWidths.keys.any(prefs::contains))prefs.edit().apply {
            for((old,key) in legacyDarkWidths){
                if(!prefs.contains(old))continue
                if(!prefs.contains(key))putInt(key,prefs.getInt(old,2).coerceIn(1,8)*4)
                remove(old)
            }
        }.apply()
        if (prefs.getBoolean("per_surface_edge_v1", false)) return
        val editor = prefs.edit()
        for (surface in 0 until LOCK_START) {
            for ((field, default) in booleanFields) {
                val target = key(surface, field)
                val old = "edge_light_$field"
                if (!prefs.contains(target) && prefs.contains(old)) editor.putBoolean(target, prefs.getBoolean(old, default))
            }
            for ((field, default) in integerFields) {
                val target = key(surface, field)
                val old = when {
                    surface == 4 && field == "strength" && prefs.contains("banner_glass_rim") -> "banner_glass_rim"
                    surface == 4 && field in listOf("width", "softness") && prefs.contains("banner_rim_$field") -> "banner_rim_$field"
                    surface == 5 && field == "strength" && prefs.contains("island_glass_rim") -> "island_glass_rim"
                    else -> "edge_light_$field"
                }
                if (!prefs.contains(target) && prefs.contains(old)) editor.putInt(target, prefs.getInt(old, default))
            }
        }
        editor.putBoolean("per_surface_edge_v1", true).apply()
    }
}
