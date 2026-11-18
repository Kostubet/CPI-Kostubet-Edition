package com.cosmod.launcherglass.haptic

import android.os.Process
import android.os.VibrationEffect
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

/** ColorOS RichTap HE 2.0, discovered through the installed framework. Frequencies use the OEM relative scale. */
internal class RichTapHaptics private constructor(private val createPattern: Method, private val setParameter: Method?) {
    fun effect(pattern: HapticPattern, start: Int, end: Int, repeating: Boolean = false): VibrationEffect? = runCatching {
        val payload = richTapPayload(pattern, start, end, Process.myPid(), sequence.incrementAndGet()) ?: return null
        createPattern.invoke(null, payload, if (repeating) Int.MAX_VALUE else 1, 0, 255, 0) as? VibrationEffect
    }.getOrNull()

    fun mixedEffect(pattern: HapticPattern, start: Int, end: Int, repeating: Boolean = false): VibrationEffect? = runCatching {
        val payload = mixedRichTapPayload(pattern, start, end, Process.myPid(), sequence.incrementAndGet()) ?: return null
        createPattern.invoke(null, payload, if (repeating) Int.MAX_VALUE else 1, 0, 255, 0) as? VibrationEffect
    }.getOrNull()

    fun tapEffect(taps: List<HapticClick>, duration: Int): VibrationEffect? = runCatching {
        if (duration > 40_000 || taps.size > 160) return null
        val payload = richTapTapPayload(taps, Process.myPid(), sequence.incrementAndGet())
        createPattern.invoke(null, payload, 1, 0, 255, 0) as? VibrationEffect
    }.getOrNull()

    // ColorOS HapticPlayer.stop sends a zero parameter packet to the RichTap engine.
    fun stopEffect(): VibrationEffect? = runCatching {
        setParameter?.invoke(null, 0, 0, 0) as? VibrationEffect
    }.getOrNull()

    companion object {
        private val sequence = AtomicInteger()
        fun discover(): RichTapHaptics? = runCatching {
            val api = Class.forName("android.os.RichTapVibrationEffect")
            val support = api.getMethod("checkIfRichTapSupport").invoke(null) as? Int ?: return null
            if ((support ushr 8 and 255) < 23) return null
            val int = Int::class.javaPrimitiveType
            val method = api.getMethod("createPatternHeWithParam", IntArray::class.java, int, int, int, int)
            if (!VibrationEffect::class.java.isAssignableFrom(method.returnType)) return null
            val setParameter = runCatching { api.getMethod("createPatternHeParameter", int, int, int) }.getOrNull()
            RichTapHaptics(method, setParameter)
        }.getOrNull()
    }
}

internal fun richTapTapPayload(taps: List<HapticClick>, pid: Int, sequence: Int): IntArray {
    val groups = taps.chunked(16)
    val payload = ArrayList<Int>(5 + groups.size * 3 + taps.size * 7)
    payload.addAll(listOf(2, 2, pid, sequence, groups.size or (groups.size shl 16)))
    groups.forEachIndexed { index, group ->
        val first = group.first().time
        payload.addAll(listOf(index, first, group.size))
        group.forEach { tap ->
            payload.addAll(listOf(4097, 5, 0, tap.time - first, (tap.strength * 100).roundToInt(), 0, 0))
        }
    }
    return payload.toIntArray()
}

internal fun richTapPayload(pattern: HapticPattern, start: Int, end: Int, pid: Int, sequence: Int): IntArray? {
    val duration = end - start
    if (duration !in 32..40_000) return null
    // One continuous event fits a slide. Longer regions use consecutive HE patterns.
    val events = if (duration <= 5000) 1 else ceil(duration / 4000.0).toInt()
    val payload = ArrayList<Int>()
    payload.addAll(listOf(2, 2, pid, sequence, events or (events shl 16)))
    var active = false
    for (index in 0 until events) {
        val first = start + duration * index / events
        val last = start + duration * (index + 1) / events
        val span = last - first
        val count = (ceil(span / 32.0).toInt() + 1).coerceIn(3, 16)
        payload.addAll(listOf(index, first - start, 1)) // Pattern index, absolute offset, event count.
        payload.addAll(listOf(4096, 6 + count * 3, 0, 0, 100, 0, span, count))
        for (i in 0 until count) {
            val time = span * i / (count - 1)
            val point = pattern.at(first + time)
            val amplitude = if (first + time == start || first + time == end) 0 else (point.amplitude * 100).roundToInt()
            active = active || amplitude > 0
            payload.addAll(listOf(time, amplitude, point.pitch.roundToInt()))
        }
    }
    return if (active) payload.toIntArray() else null
}

/** HE2 can carry transient and continuous events in the same effect, sharing the same hardware clock. */
internal fun mixedRichTapPayload(pattern: HapticPattern, start: Int, end: Int, pid: Int, sequence: Int): IntArray? {
    val duration = end - start
    if (duration !in 32..40_000 || pattern.points.any { it.hz > 0f && it.amplitude > 0f }) return null
    val taps = pattern.tapHits(start, end)
    if (taps.isEmpty() || taps.size > 120) return null
    val events = ArrayList<Pair<Int, List<Int>>>()
    fun addSlide(from: Int, until: Int) {
        val span = until - from
        if (span < 8) return
        val count = (ceil(span / 32.0).toInt() + 1).coerceIn(3, 16)
        val samples = ArrayList<Int>(count * 3)
        var active = false
        for (i in 0 until count) {
            val time = span * i / (count - 1)
            val point = pattern.at(start + from + time)
            val level = if (from + time == 0 || from + time == duration) 0 else (point.amplitude * 100).roundToInt()
            active = active || level > 0
            samples.addAll(listOf(time, level, point.pitch.roundToInt()))
        }
        if (active) events += from to (listOf(4096, 6 + samples.size, 0, 0, 100, 0, span, count) + samples)
    }
    var from = 0
    for (tap in taps) {
        val lead = (tap.time - 4).coerceAtLeast(from)
        var next = from
        while (lead - next > 480) {
            addSlide(next, next + 480)
            next += 480
        }
        addSlide(next, lead)
        events += tap.time to listOf(4097, 5, 0, 0, (tap.strength * 100).roundToInt(), 0, 0)
        from = (tap.time + 28).coerceIn(from, duration)
    }
    while (duration - from > 480) {
        addSlide(from, from + 480)
        from += 480
    }
    addSlide(from, duration)
    if (events.size > 160) return null
    val size = events.size
    val payload = mutableListOf(2, 2, pid, sequence, size or (size shl 16))
    events.forEachIndexed { index, (time, event) ->
        payload.addAll(listOf(index, time, 1))
        payload.addAll(event)
    }
    return payload.toIntArray()
}
