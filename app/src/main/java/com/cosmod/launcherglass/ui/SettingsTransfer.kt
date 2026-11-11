package com.cosmod.launcherglass.ui

import android.content.SharedPreferences
import com.cosmod.launcherglass.BuildConfig
import org.json.JSONObject
import java.io.InputStream

/** Versioned, bounded presets: validate the entire file before touching preferences. */
internal object SettingsTransfer {
    private const val FORMAT = "cherrypick-icons-settings"
    private const val VERSION = 1
    private const val MAX_BYTES = 64 * 1024
    private val defaults get() = SettingsDefaults.portable()

    private fun optionalFolder(key: String) = key != FolderSettingsKeys.OWN &&
        (key in FolderSettingsKeys.integers.map(FolderSettingsKeys::key) ||
            key in FolderSettingsKeys.booleans.map(FolderSettingsKeys::key))

    fun export(prefs: SharedPreferences, keys: Set<String> = defaults.keys): String {
        val values = JSONObject()
        val saved = prefs.all
        for ((key, default) in defaults.toSortedMap()) {
            if (key !in keys) continue
            // Absence means inherit the icon value, and must survive a round trip.
            if (optionalFolder(key) && !saved.containsKey(key)) continue
            values.put(key, saved[key] ?: default)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("moduleVersion", BuildConfig.VERSION_NAME).put("settings", values).toString(2) + "\n"
    }

    fun read(input: InputStream): Map<String, Any> {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_BYTES) { "Preset exceeds 64 KB" }
            bytes.write(buffer, 0, count)
        }
        return parse(bytes.toString("UTF-8"))
    }

    fun parse(text: String): Map<String, Any> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preset exceeds 64 KB" }
        val document = JSONObject(text.removePrefix("\uFEFF"))
        require(document.opt("format") == FORMAT) { "Not a CherryPick preset" }
        require(document.opt("version") == VERSION) { "Unsupported preset version" }
        val source = document.getJSONObject("settings")
        val schema = defaults
        val values = linkedMapOf<String, Any>()
        val saved=source.keys().asSequence().associateWith {source.get(it)}
        val normalized=saved+LockscreenSettings.sharedShortcutValues(saved)
        for ((key,value) in normalized) {
            val default = schema[key] ?: continue // New optional settings may be ignored by older modules.
            when (default) {
                is Boolean -> { require(value is Boolean) { "Invalid $key" }; values[key] = value }
                is Int -> {
                    require(value is Int || value is Long) { "Invalid $key" }
                    val number = (value as Number).toLong()
                    require(number in range(key)) { "Out of range: $key" }
                    values[key] = number.toInt()
                }
            }
        }
        if(WidgetSettings.SCOPE !in values && (source.has(WidgetSettings.LEGACY_ALL) ||
                SettingsProvider.KEY_PHOTO_WIDGET_BLUR in values)){
            val photos=values[SettingsProvider.KEY_PHOTO_WIDGET_BLUR]==true
            val all=source.opt(WidgetSettings.LEGACY_ALL)==true
            values[WidgetSettings.SCOPE]=if(all)WidgetSettings.ALL else WidgetSettings.PHOTO_ONLY
            if(all){
                values[SettingsProvider.KEY_PHOTO_WIDGET_BLUR]=true
                if(!photos){
                    val opacity=source.opt(WidgetSettings.LEGACY_OPACITY)
                    values[SettingsProvider.KEY_PHOTO_WIDGET_OPACITY]=if(opacity is Int && opacity in 0..100)opacity else 78
                }
            }
        }
        for((old,key) in EdgeSettings.legacyDarkWidths){
            if(key in values)continue
            val width=source.opt(old)
            if(width is Int && width in 1..8)values[key]=width*4
        }
        require(values.isNotEmpty()) { "Empty preset" }
        return values
    }

    private fun range(key: String): LongRange {
        if(key in OpticalSettings.integers)return OpticalSettings.minimum(key).toLong()..OpticalSettings.maximum(key).toLong()
        val base = if (key.startsWith("folder_")) key.removePrefix("folder_") else key
        if(base in OpticalSettings.integers)return OpticalSettings.minimum(base).toLong()..OpticalSettings.maximum(base).toLong()
        return when (base) {
            WallpaperGlowSettings.SATURATION -> 0L..200L
            SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL -> 0L..1L
            SettingsProvider.KEY_DOCK_BLUR -> 0L..40L

            SettingsProvider.KEY_PHOTO_WIDGET_STYLE -> 0L..1L
            SettingsProvider.KEY_PHOTO_WIDGET_CORNER -> 0L..50L
            SettingsProvider.KEY_PHOTO_WIDGET_SHADE -> 0L..100L
            SettingsProvider.KEY_PHOTO_WIDGET_GLOW -> 0L..100L
            SettingsProvider.KEY_PHOTO_WIDGET_RIM -> 0L..100L
            SettingsProvider.KEY_DOCK_STYLE -> 0L..2L
            SettingsProvider.KEY_DOCK_OPACITY -> 0L..100L
            SettingsProvider.KEY_DOCK_CORNER -> 0L..50L
            SettingsProvider.KEY_DOCK_INSET -> 0L..32L
            SettingsProvider.KEY_DOCK_LIQUID_WIDTH -> 0L..100L
            SettingsProvider.KEY_DOCK_LIQUID_DISTORTION -> 0L..300L

            SettingsProvider.KEY_COLOR_HUE, SettingsProvider.KEY_LIGHT_HUE, SettingsProvider.KEY_DARK_HUE -> 0L..360L
            SettingsProvider.KEY_BLUR_SATURATION, SettingsProvider.KEY_BLUR_VIBRANCY -> 0L..200L
            SettingsProvider.KEY_LIQUID_DISTORTION, SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION -> 0L..300L
            SettingsProvider.KEY_BLUR_RADIUS -> 0L..50L
            SettingsProvider.KEY_LIVE_FPS -> 1L..120L
            SettingsProvider.KEY_COLOR_DEPTH_BITS -> 8L..24L
            SettingsProvider.KEY_COLOR_DEPTH_STAGE -> 0L..3L
            SettingsProvider.KEY_BLUR_MODE, SettingsProvider.KEY_BLUR_STYLE,
            SettingsProvider.KEY_ICON_APPEARANCE, SettingsProvider.KEY_COLOR_MODE -> 0L..1L
            else -> 0L..100L
        }
    }

    fun apply(prefs: SharedPreferences, values: Map<String, Any>) {
        check(write(prefs.edit(),values,defaults.keys).commit()) { "Could not save preset" }
    }

    fun write(editor: SharedPreferences.Editor,values: Map<String,Any>,keys: Set<String>): SharedPreferences.Editor {
        // Complete replacement of portable settings; retain local state and migration markers.
        for ((key, default) in defaults) {
            if (key !in keys) continue
            val value = values[key] ?: if (optionalFolder(key)) null else default
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                else -> editor.remove(key)
            }
        }
        return editor
    }
}
