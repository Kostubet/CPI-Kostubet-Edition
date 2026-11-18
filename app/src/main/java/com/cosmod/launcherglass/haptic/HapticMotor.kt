package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class HapticMotor(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
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

    fun effect(pattern: HapticPattern, start: Int, end: Int): VibrationEffect? {
        if (!present || end <= start) return null
        val useFrequency = frequency != null && pattern.points.any { it.hz > 0f }
        val useRichTap = vendor != null && pattern.points.any { it.pitch != 0f }
        if (useRichTap) vendor.effect(pattern, start, end)?.let { return it }
        if (Build.VERSION.SDK_INT >= 36 && envelope != null) {
            val effect = nativeEnvelope(pattern, start, end, envelope, useFrequency)
            if (effect != null) return effect
        }
        if (!useRichTap && end - start <= 5000) vendor?.effect(pattern, start, end)?.let { return it }
        return HapticWaveform.sample(pattern, start, end, amplitude).effect(false)
    }

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
        useFrequency: Boolean): VibrationEffect? = runCatching {
        val duration = end - start
        if (duration < limits.minimum * 2 || duration > limits.duration) return null
        val minSegments = ceil(duration.toDouble() / limits.maximum).toInt().coerceAtLeast(2)
        val maxSegments = minOf(duration / limits.minimum, limits.points)
        if (minSegments > maxSegments) return null
        val step = 20.coerceIn(limits.minimum, limits.maximum)
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
        val effect = effect(pattern, start, end) ?: return null
        return { play(effect) }
    }

    @Suppress("DEPRECATION")
    private fun play(effect: VibrationEffect) {
        vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    }

    fun stop() { vibrator?.cancel() }
}

internal class HapticWaveform(val times: LongArray, val levels: IntArray) {
    fun effect(repeat: Boolean): VibrationEffect? = if (levels.any { it > 0 })
        VibrationEffect.createWaveform(times, levels, if (repeat) 0 else -1) else null

    companion object {
        fun sample(pattern: HapticPattern, start: Int, end: Int, amplitude: Boolean): HapticWaveform {
            val interval = if (amplitude) 16 else 32
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
