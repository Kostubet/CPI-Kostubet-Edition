package com.cosmod.launcherglass.haptic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class HapticStyle {
    BALANCED, SMOOTH, DYNAMIC, BASS_FOCUS, MELODY_FLOW, PRECISION, CUSTOM, TAPS, MIXED;

    fun options() = when (this) {
        BALANCED, CUSTOM -> GeneratorOptions()
        SMOOTH -> GeneratorOptions(smoothness = 88, detail = 37, impulseMs = 142, repetitions = 5)
        DYNAMIC -> GeneratorOptions(strength = 90, dynamics = 85, sensitivity = 75, detail = 78)
        BASS_FOCUS -> GeneratorOptions(bass = 94, melody = 15, impulseMs = 90)
        MELODY_FLOW -> GeneratorOptions(smoothness = 80, bass = 20, melody = 96, impulseMs = 155)
        PRECISION -> GeneratorOptions(smoothness = 23, detail = 82, impulseMs = 50, noise = 35)
        TAPS -> GeneratorOptions(sensitivity = 74, strength = 88, detail = 82, repetitions = 10, noise = 30)
        MIXED -> GeneratorOptions(strength = 58, tapStrength = 82, tapSensitivity = 74, repetitions = 6)
    }
}

internal data class GeneratorOptions(
    val sensitivity: Int = 58,
    val detail: Int = 58,
    val strength: Int = 76,
    val smoothness: Int = 58,
    val bass: Int = 63,
    val melody: Int = 52,
    val impulseMs: Int = 105,
    val repetitions: Int = 8,
    val dynamics: Int = 52,
    val noise: Int = 24,
    val tapStrength: Int = strength,
    val tapSensitivity: Int = sensitivity,
) {
    init {
        require(listOf(sensitivity, detail, strength, smoothness, bass, melody, dynamics, noise, tapStrength, tapSensitivity).all { it in 0..100 })
        require(impulseMs in 25..400 && repetitions in 1..16)
    }
}

internal object HapticAnalysis {
    const val SAMPLE_RATE = 16_000
    const val HOP_MS = 20
    private const val HOP = SAMPLE_RATE * HOP_MS / 1000
    private const val FFT_SIZE = 1024

    data class Frame(val rms: Float, val low: Float, val mid: Float, val high: Float, val flux: Float,
        val rise: Float, val onsetMs: Int)
    data class Track(val frames: List<Frame>, val length: Int, val bpm: Int?)

    fun analyze(samples: FloatArray, count: Int): Track {
        require(count >= HOP * 4)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val previous = DoubleArray(FFT_SIZE / 2)
        val window = DoubleArray(FFT_SIZE) { .5 - .5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val subFrame = HOP / 4
        var lastLevel = 0f
        val frames = buildList {
            for (start in 0 until count step HOP) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                var power = 0.0
                var strongestRise = 0f
                var onsetMs = 0
                for (slice in 0..3) {
                    val first = start + slice * subFrame
                    var slicePower = 0.0
                    for (i in first until minOf(first + subFrame, count)) slicePower += samples[i] * samples[i]
                    power += slicePower
                    val level = sqrt(slicePower / subFrame).toFloat()
                    val rise = level - lastLevel
                    if (rise > strongestRise) {
                        strongestRise = rise
                        onsetMs = slice * (HOP_MS / 4)
                    }
                    lastLevel = level
                }
                for (i in 0 until FFT_SIZE) {
                    val at = start + HOP / 2 - FFT_SIZE / 2 + i
                    val sample = if (at in 0 until count) samples[at] else 0f
                    real[i] = sample * window[i]
                    imaginary[i] = 0.0
                }
                transform(real, imaginary)
                var flux = 0.0
                var low = 0.0
                var mid = 0.0
                var high = 0.0
                for (bin in 2 until previous.size) {
                    val energy = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / (FFT_SIZE * FFT_SIZE)
                    val magnitude = ln(1 + 65 * sqrt(energy))
                    flux += (magnitude - previous[bin]).coerceAtLeast(0.0) * if (bin < 16) 2.5 else 1.0
                    previous[bin] = magnitude
                    when {
                        bin < 16 -> low += energy
                        bin < 130 -> mid += energy
                        else -> high += energy
                    }
                }
                add(Frame(sqrt(power / HOP).toFloat(), sqrt(low).toFloat(), sqrt(mid).toFloat(),
                    sqrt(high).toFloat(), (flux / 40).toFloat(), strongestRise, onsetMs))
            }
        }
        return Track(frames, (count * 1000L / SAMPLE_RATE).toInt().coerceIn(80, MAX_TRACK_MS), tempo(frames))
    }

