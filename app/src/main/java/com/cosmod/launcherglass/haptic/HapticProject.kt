package com.cosmod.launcherglass.haptic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val MAX_TRACK_MS = 120_000
internal const val MAX_HAPTIC_POINTS = 6_100

internal enum class HapticCurve {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTH_STEP, BEZIER;

    fun fraction(t: Float, first: Float, second: Float): Float = when (this) {
        LINEAR -> t
        EASE_IN -> t * t
        EASE_OUT -> t * (2 - t)
        EASE_IN_OUT -> if (t < .5f) 2 * t * t else 1 - 2 * (1 - t) * (1 - t)
        SMOOTH_STEP -> t * t * (3 - 2 * t)
        BEZIER -> {
            val u = 1 - t
            3 * u * u * t * first + 3 * u * t * t * second + t * t * t
        }
    }
}

internal enum class HapticSlide { INTENSITY, FREQUENCY, RISE, FALL, WAVE, PULSE_FLOW, SHARPNESS, CUSTOM, TAPS }
internal enum class HapticChannel { AMPLITUDE, FREQUENCY, RICHTAP, SHARPNESS }
internal enum class HapticTap { NONE, NATIVE, PULSE }

internal data class HapticPoint(
    val ms: Int,
    val amplitude: Float,
    val hz: Float = 0f,
    val sharpness: Float = .45f,
    val curve: HapticCurve = HapticCurve.LINEAR,
    val bezierIn: Float = .25f,
    val bezierOut: Float = .75f,
    val pitch: Float = 0f,
    val tapKind: HapticTap = HapticTap.NONE,
) {
    val tap get() = tapKind != HapticTap.NONE
    init {
        require(ms in 0..MAX_TRACK_MS && amplitude in 0f..1f && hz in 0f..2000f &&
            sharpness in 0f..1f && bezierIn in 0f..1f && bezierOut in 0f..1f && pitch in -100f..100f)
    }
}

internal data class HapticPattern(
    val name: String,
    val length: Int,
    val points: List<HapticPoint>,
    val audio: String? = null,
    val intensity: Int = 100,
    val offset: Int = 0,
    val fadeIn: Int = 0,
    val fadeOut: Int = 0,
    val style: HapticStyle? = null,
    val generator: GeneratorOptions = GeneratorOptions(),
    val repeat: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name.length <= 80 && length in 80..MAX_TRACK_MS)
        require(points.size in 2..MAX_HAPTIC_POINTS && points.first().ms == 0 && points.last().ms == length)
        require((1 until points.size).all { points[it - 1].ms < points[it].ms })
        require(intensity in 0..150 && offset in -1500..1500 && fadeIn in 0..5000 && fadeOut in 0..5000)
    }

    fun at(time: Int): HapticPoint {
        val ms = time.coerceIn(0, length)
        val sourceTime = ms - offset
        val index = points.binarySearchBy(sourceTime) { it.ms }
        val point = when {
            sourceTime !in 0..length -> HapticPoint(ms, 0f)
            index >= 0 -> points[index]
            else -> {
                val next = -(index + 1)
                val a = points[next - 1]
                val b = points[next]
                val t = b.curve.fraction((sourceTime - a.ms).toFloat() / (b.ms - a.ms), b.bezierIn, b.bezierOut)
                HapticPoint(ms, lerp(a.amplitude, b.amplitude, t), lerp(a.hz, b.hz, t),
                    lerp(a.sharpness, b.sharpness, t), pitch = lerp(a.pitch, b.pitch, t))
            }
        }
        val attack = if (fadeIn == 0) 1f else (ms.toFloat() / fadeIn).coerceAtMost(1f)
        val release = if (fadeOut == 0) 1f else ((length - ms).toFloat() / fadeOut).coerceAtMost(1f)
        return point.copy(ms = ms, amplitude = (point.amplitude * intensity * .01f * attack * release).coerceIn(0f, 1f))
    }

    fun replace(point: HapticPoint): HapticPattern {
        val at = points.binarySearchBy(point.ms) { it.ms }
        if (at < 0 && points.size == MAX_HAPTIC_POINTS) return this
        val updated = points.toMutableList()
        if (at < 0) updated.add(-at - 1, point) else updated[at] = point
        return copy(points = updated)
    }

    val hasTapPoints get() = points.any { it.tap && it.amplitude > 0f }

    fun nativeTaps(start: Int, end: Int): List<HapticClick>? {
        if (points.any { it.tapKind != HapticTap.NATIVE && it.amplitude > 0f }) return null
        return points.mapNotNull { point ->
            val time = point.ms + offset
            if (!point.tap || time !in start until end) return@mapNotNull null
            at(time).amplitude.takeIf { it > 0f }?.let { HapticClick(time - start, it) }
        }.takeIf { it.isNotEmpty() }
    }

    fun resetVibration(): HapticPattern = HapticPattern(name, length,
        listOf(HapticPoint(0, 0f), HapticPoint(length, 0f)), audio = audio)

    companion object {
        fun empty(name: String) = HapticPattern(name, 2_000, listOf(HapticPoint(0, 0f), HapticPoint(2_000, 0f)))
    }
}

