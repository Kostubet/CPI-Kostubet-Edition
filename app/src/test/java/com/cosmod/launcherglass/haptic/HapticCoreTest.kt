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
        val taps = HapticAnalysis.generate(track, HapticStyle.TAPS, HapticStyle.TAPS.options())
        assertTrue(bass.filter { it.ms in 0..3800 }.maxOf { it.amplitude } > .12f)
        assertTrue(bass.filter { it.ms >= 4600 }.all { it.amplitude == 0f })
        assertNotEquals(bass, smooth)
        assertTrue(taps.count { it.tap } >= 6)
        assertTrue(taps.filter { it.amplitude > 0f }.all { it.tap && it.ms < 4100 })
        val quiet = HapticAnalysis.analyze(FloatArray(rate), rate)
        assertTrue(HapticAnalysis.generate(quiet, HapticStyle.DYNAMIC, GeneratorOptions()).all { it.amplitude == 0f })
    }

    @Test fun tapsRetainSilenceAndUseRichTapTransientsAtTheCorrectTimes() {
        val pattern = HapticPattern("taps", 720, tapPoints(720, listOf(HapticClick(80, .8f), HapticClick(320, .6f))),
            offset = 20)
        val clicks = requireNotNull(pattern.nativeTaps(50, 540))
        assertEquals(listOf(54, 294), clicks.map { it.time })
        assertEquals(.8f, clicks[0].strength, .0001f)
        assertEquals(.6f, clicks[1].strength, .0001f)
        assertEquals(0f, pattern.at(210).amplitude, 0f)
        val fallback = HapticWaveform.sample(pattern, 50, 540, true)
        assertEquals(490L, fallback.times.sum())
        assertTrue(fallback.times.indices.any { fallback.times[it] >= 180 && fallback.levels[it] == 0 })
        val payload = richTapTapPayload(clicks, 10, 20)
        assertEquals(listOf(0, 54, 2), payload.slice(5..7))
        assertEquals(listOf(4097, 5, 0, 0, 80, 0, 0), payload.slice(8..14))
        assertEquals(listOf(4097, 5, 0, 240, 60, 0, 0), payload.slice(15..21))
        assertNull(pattern.replace(HapticPoint(220, .4f)).nativeTaps(0, 720))
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

    @Test fun tapRhythmStrengthAndPulseWidthAreIndependent() {
        val train = HapticTapTrain(count = 4, spacing = 160, delay = 100, swing = 25,
            firstStrength = 25, lastStrength = 85, customPulse = true, pulseWidth = 20)
        val pattern = HapticPattern("taps", train.length, train.points())
        val peaks = pattern.points.filter { it.tap }
        assertEquals(listOf(104, 304, 424, 624), peaks.map { it.ms })
        assertEquals(.25f, peaks.first().amplitude, .0001f)
        assertEquals(.85f, peaks.last().amplitude, .0001f)
        assertEquals(0f, pattern.at(95).amplitude, 0f)
        assertEquals(0f, pattern.at(120).amplitude, 0f)
        assertNull(pattern.nativeTaps(0, pattern.length))
        val systemClicks = pattern.copy(points = train.copy(customPulse = false).points())
        assertEquals(peaks.map { it.ms }, systemClicks.nativeTaps(0, pattern.length)?.map { it.time })
    }

    @Test fun resettingVibrationKeepsSourceAndTimeline() {
        val pattern = HapticPattern("song", 800, HapticTapTrain(count = 2, spacing = 400).points(),
            audio = "content://audio/1", intensity = 50, offset = 120, fadeIn = 100, repeat = true)
        val reset = pattern.resetVibration()
        assertEquals(pattern.name, reset.name)
        assertEquals(pattern.audio, reset.audio)
        assertEquals(pattern.length, reset.length)
        assertEquals(listOf(HapticPoint(0, 0f), HapticPoint(800, 0f)), reset.points)
        assertEquals(100, reset.intensity)
        assertEquals(0, reset.offset)
        assertTrue(!reset.repeat)
    }
}