    fun generate(track: Track, style: HapticStyle, options: GeneratorOptions): List<HapticPoint> {
        val frames = track.frames
        val peak = frames.map { it.rms }.sorted()[(frames.lastIndex * .94f).toInt()].coerceAtLeast(.015f)
        val gate = .0016f + peak * options.noise * .0011f
        val attack = .36f + .58f * (1 - options.smoothness * .01f)
        val decay = .09f + (100 - options.smoothness) * .0025f
        val onsetDecay = exp(-HOP_MS.toFloat() / options.impulseMs)
        val accents = findAccents(track, peak, gate, options.sensitivity, options.repetitions)
        val taps = if (style == HapticStyle.TAPS || style == HapticStyle.MIXED) {
            findAccents(track, peak, gate, options.tapSensitivity, options.repetitions).mapNotNull {
                val strength = (.24f + it.strength * .76f) * options.tapStrength * .01f
                HapticClick(it.time, strength).takeIf { strength > 0f }
            }
        } else emptyList()
        if (style == HapticStyle.TAPS) return tapPoints(track.length, taps)
        var flowing = 0f
        var accent = 0f
        var nextAccent = 0
        val points = ArrayList<HapticPoint>(frames.size + 1)
        points += HapticPoint(0, 0f)
        for (i in frames.indices) {
            val f = frames[i]
            val hit = accents.getOrNull(nextAccent)?.takeIf { it.time / HOP_MS == i }
            val ms = (hit?.time ?: (i * HOP_MS + HOP_MS / 2)).coerceAtLeast(1)
            if (ms >= track.length) break
            val level = (f.rms / peak).coerceIn(0f, 1.25f)
            val bass = (f.low / peak).coerceIn(0f, 1f)
            val melody = (f.mid / peak).coerceIn(0f, 1f)
            val brightness = (f.high / peak).coerceIn(0f, 1f)
            val quiet = f.rms <= gate
            accent *= onsetDecay
            if (hit != null) {
                // Keep the preceding frame flat right up to the real attack.
                if (ms - 1 > points.last().ms) points += points.last().copy(ms = ms - 1)
                accent = maxOf(accent, hit.strength)
                nextAccent++
            }
            val body = level * .28f + bass * options.bass * .0062f + melody * options.melody * .006f
            val strong = (body + accent * (.26f + options.detail * .007f) + brightness * options.detail * .0012f)
                .coerceIn(0f, 1f)
            val target = if (quiet) 0f else (strong + options.dynamics * .01f * (strong * strong - strong)) *
                options.strength * .01f * if (style == HapticStyle.PRECISION && accent < .12f) .43f else 1f
            flowing += (target - flowing) * if (target > flowing) attack else decay
            if (quiet && flowing < .018f) flowing = 0f
            points += HapticPoint(ms, flowing.coerceIn(0f, 1f), sharpness = (.24f + brightness * .46f + accent * .3f)
                .coerceIn(0f, 1f))
        }
        points += HapticPoint(track.length, 0f)
        if (taps.isEmpty()) return fitPointBudget(points, MAX_HAPTIC_POINTS)
        val mixed = java.util.TreeMap<Int, HapticPoint>()
        val slideBudget = (MAX_HAPTIC_POINTS - taps.size * 3).coerceAtLeast(2)
        for (point in fitPointBudget(points, slideBudget)) mixed[point.ms] = point
        for (tap in taps) {
            val from = tap.time
            val to = from + 32
            if (to >= track.length) continue
            mixed.subMap(from, true, to, true).clear()
            mixed[from] = HapticPoint(from, 0f)
            mixed[from + 4] = HapticPoint(from + 4, tap.strength, tapKind = HapticTap.NATIVE)
            mixed[to] = HapticPoint(to, 0f, curve = HapticCurve.EASE_OUT)
        }
        return mixed.values.toList()
    }

