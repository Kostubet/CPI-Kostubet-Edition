package com.cosmod.launcherglass.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HapticCoreTest {
    @Test fun waveformPreservesDurationAcrossSilenceAndAppliesSyncOffset() {
        val pattern = HapticPattern("pulse", 700, listOf(
            HapticPoint(0, 0f), HapticPoint(80, 0f), HapticPoint(100, 1f),
            HapticPoint(180, 1f), HapticPoint(210, 0f), HapticPoint(700, 0f),
        ), offset = 50)
        val waveform = HapticWaveform.sample(pattern, 0, pattern.length, true)
        assertEquals(700L, waveform.times.sum())
        assertTrue(waveform.times.first() >= 112)
        assertTrue(waveform.levels.any { it == 255 })
        assertEquals(0, waveform.levels.last())
    }

    @Test fun slideCarriesFrequencyBetweenControlPoints() {
        val length = 660
        val points = slidePoints(HapticSlide.FREQUENCY, 0, length, .8f, .6f, 120f, 170f, 3,
            HapticAdsr(), HapticCurve.LINEAR)
        val slide = HapticPattern("slide", length, points)
        assertEquals(0f, slide.at(0).amplitude, 0f)
        assertTrue(slide.at(150).amplitude > .4f)
        assertEquals(145f, slide.at(length / 2).hz, .2f)
        assertEquals(0f, slide.at(length).amplitude, 0f)
        val curve = slide.copy(points = points.map { it.copy(pitch = -40f + 80f * it.ms / length) })
        assertEquals(0f, curve.at(length / 2).pitch, .2f)
    }

    @Test fun analyzedBeatProducesAccentsAndSilenceProducesNoVibration() {
        val rate = HapticAnalysis.SAMPLE_RATE
        val samples = FloatArray(rate * 5) { i ->
            val local = i % (rate / 2)
            if (i >= rate * 4 || local >= rate / 16) 0f
            else (.65 * sin(i * 2 * PI * 88 / rate)).toFloat()
        }
        val track = HapticAnalysis.analyze(samples, samples.size)
        assertTrue(track.bpm?.let { it in 117..123 } == true)
        val bass = HapticAnalysis.generate(track, HapticStyle.BASS_FOCUS, HapticStyle.BASS_FOCUS.options())
        val smooth = HapticAnalysis.generate(track, HapticStyle.SMOOTH, HapticStyle.SMOOTH.options())
        assertTrue(bass.filter { it.ms in 0..3800 }.maxOf { it.amplitude } > .12f)
        assertTrue(bass.filter { it.ms >= 4600 }.all { it.amplitude == 0f })
        assertNotEquals(bass, smooth)
        val quiet = HapticAnalysis.analyze(FloatArray(rate), rate)
        assertTrue(HapticAnalysis.generate(quiet, HapticStyle.DYNAMIC, GeneratorOptions()).all { it.amplitude == 0f })
    }

    @Test fun richTapEmitsOneBoundedContinuousEffectWithRelativeFrequency() {
        val pattern = HapticPattern("sweep", 900, listOf(
            HapticPoint(0, 0f, pitch = -75f),
            HapticPoint(180, .75f, pitch = -40f),
            HapticPoint(720, .8f, pitch = 55f),
            HapticPoint(900, 0f, pitch = 80f),
        ))
        val result = requireNotNull(richTapPayload(pattern, 0, 900, 121, 12))
        assertEquals(listOf(2, 2, 121, 12, 65_537, 0, 0, 1), result.take(8))
        assertEquals(4096, result[8])
        assertEquals(900, result[14])
        assertEquals(16, result[15])
        val points = result.drop(16).chunked(3)
        assertEquals(listOf(0, 0, -75), points.first())
        assertEquals(listOf(900, 0, 80), points.last())
        assertTrue(points.zipWithNext().all { (a, b) -> a[0] < b[0] && a[2] <= b[2] })
        assertNull(richTapPayload(HapticPattern.empty("silence"), 0, 900, 121, 13))
    }
}
