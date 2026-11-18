package com.cosmod.launcherglass.haptic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class HapticStyle {
    BALANCED, SMOOTH, DYNAMIC, BASS_FOCUS, MELODY_FLOW, PRECISION, CUSTOM, TAPS;

    fun options() = when (this) {
        BALANCED, CUSTOM -> GeneratorOptions()
        SMOOTH -> GeneratorOptions(smoothness = 88, detail = 37, impulseMs = 142, repetitions = 5)
        DYNAMIC -> GeneratorOptions(strength = 90, dynamics = 85, sensitivity = 75, detail = 78)
        BASS_FOCUS -> GeneratorOptions(bass = 94, melody = 15, impulseMs = 90)
        MELODY_FLOW -> GeneratorOptions(smoothness = 80, bass = 20, melody = 96, impulseMs = 155)
        PRECISION -> GeneratorOptions(smoothness = 23, detail = 82, impulseMs = 50, noise = 35)
        TAPS -> GeneratorOptions(sensitivity = 74, strength = 88, detail = 82, repetitions = 10, noise = 30)
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
) {
    init {
        require(listOf(sensitivity, detail, strength, smoothness, bass, melody, dynamics, noise).all { it in 0..100 })
        require(impulseMs in 25..400 && repetitions in 1..16)
    }
}

internal object HapticAnalysis {
    const val SAMPLE_RATE = 16_000
    const val HOP_MS = 20
    private const val HOP = SAMPLE_RATE * HOP_MS / 1000
    private const val FFT_SIZE = 1024

    data class Frame(val rms: Float, val low: Float, val mid: Float, val high: Float, val flux: Float)
    data class Track(val frames: List<Frame>, val length: Int, val bpm: Int?)

    fun analyze(samples: FloatArray, count: Int): Track {
        require(count >= HOP * 4)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val previous = DoubleArray(FFT_SIZE / 2)
        val window = DoubleArray(FFT_SIZE) { .5 - .5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        val frames = buildList {
            for (start in 0 until count step HOP) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                var power = 0.0
                for (i in 0 until FFT_SIZE) {
                    val sample = if (i < count - start) samples[start + i] else 0f
                    real[i] = sample * window[i]
                    imaginary[i] = 0.0
                    if (i < HOP) power += sample * sample
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
                    sqrt(high).toFloat(), (flux / 40).toFloat()))
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
        val minimumGap = (1000 / options.repetitions / HOP_MS).coerceAtLeast(1)
        val beatGap = track.bpm?.let { 60_000 / it / HOP_MS }
        var baseline = .02f
        var flowing = 0f
        var accent = 0f
        var lastAccent = -1000
        val points = ArrayList<HapticPoint>(frames.size + 1)
        val taps = if (style == HapticStyle.TAPS) ArrayList<HapticClick>() else null
        points += HapticPoint(0, 0f)
        for (i in frames.indices) {
            val ms = (i + 1) * HOP_MS
            if (ms >= track.length) break
            val f = frames[i]
            val level = (f.rms / peak).coerceIn(0f, 1.25f)
            val bass = (f.low / peak).coerceIn(0f, 1f)
            val melody = (f.mid / peak).coerceIn(0f, 1f)
            val brightness = (f.high / peak).coerceIn(0f, 1f)
            val t = options.sensitivity * .01f
            val periodic = beatGap != null && abs(i - lastAccent - beatGap) <= 2
            val threshold = baseline * (1.75f - .78f * t) * if (periodic) .87f else 1f
            val transient = f.flux * (.75f + .5f * bass)
            baseline += .072f * (transient - baseline)
            val quiet = f.rms <= gate
            accent *= onsetDecay
            val hit = !quiet && i - lastAccent >= minimumGap && transient > threshold.coerceAtLeast(.009f)
            if (hit) {
                accent = (.24f + (transient - threshold) * .85f + bass * .42f).coerceAtMost(.9f)
                lastAccent = i
            }
            val body = level * .28f + bass * options.bass * .0062f + melody * options.melody * .006f
            val strong = (body + accent * (.26f + options.detail * .007f) + brightness * options.detail * .0012f)
                .coerceIn(0f, 1f)
            val target = if (quiet) 0f else (strong + options.dynamics * .01f * (strong * strong - strong)) *
                options.strength * .01f * if (style == HapticStyle.PRECISION && accent < .12f) .43f else 1f
            if (taps != null) {
                if (hit) taps += HapticClick(i * HOP_MS, target.coerceIn(0f, 1f))
                continue
            }
            flowing += (target - flowing) * if (target > flowing) attack else decay
            if (quiet && flowing < .018f) flowing = 0f
            points += HapticPoint(ms, flowing.coerceIn(0f, 1f), sharpness = (.24f + brightness * .46f + accent * .3f)
                .coerceIn(0f, 1f))
        }
        if (taps != null) return tapPoints(track.length, taps)
        points += HapticPoint(track.length, 0f)
        return points
    }

    private fun tempo(frames: List<Frame>): Int? {
        if (frames.size < 145) return null
        val onset = FloatArray(frames.size)
        var floor = 0f
        for (i in frames.indices) {
            val flux = frames[i].flux
            onset[i] = if (frames[i].rms > .003f) (flux - floor * 1.1f).coerceAtLeast(0f) else 0f
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
