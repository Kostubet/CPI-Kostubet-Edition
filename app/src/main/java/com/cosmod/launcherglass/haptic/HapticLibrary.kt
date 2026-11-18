package com.cosmod.launcherglass.haptic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import com.cosmod.launcherglass.ui.SettingsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

internal object HapticPatternCodec {
    private const val MAX_BYTES = 1_000_000

    fun encode(pattern: HapticPattern, includeAudio: Boolean): String = JSONObject().apply {
        put("version", 1)
        put("name", pattern.name)
        put("length", pattern.length)
        if (includeAudio) put("audio", pattern.audio)
        put("intensity", pattern.intensity)
        put("offset", pattern.offset)
        put("fadeIn", pattern.fadeIn)
        put("fadeOut", pattern.fadeOut)
        put("repeat", pattern.repeat)
        put("style", pattern.style?.name)
        with(pattern.generator) {
            put("generator", JSONArray(listOf(sensitivity, detail, strength, smoothness, bass, melody,
                impulseMs, repetitions, dynamics, noise)))
        }
        put("points", JSONArray(pattern.points.map {
            JSONArray(listOf(it.ms, it.amplitude.toDouble(), it.hz.toDouble(), it.sharpness.toDouble(),
                it.curve.name, it.bezierIn.toDouble(), it.bezierOut.toDouble(), it.pitch.toDouble(), it.tap,
                it.tapKind == HapticTap.PULSE))
        }))
    }.toString()

    fun read(stream: InputStream, includeAudio: Boolean): HapticPattern {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Pattern exceeds size limit" }
            output.write(buffer, 0, count)
        }
        val root = JSONObject(output.toString("UTF-8"))
        require(root.getInt("version") == 1)
        val points = root.getJSONArray("points")
        require(points.length() in 2..MAX_HAPTIC_POINTS)
        val settings = root.optJSONArray("generator")
        val generator = if (settings == null) GeneratorOptions() else GeneratorOptions(
            settings.getInt(0), settings.getInt(1), settings.getInt(2), settings.getInt(3), settings.getInt(4),
            settings.getInt(5), settings.getInt(6), settings.getInt(7), settings.getInt(8), settings.getInt(9),
        )
        return HapticPattern(root.getString("name"), root.getInt("length"), List(points.length()) { i ->
            val p = points.getJSONArray(i)
            HapticPoint(p.getInt(0), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(), p.getDouble(3).toFloat(),
                HapticCurve.valueOf(p.getString(4)), p.getDouble(5).toFloat(), p.getDouble(6).toFloat(),
                p.optDouble(7, 0.0).toFloat(), when {
                    p.optBoolean(9) -> HapticTap.PULSE
                    p.optBoolean(8) -> HapticTap.NATIVE
                    else -> HapticTap.NONE
                })
        }, root.optString("audio").takeIf { includeAudio && Uri.parse(it).scheme == "content" },
            root.optInt("intensity", 100), root.optInt("offset", 0), root.optInt("fadeIn", 0), root.optInt("fadeOut", 0),
            root.optString("style").takeIf { it.isNotEmpty() }?.let(HapticStyle::valueOf), generator, root.optBoolean("repeat"))
    }
}

internal class HapticLibrary(context: Context) {
    data class Entry(val id: String, val pattern: HapticPattern)
    private val directory = File(context.filesDir, "haptic-patterns")

    fun read(): List<Entry> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .sortedByDescending { it.lastModified() }.take(40).mapNotNull { file ->
            runCatching {
                Entry(file.nameWithoutExtension, AtomicFile(file).openRead().use { HapticPatternCodec.read(it, true) })
            }.getOrNull()
        }

    fun save(pattern: HapticPattern, previousId: String?): Entry {
        val id = previousId ?: UUID.randomUUID().toString()
        if (previousId == null) check(directory.listFiles().orEmpty().count { it.extension == "json" } < 40)
        val file = file(id)
        val output = file.startWrite()
        try {
            output.write(HapticPatternCodec.encode(pattern, true).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return Entry(id, pattern)
    }

    fun delete(id: String) = file(id).delete()

    private fun file(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(directory, "$id.json"))
    }
}

/** Ring bindings contain finished effects only. The sound file and project library stay in CPI. */
internal object HapticRingStore {
    const val READ = "haptic_ring_read"
    const val REPORT = "haptic_ring_report"
    val uri: Uri get() = SettingsProvider.CONTENT_URI.buildUpon().appendPath("haptic-ring").build()
    private val targets = listOf("default", "sim1", "sim2")
    private fun prefs(context: Context) = context.getSharedPreferences("haptic_ring", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    private fun boot(context: Context) = android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
    fun status(context: Context): String? {
        val state = prefs(context)
        if (state.getInt("adapterBoot", -2) != boot(context) ||
            state.getInt("adapterBuild", -1) != com.cosmod.launcherglass.BuildConfig.VERSION_CODE) return null
        return state.getString("adapter", null)
    }
    fun report(context: Context, adapter: String) {
        if (adapter in listOf("coloros17", "aosp", "unsupported")) prefs(context).edit().putString("adapter", adapter)
            .putInt("adapterBoot", boot(context)).putInt("adapterBuild", com.cosmod.launcherglass.BuildConfig.VERSION_CODE).apply()
    }

    fun enable(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        context.contentResolver.notifyChange(uri, null)
    }

    fun bindingName(context: Context, tag: String): String? = prefs(context).getString("name_$tag", null)

    fun put(context: Context, tag: String, pattern: HapticPattern?, waveform: HapticWaveform? = null) {
        require(tag in targets)
        val editor = prefs(context).edit()
        if (pattern == null) {
            for (key in listOf("times", "levels", "name", "uri", "native")) editor.remove("${key}_$tag")
        } else {
            requireNotNull(waveform)
            require(waveform.levels.any { it > 0 })
            editor.putString("times_$tag", waveform.times.joinToString(","))
                .putString("levels_$tag", waveform.levels.joinToString(","))
                .putString("name_$tag", pattern.name)
                .putString("uri_$tag", pattern.audio)
            if (pattern.length <= 5000 && pattern.points.size <= 256 &&
                pattern.points.any { it.hz > 0f || it.pitch != 0f }) {
                editor.putString("native_$tag", HapticPatternCodec.encode(pattern, false))
            } else editor.remove("native_$tag")
        }
        editor.apply()
        context.contentResolver.notifyChange(uri, null)
    }

    fun read(context: Context, masterEnabled: Boolean): Bundle = Bundle().apply {
        if (!masterEnabled || !enabled(context)) return@apply
        val state = prefs(context)
        for (tag in targets) for (key in listOf("times", "levels", "uri", "native")) {
            val fullKey = "${key}_$tag"
            state.getString(fullKey, null)?.let { putString(fullKey, it) }
        }
    }
}
