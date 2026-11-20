package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class HapticMotor(context: Context, private val touchFeedback: Boolean = false) {
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        ?.takeIf { it.hasVibrator() } ?: context.getSystemService(Vibrator::class.java)
    val present = vibrator?.hasVibrator() == true
    val amplitude = present && vibrator?.hasAmplitudeControl() == true
    val primitives = if (present) runCatching { vibrator?.arePrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
        VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
        VibrationEffect.Composition.PRIMITIVE_THUD, VibrationEffect.Composition.PRIMITIVE_SPIN,
    )?.count { it } ?: 0 }.getOrDefault(0) else 0
    val envelope = if (Build.VERSION.SDK_INT >= 36 && present && vibrator != null)
        readEnvelope(vibrator) else null
    val frequency get() = envelope?.frequency
    private val vendor = if (present) RichTapHaptics.discover() else null
    val richTap get() = vendor != null
    private var richTapActive = false
    private val scheduledClicks = Handler(Looper.getMainLooper())
    private val clickPresets = intArrayOf(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK)
    private val supportedClicks = runCatching { vibrator?.let { motor ->
        val supported = motor.areEffectsSupported(*clickPresets)
        clickPresets.filterIndexed { index, _ -> supported[index] == Vibrator.VIBRATION_EFFECT_SUPPORT_YES }
    }.orEmpty() }.getOrDefault(emptyList())

    data class Envelope(val minimum: Int, val maximum: Int, val points: Int, val duration: Int,
        val frequency: ClosedFloatingPointRange<Float>?, val resonant: Float)

    @RequiresApi(36)
    private fun readEnvelope(motor: Vibrator): Envelope? = runCatching {
        if (!motor.areEnvelopeEffectsSupported()) return null
        val info = motor.envelopeEffectInfo
        val frequency = motor.frequencyProfile?.let { profile ->
            val low = profile.minFrequencyHz
            val high = profile.maxFrequencyHz
            if (low.isFinite() && high.isFinite() && low > 0 && high > low) low..high else null
        }
        Envelope(
            info.minControlPointDurationMillis.toInt(), info.maxControlPointDurationMillis.toInt(),
            info.maxSize, info.maxDurationMillis.toInt(), frequency, motor.resonantFrequency,
        ).takeIf { it.minimum > 0 && it.maximum >= it.minimum && it.points >= 2 && it.duration > 0 }
    }.getOrNull()

    private data class PreviewEffect(val effect: VibrationEffect, val richTap: Boolean = false)

    private fun previewEffect(pattern: HapticPattern, start: Int, end: Int): PreviewEffect? {
        if (!present || end <= start) return null
        val useFrequency = frequency != null && pattern.points.any { it.hz > 0f }
        if (pattern.hasTapPoints) {
            pattern.nativeTaps(start, end)?.let { taps ->
                clickEffect(taps)?.let { return PreviewEffect(it) }
                vendor?.tapEffect(taps, end - start)?.let { return PreviewEffect(it, richTap = true) }
            }
            if (!useFrequency) {
                vendor?.mixedEffect(pattern, start, end)?.let { return PreviewEffect(it, richTap = true) }
                return HapticWaveform.sample(pattern, start, end, amplitude).effect(false)?.let(::PreviewEffect)
            }
            return frequencyWithTaps(pattern, start, end)
        }
        return continuousEffect(pattern, start, end, useFrequency)
    }

    private fun continuousEffect(pattern: HapticPattern, start: Int, end: Int, useFrequency: Boolean): PreviewEffect? {
        if (end <= start) return null
        if (vendor != null && pattern.points.any { it.pitch != 0f }) {
            vendor.effect(pattern, start, end)?.let { return PreviewEffect(it, richTap = true) }
        }
        if (Build.VERSION.SDK_INT >= 36 && envelope != null) {
            val effect = nativeEnvelope(pattern, start, end, envelope, useFrequency)
            if (effect != null) return PreviewEffect(effect)
        }
        // The sparse RichTap sweep can miss the accents of a generated audio envelope.
        if (!amplitude && end - start <= 5000) {
            vendor?.effect(pattern, start, end)?.let { return PreviewEffect(it, richTap = true) }
        }
        return HapticWaveform.sample(pattern, start, end, amplitude).effect(false)?.let(::PreviewEffect)
    }

    private fun frequencyWithTaps(pattern: HapticPattern, start: Int, end: Int): PreviewEffect? {
        // A dense Android envelope preserves the physical frequency and the short tap peaks when supported.
        val limits = envelope
        if (Build.VERSION.SDK_INT >= 36 && limits != null) {
            nativeEnvelope(pattern, start, end, limits, useFrequency = true, sampleMs = 8)?.let { return PreviewEffect(it) }
        }
        return HapticWaveform.sample(pattern, start, end, amplitude).effect(false)?.let(::PreviewEffect)
    }

    private fun clickEffect(taps: List<HapticClick>): VibrationEffect? = runCatching {
        val motor = vibrator ?: return null
        val primitive = VibrationEffect.Composition.PRIMITIVE_CLICK
        if (taps.size > 256 || !motor.areAllPrimitivesSupported(primitive)) return null
        val duration = motor.getPrimitiveDurations(primitive).firstOrNull()?.takeIf { it > 0 } ?: return null
        var previousEnd = 0
        val composition = VibrationEffect.startComposition()
        for (tap in taps) {
            if (tap.time < previousEnd) return null
            composition.addPrimitive(primitive, tap.strength, tap.time - previousEnd)
            previousEnd = tap.time + duration
        }
        composition.compose()
    }.getOrNull()

    fun nativeRingtone(pattern: HapticPattern): VibrationEffect? {
        val hardware = envelope
        return when {
            vendor != null && pattern.points.any { it.pitch != 0f } ->
                vendor.effect(pattern, 0, pattern.length, repeating = true)
            Build.VERSION.SDK_INT >= 36 && hardware != null && hardware.frequency != null &&
                pattern.points.any { it.hz > 0f } ->
                nativeEnvelope(pattern, 0, pattern.length, hardware, useFrequency = true)?.let {
                    VibrationEffect.createRepeatingEffect(it)
                }
            else -> null
        }
    }

    @RequiresApi(36)
    private fun nativeEnvelope(pattern: HapticPattern, start: Int, end: Int, limits: Envelope,
        useFrequency: Boolean, sampleMs: Int = 20): VibrationEffect? = runCatching {
        val duration = end - start
        if (duration < limits.minimum * 2 || duration > limits.duration) return null
        val minSegments = ceil(duration.toDouble() / limits.maximum).toInt().coerceAtLeast(2)
        val maxSegments = minOf(duration / limits.minimum, limits.points)
        if (minSegments > maxSegments) return null
        val step = sampleMs.coerceIn(limits.minimum, limits.maximum)
        val count = ceil(duration.toDouble() / step).toInt().coerceIn(minSegments, maxSegments)
        var previous = start
        var active = false
        val waveform = if (useFrequency) VibrationEffect.WaveformEnvelopeBuilder() else null
        val basic = if (waveform == null) VibrationEffect.BasicEnvelopeBuilder() else null
        val initial = pattern.at(start)
        val range = limits.frequency
        fun hz(point: HapticPoint): Float {
            requireNotNull(range)
            val fallback = if (limits.resonant.isFinite()) limits.resonant else (range.start + range.endInclusive) * .5f
            return (point.hz.takeIf { it > 0f } ?: fallback).coerceIn(range.start, range.endInclusive)
        }
        waveform?.setInitialFrequencyHz(hz(initial))
        basic?.setInitialSharpness(initial.sharpness)
        for (i in 1..count) {
            val time = start + duration * i / count
            val point = pattern.at(time)
            val strength = if (i == count) 0f else point.amplitude
            active = active || strength > 0f
            val transition = (time - previous).toLong()
            waveform?.addControlPoint(strength, hz(point), transition)
            basic?.addControlPoint(strength, point.sharpness, transition)
            previous = time
        }
        if (!active) null else if (waveform != null) waveform.build() else basic?.build()
    }.getOrNull()

    fun prepare(pattern: HapticPattern, start: Int, end: Int): (() -> Unit)? {
        val taps = pattern.tapHits(start, end)
        if (taps.isNotEmpty()) {
            if (pattern.nativeTaps(start, end) != null) {
                clickEffect(taps)?.let { return { play(it) } }
            }
            if (supportedClicks.isNotEmpty()) return preparePresetClicks(pattern, taps, start, end)
        }
        val preview = previewEffect(pattern, start, end) ?: return null
        return { playPreview(preview) }
    }

    private fun preparePresetClicks(pattern: HapticPattern, taps: List<HapticClick>, start: Int, end: Int): () -> Unit {
        val useFrequency = frequency != null && pattern.points.any { it.hz > 0f }
        val steps = buildList {
            var cursor = start
            val onlyClicks = pattern.nativeTaps(start, end) != null
            for (tap in taps) {
                val time = start + tap.time
                if (!onlyClicks) {
                    val finish = time - 4
                    continuousEffect(pattern, cursor, finish, useFrequency)?.let { add(cursor - start to it) }
                }
                add(tap.time to PreviewEffect(predefinedClick(tap.strength)))
                cursor = time + 36
            }
            if (!onlyClicks) continuousEffect(pattern, cursor, end, useFrequency)?.let { add(cursor - start to it) }
        }
        return {
            val clock = SystemClock.uptimeMillis()
            for ((delay, effect) in steps) {
                if (delay == 0) playPreview(effect)
                else scheduledClicks.postAtTime({ runCatching { playPreview(effect) } }, clock + delay)
            }
        }
    }

    private fun playPreview(preview: PreviewEffect) {
        // A native click may interrupt an earlier vendor sweep on this same actuator.
        richTapActive = richTapActive || preview.richTap
        play(preview.effect)
    }

    private fun predefinedClick(strength: Float): VibrationEffect {
        val desired = when {
            strength < .42f -> 0
            strength < .78f -> 1
            else -> 2
        }
        val closest = supportedClicks.minByOrNull { kotlin.math.abs(clickPresets.indexOf(it) - desired) }
        return VibrationEffect.createPredefined(closest ?: clickPresets[desired])
    }

    fun auditionClick(strength: Float) {
        if (present) runCatching {
            play(clickEffect(listOf(HapticClick(0, strength))) ?: predefinedClick(strength))
        }
    }

    fun celebrationTap(note: WinterMelody.Note) {
        if (!present) return
        val pulse = VibrationEffect.createOneShot(
            note.pulseMs,
            if (amplitude) (note.strength * 255).roundToInt().coerceIn(1, 255)
            else VibrationEffect.DEFAULT_AMPLITUDE,
        )
        runCatching { play(pulse) }.onFailure {
            // Some vendor implementations accept only the device's default amplitude.
            runCatching { play(VibrationEffect.createOneShot(note.pulseMs, VibrationEffect.DEFAULT_AMPLITUDE)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun play(effect: VibrationEffect) {
        val motor = vibrator ?: return
        if (touchFeedback && Build.VERSION.SDK_INT >= 33) {
            motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            val usage = if (touchFeedback) AudioAttributes.USAGE_ASSISTANCE_SONIFICATION else AudioAttributes.USAGE_MEDIA
            motor.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
        }
    }

    fun stop() {
        scheduledClicks.removeCallbacksAndMessages(null)
        val wasRichTap = richTapActive
        richTapActive = false
        runCatching { vibrator?.cancel() }
        if (wasRichTap) vendor?.stopEffect()?.let { effect -> runCatching { play(effect) } }
    }
}

internal class HapticWaveform(val times: LongArray, val levels: IntArray) {
    fun effect(repeat: Boolean): VibrationEffect? = if (levels.any { it > 0 })
        VibrationEffect.createWaveform(times, levels, if (repeat) 0 else -1) else null

    companion object {
        fun sample(pattern: HapticPattern, start: Int, end: Int, amplitude: Boolean): HapticWaveform {
            val interval = if (pattern.hasTapPoints) 8 else if (amplitude) 16 else 32
            val times = ArrayList<Long>()
            val levels = ArrayList<Int>()
            var time = start
            while (time < end) {
                val duration = minOf(interval, end - time)
                val strength = pattern.at(time + duration / 2).amplitude
                val level = if (amplitude) (strength * 255).roundToInt() else if (strength >= .16f) 255 else 0
                if (levels.lastOrNull() == level) times[times.lastIndex] += duration.toLong()
                else {
                    times += duration.toLong()
                    levels += level
                }
                time += duration
            }
            return HapticWaveform(times.toLongArray(), levels.toIntArray())
        }
    }
}
