package com.cosmod.launcherglass.haptic

import org.junit.Assert.*
import org.junit.Test

class WinterMelodyTest {
    @Test fun playerClockFiresEveryAttackOnceDespiteUnevenTicks() {
        val cursor = WinterMelody.Cursor()
        val fired = mutableListOf<WinterMelody.Note>()
        for (ms in 0..WinterMelody.DURATION_MS step 7) fired += cursor.due(ms)
        assertEquals(WinterMelody.notes, fired)
        assertTrue(cursor.due(WinterMelody.DURATION_MS).isEmpty())
    }

    @Test fun frozenUiDoesNotReplayOldAttacksOrAFinaleLate() {
        val cursor = WinterMelody.Cursor()
        assertEquals(listOf(WinterMelody.notes.first()), cursor.due(0))
        assertEquals(listOf(WinterMelody.notes[8]), cursor.due(1290))
        assertFalse(cursor.startSlide(1600))
        assertFalse(cursor.startSlide(3000))
        assertTrue(cursor.due(3000).isEmpty())
    }

    @Test fun finalTapAndSlideAreSeparatedAndSlideIsNotRepeated() {
        val cursor = WinterMelody.Cursor()
        assertEquals(listOf(WinterMelody.notes.last()), cursor.due(WinterMelody.SLIDE_START_MS))
        assertFalse(cursor.startSlide(WinterMelody.SLIDE_START_MS))
        val pulseEnd = WinterMelody.SLIDE_START_MS + WinterMelody.notes.last().pulseMs.toInt()
        assertFalse(cursor.startSlide(pulseEnd - 1))
        assertTrue(cursor.startSlide(pulseEnd))
        assertFalse(cursor.startSlide(pulseEnd + 7))
    }

    @Test fun slideUsesReportedFrequencyRangeAndEndsInSilence() {
        val slide = WinterMelody.slide(80f..260f)
        assertTrue(slide.points.all { it.hz in 80f..260f && it.pitch == 0f })
        assertEquals(0f, slide.points.first().amplitude, 0f)
        assertEquals(0f, slide.points.last().amplitude, 0f)
        assertTrue(slide.points.maxOf { it.amplitude } <= .42f)
        val fallback = WinterMelody.slide(null)
        assertTrue(fallback.points.all { it.hz == 0f })
        assertTrue(fallback.points.any { it.pitch != 0f })
    }
}