    private data class Accent(val time: Int, val strength: Float)

    private fun findAccents(track: Track, peak: Float, gate: Float, sensitivity: Int, repetitions: Int): List<Accent> {
        val minimumGap = 1000 / repetitions
        val response = sensitivity * .01f
        val scoreFloor = .14f - .09f * response
        var baseline = 0f
        val peaks = ArrayList<Accent>()
        for ((index, frame) in track.frames.withIndex()) {
            val time = index * HOP_MS + frame.onsetMs
            if (time >= track.length) break
            val rise = (frame.rise / peak).coerceAtMost(1f)
            val spectrum = (frame.flux * 5f).coerceAtMost(1f)
            val score = rise * (.80f + spectrum * .20f)
            val limit = maxOf(scoreFloor, baseline * (2.4f - response))
            baseline += .11f * (score - baseline)
            if (frame.rms <= gate || score < limit) continue
            val accent = Accent(time, (score * 1.3f + .12f).coerceAtMost(1f))
            // Choose the strongest onset during the refractory period, even if a
            // quieter transient arrived first. The surviving timestamp stays unquantized.
            val previous = peaks.lastOrNull()
            if (previous == null || time - previous.time >= minimumGap) peaks += accent
            else if (time - previous.time <= 2 * HOP_MS && accent.strength > previous.strength) peaks[peaks.lastIndex] = accent
        }
        return peaks
    }

    private fun fitPointBudget(points: List<HapticPoint>, budget: Int): List<HapticPoint> {
        if (points.size <= budget) return points
        // Keep abrupt attacks, ends of silent regions and the timeline's endpoints.
        val important = points.indices.drop(1).dropLast(1).sortedByDescending { index ->
            val current = points[index]
            val before = points[index - 1]
            val after = points[index + 1]
            val fraction = (current.ms - before.ms).toFloat() / (after.ms - before.ms)
            kotlin.math.abs(current.amplitude - (before.amplitude + (after.amplitude - before.amplitude) * fraction))
        }.take(budget - 2)
        return (important + listOf(0, points.lastIndex)).sorted().map(points::get)
    }

    private fun tempo(frames: List<Frame>): Int? {
        if (frames.size < 145) return null
        val onset = FloatArray(frames.size)
        var floor = 0f
        for (i in frames.indices) {
            val flux = frames[i].flux
            onset[i] = if (frames[i].rms > .003f) (flux - floor * 1.1f).coerceAtLeast(0f) + frames[i].rise else 0f
            floor += .10f * (flux - floor)
        }
        var score = .22
        var best = 0
        for (lag in 16..55) {
            var product = 0.0
            var first = 0.0
            var second = 0.0
            for (i in lag until onset.size) {
                val a = onset[i]
                val b = onset[i - lag]
                product += a * b
                first += a * a
                second += b * b
            }
            val matched = product / sqrt(first * second).coerceAtLeast(1e-8)
            if (matched > score) {
                score = matched
                best = lag
            }
        }
        return if (best == 0) null else (60_000f / (best * HOP_MS)).roundToInt()
    }

    private fun transform(real: DoubleArray, imaginary: DoubleArray) {
        var reverse = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (reverse and bit != 0) {
                reverse = reverse xor bit
                bit = bit shr 1
            }
            reverse = reverse xor bit
            if (i < reverse) {
                val value = real[i]
                real[i] = real[reverse]
                real[reverse] = value
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val cosStep = cos(-2 * PI / size)
            val sinStep = sin(-2 * PI / size)
            for (start in 0 until FFT_SIZE step size) {
                var c = 1.0
                var s = 0.0
                for (i in start until start + half) {
                    val other = i + half
                    val re = real[other] * c - imaginary[other] * s
                    val im = imaginary[other] * c + real[other] * s
                    real[other] = real[i] - re
                    imaginary[other] = imaginary[i] - im
                    real[i] += re
                    imaginary[i] += im
                    val next = c * cosStep - s * sinStep
                    s = c * sinStep + s * cosStep
                    c = next
                }
            }
            size *= 2
        }
    }
}
