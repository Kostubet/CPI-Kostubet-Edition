package com.cosmod.launcherglass.haptic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal class RingtonePcm private constructor(
    val rate: Int, val channels: Int, val hapticChannels: Int,
    private val samples: List<ShortArray>, private val size: Int,
) {
    val audibleChannels get() = channels - hapticChannels
    val frames get() = size / channels

    fun sample(frame: Int, channel: Int): Short {
        if (frame !in 0 until frames) return 0
        val index = frame * channels + channel
        return samples[index / CHUNK][index % CHUNK]
    }

    internal class Collector {
        private val samples = ArrayList<ShortArray>()
        private var size = 0
        private var rate = 0
        private var channels = 0
        private var haptics = 0
        private var discarded = false

        fun append(input: ByteBuffer, rate: Int, channels: Int, haptics: Int, timestampUs: Long, encoding: Int, bytes: Int) {
            if (discarded) return
            if (rate !in 8_000..96_000 || (this.rate != 0 && (this.rate != rate || this.channels != channels))) {
                discard()
                return
            }
            this.rate = rate
            this.channels = channels
            this.haptics = haptics
            val maxSamples = minOf(rate * MAX_TRACK_MS.toLong() / 1000 * channels, MAX_PCM_SAMPLES.toLong()).toInt()
            if (size == 0) {
                val initialFrames = (timestampUs.coerceIn(0, MAX_TRACK_MS * 1000L) * rate / 1_000_000).toInt()
                repeat((initialFrames * channels).coerceAtMost(maxSamples)) { add(0) }
            }
            val data = input.duplicate().order(input.order())
            val count = minOf(data.remaining() / (bytes * channels) * channels, maxSamples - size)
            repeat(count) {
                val sample = when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> data.short
                    AudioFormat.ENCODING_PCM_8BIT -> (((data.get().toInt() and 255) - 128) * 256).toShort()
                    else -> data.float.let { if (it.isFinite()) (it.coerceIn(-1f, 1f) * 32767).roundToInt().toShort() else 0 }
                }
                add(sample)
            }
        }

        private fun add(value: Short) {
            if (size % CHUNK == 0) samples += ShortArray(CHUNK)
            samples.last()[size % CHUNK] = value
            size++
        }

        fun discard() { discarded = true; samples.clear() }

        fun finish(): RingtonePcm? = if (discarded || size == 0 || size >= MAX_PCM_SAMPLES) null
            else RingtonePcm(rate, channels, haptics, samples, size)
    }

    companion object {
        private const val CHUNK = 16_384
        private const val MAX_PCM_SAMPLES = 16 * 1024 * 1024
    }
}

/** Streams the original audio and carrier together so timing edits keep the hardware's haptic route. */
internal class HapticRingtonePlayback(
    private val pcm: RingtonePcm,
    private val original: HapticPattern,
    private val pattern: HapticPattern,
    private val audible: Boolean,
    private val start: Int,
    private val end: Int,
    private val onFailure: () -> Unit,
) {
    @Volatile private var output: AudioTrack? = null
    @Volatile private var stopped = false
    @Volatile private var lastPosition = start
    val position get() = output?.let { track ->
        runCatching { (start + track.playbackHeadPosition * 1000L / pcm.rate).toInt().coerceIn(start, end) }
            .getOrDefault(lastPosition)
    } ?: lastPosition
    private val worker = Thread(::stream, "CPI ringtone carrier")

    fun start() = worker.start()

    fun close() {
        stopped = true
        runCatching { output?.pause() }
        worker.interrupt()
    }

    private fun stream() {
        var track: AudioTrack? = null
        try {
            if (stopped) return
            val audioMask = if (pcm.audibleChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val hapticMask = if (pcm.hapticChannels == 1) HAPTIC_A else HAPTIC_A or HAPTIC_B
            track = AudioTrack.Builder().setAudioAttributes(ringtoneAudioAttributes(nativeHaptics = true))
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.rate).setChannelMask(audioMask or hapticMask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(pcm.rate * pcm.channels * 2 / 5)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED)
            output = track
            if (stopped) return
            track.play()
            write(track)
            while (!stopped && position < end - 1) Thread.sleep(8)
            if (!stopped) lastPosition = end
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (!stopped) onFailure()
        } finally {
            output = null
            track?.release()
        }
    }

    private fun write(track: AudioTrack) {
        val framesPerStep = (pcm.rate / 250).coerceAtLeast(1)
        val data = ShortArray(framesPerStep * pcm.channels)
        val sameEnvelope = pattern.points == original.points
        val shiftFrames = pattern.offset * pcm.rate / 1000
        val first = start * pcm.rate / 1000
        val last = (end * pcm.rate + 999) / 1000
        var frame = first
        while (!stopped && frame < last) {
            val size = minOf(framesPerStep, last - frame)
            val from = pattern.ringtoneGain(original, frame * 1000 / pcm.rate, sameEnvelope)
            val to = pattern.ringtoneGain(original, (frame + size) * 1000 / pcm.rate, sameEnvelope)
            for (index in 0 until size) {
                val gain = from + (to - from) * index / size
                for (channel in 0 until pcm.channels) {
                    data[index * pcm.channels + channel] = if (channel < pcm.audibleChannels) {
                        if (audible) pcm.sample(frame + index, channel) else 0
                    } else {
                        (pcm.sample(frame + index - shiftFrames, channel) * gain)
                            .roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                }
            }
            val count = size * pcm.channels
            var written = 0
            while (!stopped && written < count) {
                val result = track.write(data, written, count - written, AudioTrack.WRITE_NON_BLOCKING)
                check(result >= 0)
                if (result == 0) Thread.sleep(4) else written += result
            }
            frame += size
        }
    }

    companion object {
        // Android AudioFormat's haptic masks are hidden from the public SDK.
        private const val HAPTIC_A = 0x20000000
        private const val HAPTIC_B = 0x10000000
    }
}

internal fun ringtoneAudioAttributes(nativeHaptics: Boolean): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(if (nativeHaptics) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_MEDIA)
        .setContentType(if (nativeHaptics) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
        .setHapticChannelsMuted(!nativeHaptics)
        .build()
