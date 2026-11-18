package com.cosmod.launcherglass.haptic

import android.os.Process
import android.os.VibrationEffect
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.roundToInt

/** ColorOS RichTap HE 2.0, discovered through the installed framework. Frequencies use the OEM relative scale. */
internal class RichTapHaptics private constructor(private val createPattern: Method) {
    fun effect(pattern: HapticPattern, start: Int, end: Int, repeating: Boolean = false): VibrationEffect? = runCatching {
        val payload = richTapPayload(pattern, start, end, Process.myPid(), sequence.incrementAndGet()) ?: return null
        createPattern.invoke(null, payload, if (repeating) Int.MAX_VALUE else 1, 0, 255, 0) as? VibrationEffect
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
            RichTapHaptics(method)
        }.getOrNull()
    }
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
