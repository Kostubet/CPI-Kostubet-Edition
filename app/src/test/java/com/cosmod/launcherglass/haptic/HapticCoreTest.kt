package com.cosmod.launcherglass.haptic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HapticCoreTest {
    @Test fun originalRingtoneSurvivesRenameAndKeepsCarrierWithGainEdits() {
        val reference = HapticPattern("Original", 100, listOf(
            HapticPoint(0, 0f), HapticPoint(20, .5f), HapticPoint(60, .5f), HapticPoint(100, 0f),
        ), ringtoneVibration = true)
        assertTrue(reference.copy(name = "Copy", repeat = true).usesOriginalRingtone(reference))
        val quieter = reference.copy(intensity = 60, offset = 20, fadeIn = 60)
        assertTrue(quieter.canEditRingtoneCarrier(reference))
        assertEquals(.4f, quieter.ringtoneGain(reference, 40, true), .0001f)
        assertEquals(0f, quieter.ringtoneGain(reference, 10, true), 0f)
        val local = reference.replace(HapticPoint(20, .25f))
        assertEquals(.5f, local.ringtoneGain(reference, 20, false), .0001f)
        assertEquals(1f, local.ringtoneGain(reference, 60, false), .0001f)
        assertTrue(!local.usesOriginalRingtone(reference))
        assertTrue(!reference.replace(HapticPoint(40, .8f, tapKind = HapticTap.NATIVE)).canEditRingtoneCarrier(reference))
    }

    @Test fun ringtonePcmKeepsSignedAudioAndHapticSamplesSeparate() {
        val buffer = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(100).putShort(-170).putShort(-100).putShort(170).flip()
        val collector = RingtonePcm.Collector()
        collector.append(buffer, rate = 8000, channels = 2, haptics = 1, timestampUs = 125,
            encoding = android.media.AudioFormat.ENCODING_PCM_16BIT, bytes = 2)
        val pcm = requireNotNull(collector.finish())
        assertEquals(3, pcm.frames)
        assertEquals(0, pcm.sample(0, 1).toInt())
        assertEquals(100, pcm.sample(1, 0).toInt())
        assertEquals(-170, pcm.sample(1, 1).toInt())
        assertEquals(170, pcm.sample(2, 1).toInt())
        assertEquals(0, buffer.position())
    }

    @Test fun importedRingtoneEnvelopeKeepsItsAmplitudeAndSilence() {
        val envelope = requireNotNull(ringtoneEnvelope(floatArrayOf(0f, .001f, .72f, .35f, 0f, 0f), 120))
        val pattern = HapticPattern("Ringtone", 120, envelope)
        assertEquals(0f, pattern.at(20).amplitude, .0001f)
        assertEquals(.72f, pattern.at(40).amplitude, .0001f)
        assertEquals(.35f, pattern.at(60).amplitude, .0001f)
        assertEquals(0f, pattern.at(100).amplitude, .0001f)
        assertNull(ringtoneEnvelope(FloatArray(6), 120))
    }

    @Test fun movingAHandleCrossesOtherPointsWithoutReplacingThem() {
        val pattern = HapticPattern("Editor", 800, listOf(
            HapticPoint(0, 0f), HapticPoint(100, .7f, pitch = 30f, tapKind = HapticTap.NATIVE),
            HapticPoint(240, .4f), HapticPoint(500, 0f), HapticPoint(800, 0f),
        ), offset = 20)
        val (edited, time) = pattern.movePoint(100, 520, HapticChannel.AMPLITUDE, .8f)
        assertEquals(499, time)
        assertEquals(.8f, edited.sourcePoint(time).amplitude, .0001f)
        assertEquals(30f, edited.sourcePoint(time).pitch, 0f)
        assertEquals(HapticTap.NATIVE, edited.sourcePoint(time).tapKind)
        assertEquals(pattern.sourcePoint(240), edited.sourcePoint(240))
        assertEquals(pattern.sourcePoint(500), edited.sourcePoint(500))
        assertEquals(listOf(519), edited.tapHits(0, edited.length).map { it.time })
    }

    @Test fun automaticSyncSelectsTheRealAttackAndDoesNotRepeatOnASustainedTone() {
        val quiet = HapticAnalysis.Frame(0f, 0f, 0f, 0f, 0f, 0f, 0)
        val steady = quiet.copy(rms = .4f, flux = .025f)
        val frames = MutableList(30) { quiet }
        frames[5] = steady.copy(rms = .18f, rise = .07f, onsetMs = 5)
        frames[6] = steady.copy(rise = .33f, onsetMs = 15)
        for (i in 7..18) frames[i] = steady
        val track = HapticAnalysis.Track(frames, 600, null)
        val points = HapticAnalysis.generate(track, HapticStyle.TAPS, HapticStyle.TAPS.options())
        assertEquals(listOf(139), points.filter { it.tap }.map { it.ms })
    }

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

    @Test fun insertionKeepsSlidesAndNativeClicksOnTheSameTimeline() {
        val slide = HapticPattern("track", 1400, listOf(HapticPoint(0, 0f), HapticPoint(120, .65f, pitch = -35f),
            HapticPoint(420, .75f, pitch = 35f), HapticPoint(610, 0f), HapticPoint(1400, 0f)), offset = 25)
        val taps = HapticTapTrain(count = 2, spacing = 150)
        val mixed = requireNotNull(slide.insertEffect(750, HapticPattern("taps", taps.length, taps.points())))
        assertEquals(slide.at(300), mixed.at(300))
        assertEquals(listOf(754, 904), mixed.tapHits(0, mixed.length).map { it.time })
        assertNull(mixed.nativeTaps(0, mixed.length))
        val payload = requireNotNull(mixedRichTapPayload(mixed, 0, mixed.length, 1, 1))
        assertEquals(2, payload.count { it == 4097 })
        assertTrue(4096 in payload)
    }

    @Test fun mixedGeneratorAlignsTapsToLateOnsetsAndKeepsASlideBetweenThem() {
        val rate = HapticAnalysis.SAMPLE_RATE
        val samples = FloatArray(rate * 3) { i ->
            val time = i * 1000 / rate
            val attack = time in 375..422 || time in 937..983
            val amplitude = if (attack) .8 else if (time in 430..890) .12 else 0.0
            (amplitude * sin(i * 2 * PI * 95 / rate)).toFloat()
        }
        val track = HapticAnalysis.analyze(samples, samples.size)
        val options = HapticStyle.MIXED.options()
        val points = HapticAnalysis.generate(track, HapticStyle.MIXED, options)
        assertTrue(points.any { it.tap && it.ms in 370..389 })
        assertTrue(points.any { it.tap && it.ms in 930..950 })
        assertTrue(points.any { !it.tap && it.ms in 500..850 && it.amplitude > 0f })
        assertTrue(HapticAnalysis.generate(track, HapticStyle.MIXED, options.copy(tapStrength = 0)).none { it.tap })
    }

    @Test fun brushReplacesOnlyItsStrokeAndKeepsOtherChannels() {
        val pattern = HapticPattern("brush", 1000, listOf(HapticPoint(0, 0f),
            HapticPoint(300, .2f, pitch = 35f), HapticPoint(500, .6f, pitch = 60f), HapticPoint(1000, 0f)), offset = 20)
        val stroke = listOf(250 to .3f, 320 to .8f, 400 to .4f)
        val result = pattern.paintStroke(HapticChannel.AMPLITUDE, stroke)
        assertEquals(.8f, result.sourcePoint(300).amplitude, .0001f)
        assertEquals(35f, result.sourcePoint(300).pitch, .0001f)
        assertEquals(pattern.points[2], result.sourcePoint(500))
    }

    @Test fun amplitudeStrokeKeepsTapsAndFrequencyAnchorsBetweenTouchSamples() {
        val pattern = HapticPattern("brush", 1000, listOf(HapticPoint(0, 0f),
            HapticPoint(250, .15f, hz = 150f, pitch = 30f, curve = HapticCurve.EASE_IN),
            HapticPoint(300, .65f, hz = 120f, pitch = 80f, tapKind = HapticTap.NATIVE),
            HapticPoint(330, 0f), HapticPoint(1000, 0f)), offset = 20)
        val result = pattern.paintStroke(HapticChannel.AMPLITUDE, listOf(220 to .2f, 420 to .8f))
        assertEquals(150f, result.sourcePoint(250).hz, 0f)
        assertEquals(HapticCurve.EASE_IN, result.sourcePoint(250).curve)
        assertEquals(80f, result.sourcePoint(300).pitch, 0f)
        assertEquals(320, result.tapHits(0, 1000).single().time)
        assertEquals(.5f, result.sourcePoint(300).amplitude, .0001f)
    }

    @Test fun addingAPointUsesVibrationTimeAndKeepsTheExistingValue() {
        val pattern = HapticPattern("offset", 1000, listOf(HapticPoint(0, 0f),
            HapticPoint(500, .8f, hz = 150f), HapticPoint(1000, 0f)), offset = 100)
        val point = pattern.sourcePoint(pattern.sourceTimeAt(350))
        assertEquals(250, point.ms)
        assertEquals(.4f, point.amplitude, .0001f)
        assertEquals(pattern.at(350), pattern.replace(point).at(350))
    }
}