internal data class HapticAdsr(val attack: Int = 160, val decay: Int = 200, val sustain: Int = 55, val release: Int = 220)
internal data class HapticClick(val time: Int, val strength: Float)

internal data class HapticTapTrain(
    val count: Int = 3,
    val spacing: Int = 220,
    val delay: Int = 0,
    val swing: Int = 0,
    val firstStrength: Int = 70,
    val lastStrength: Int = 70,
    val customPulse: Boolean = false,
    val pulseWidth: Int = 28,
) {
    val length get() = delay + count * spacing

    fun points(): List<HapticPoint> {
        val clicks = List(count) { index ->
            val time = delay + index * spacing + (index % 2) * spacing * swing / 100
            val fraction = index.toFloat() / (count - 1).coerceAtLeast(1)
            HapticClick(time, lerp(firstStrength * .01f, lastStrength * .01f, fraction))
        }
        return tapPoints(length, clicks, pulseWidth.takeIf { customPulse })
    }
}

internal fun tapPoints(length: Int, clicks: List<HapticClick>, pulseWidth: Int? = null): List<HapticPoint> = buildList {
    val kind = if (pulseWidth == null) HapticTap.NATIVE else HapticTap.PULSE
    add(HapticPoint(0, 0f))
    for (click in clicks) {
        val start = maxOf(last().ms, click.time)
        val end = minOf(start + (pulseWidth ?: 32), length)
        if (end - start < 12 || click.strength <= 0f) continue
        if (last().ms != start) add(HapticPoint(start, 0f))
        add(HapticPoint(start + 4, click.strength, tapKind = kind))
        add(HapticPoint(end, 0f, curve = HapticCurve.EASE_OUT))
    }
    if (last().ms < length) add(HapticPoint(length, 0f))
}

internal fun slidePoints(
    type: HapticSlide,
    start: Int,
    duration: Int,
    amplitude: Float,
    sharpness: Float,
    lowHz: Float,
    highHz: Float,
    density: Int,
    adsr: HapticAdsr,
    curve: HapticCurve,
): List<HapticPoint> {
    val attack = adsr.attack.coerceIn(1, duration / 2)
    val decay = adsr.decay.coerceIn(1, duration - attack - 1)
    val release = adsr.release.coerceIn(1, duration - attack - decay)
    val fadeStart = duration - release
    val count = minOf(40, duration)
    return (0..count).map { step ->
        val t = step / count.toFloat()
        val ms = (t * duration).roundToInt()
        val envelope = when {
            ms <= attack -> ms.toFloat() / attack
            ms >= fadeStart -> adsr.sustain * .01f * (duration - ms) / release
            ms < attack + decay -> lerp(1f, adsr.sustain * .01f, (ms - attack).toFloat() / decay)
            else -> adsr.sustain * .01f
        }
        val shape = when (type) {
            HapticSlide.INTENSITY -> sin(PI * t).toFloat()
            HapticSlide.RISE -> t * t
            HapticSlide.FALL -> (1 - t) * (1 - t)
            HapticSlide.WAVE -> (.5 - .5 * cos(2 * PI * density * t)).toFloat()
            HapticSlide.PULSE_FLOW -> (.62 - .38 * cos(2 * PI * density * t)).toFloat()
            HapticSlide.CUSTOM -> curve.fraction(t, .1f, .95f)
            else -> 1f
        }
        val frequency = if (type == HapticSlide.FREQUENCY) lerp(lowHz, highHz, t) else lowHz
        val sharp = if (type == HapticSlide.SHARPNESS) lerp(.05f, sharpness, t) else sharpness
        HapticPoint(start + ms, (envelope * shape * amplitude).coerceIn(0f, 1f), frequency, sharp, curve)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = (a + (b - a) * t).coerceIn(minOf(a, b), maxOf(a, b))
