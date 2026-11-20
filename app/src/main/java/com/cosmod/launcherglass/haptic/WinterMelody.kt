package com.cosmod.launcherglass.haptic

/** Attack positions measured from the bundled 48 kHz Vorbis recording, before its reverb tail. */
internal object WinterMelody {
    data class Note(val ms: Int, val strength: Float) {
        // Pulse length still distinguishes accents on motors with fixed amplitude.
        val pulseMs: Long get() = (24 + strength * 26).toLong()
    }
    const val DURATION_MS = 3361
    const val SLIDE_START_MS = 1716
    const val SLIDE_DURATION_MS = 610
    val notes = listOf(
        Note(0, .51f), Note(144, .63f), Note(286, .56f), Note(428, .69f),
        Note(572, .82f), Note(860, .59f), Note(1004, .53f), Note(1146, .70f),
        Note(1288, .75f), Note(1432, .65f), Note(SLIDE_START_MS, .94f),
    )

    class Cursor {
        private var index = 0
        private var finale = false
        fun due(ms: Int): List<Note> = buildList {
            while (index < notes.size && ms >= notes[index].ms) {
                val note = notes[index++]
                if (ms - note.ms <= 45) add(note)
            }
        }
        fun startSlide(ms: Int): Boolean {
            val start = SLIDE_START_MS + notes.last().pulseMs.toInt()
            if (finale || ms < start) return false
            finale = true
            return ms - start < 65
        }
    }

    /** A separate final envelope lets hardware clicks keep their short, crisp transient. */
    fun slide(frequency: ClosedFloatingPointRange<Float>?): HapticPattern {
        val low = frequency?.start ?: 0f
        val high = frequency?.endInclusive ?: 0f
        val middle = (low + high) * .5f
        fun pitch(value: Float) = if (frequency == null) value else 0f
        val points = listOf(
            HapticPoint(0, 0f, middle, .32f, pitch = pitch(14f)),
            HapticPoint(45, .30f, middle, .32f, HapticCurve.SMOOTH_STEP, pitch = pitch(14f)),
            HapticPoint(160, .42f, low + (high - low) * .64f, .42f, HapticCurve.SMOOTH_STEP, pitch = pitch(30f)),
            HapticPoint(365, .19f, low + (high - low) * .34f, .29f, HapticCurve.SMOOTH_STEP, pitch = pitch(-8f)),
            HapticPoint(SLIDE_DURATION_MS, 0f, low + (high - low) * .24f, .2f, HapticCurve.SMOOTH_STEP, pitch = pitch(-25f)),
        )
        return HapticPattern("Winter finale", SLIDE_DURATION_MS, points)
    }
}
