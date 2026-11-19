package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class HapticPreview(
    private val context: Context,
    private val motor: HapticMotor,
    private val positionChanged: (Int) -> Unit,
    private val playbackFailed: () -> Unit,
) {
    data class RingtoneSource(val reference: HapticPattern, val pcm: RingtonePcm?)

    private data class Request(
        val pattern: HapticPattern,
        val audio: Uri?,
        val end: Int,
        val repeat: Boolean,
        val loopStart: Int,
        val ringtone: RingtoneSource?,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var carrier: HapticRingtonePlayback? = null
    private var request: Request? = null
    private var elapsed = 0L
    private var startPosition = 0
    private var endPosition = 0
    private var haptics: (() -> Unit)? = null
    private var loopHaptics: (() -> Unit)? = null
    private var started = false
    private var motorActive = false
    var playing = false
        private set
    var position = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || !started) return
            val value = currentPosition()
            if (value >= endPosition) cycle()
            else {
                position = value.coerceAtLeast(startPosition)
                positionChanged(position)
                handler.postDelayed(this, 20)
            }
        }
    }

    fun play(pattern: HapticPattern, audio: Uri?, start: Int, end: Int, repeat: Boolean, lower: Int = start,
        ringtone: RingtoneSource? = null) {
        stop()
        if (start >= end) return
        start(Request(pattern, audio, end, repeat, lower, ringtone), start)
    }

    fun pause() {
        if (!playing) return
        if (started) position = currentPosition().coerceIn(startPosition, endPosition)
        playing = false
        stopOutputs()
        positionChanged(position)
    }

    fun resume(): Boolean {
        val paused = request ?: return false
        if (playing || position >= endPosition) return false
        start(paused, position)
        return true
    }

    private fun start(next: Request, at: Int) {
        request = next
        startPosition = at
        endPosition = next.end
        position = at
        playing = true
        positionChanged(position)
        handler.post {
            if (!playing || request !== next || startPosition != at) return@post
            runCatching { prepare(next, at) }.onFailure { fail() }
        }
    }

    private fun prepare(next: Request, at: Int) {
        val ringtone = next.ringtone?.takeIf {
            next.pattern.ringtoneVibration && next.pattern.audio == it.reference.audio &&
                AudioManager.isHapticPlaybackSupported()
        }
        if (ringtone != null && next.pattern.usesOriginalRingtone(ringtone.reference) &&
            (next.audio != null || ringtone.pcm == null)) {
            val source = Uri.parse(requireNotNull(ringtone.reference.audio))
            prepareMedia(next, at, source, nativeHaptics = true)
            return
        }
        if (ringtone?.pcm != null && next.pattern.canEditRingtoneCarrier(ringtone.reference)) {
            startCarrier(next, at, ringtone)
            return
        }
        haptics = motor.prepare(next.pattern, at, endPosition)
        loopHaptics = if (!next.repeat || next.loopStart == at) haptics
            else motor.prepare(next.pattern, next.loopStart, endPosition)
        if (next.audio == null) {
            startCycle()
            return
        }
        prepareMedia(next, at, next.audio, nativeHaptics = false)
    }

    private fun startCarrier(next: Request, at: Int, ringtone: RingtoneSource) {
        lateinit var playback: HapticRingtonePlayback
        playback = HapticRingtonePlayback(requireNotNull(ringtone.pcm), ringtone.reference,
            next.pattern, next.audio != null, at, endPosition) {
            handler.post { if (carrier === playback) fail() }
        }
        carrier = playback
        startCycle()
    }

    private fun prepareMedia(next: Request, at: Int, uri: Uri, nativeHaptics: Boolean) {
        val player = MediaPlayer()
        media = player
        player.setAudioAttributes(ringtoneAudioAttributes(nativeHaptics))
        if (next.audio == null) player.setVolume(0f, 0f)
        player.setDataSource(context, uri)
        player.setOnPreparedListener {
            if (media === it && playing) {
                runCatching {
                    endPosition = minOf(next.end, it.duration)
                    when {
                        endPosition <= at -> fail()
                        at == 0 -> startCycle()
                        else -> it.seekTo(at.toLong(), MediaPlayer.SEEK_CLOSEST)
                    }
                }.onFailure { fail() }
            }
        }
        player.setOnSeekCompleteListener { if (media === it && playing) startCycle() }
        player.setOnCompletionListener { if (media === it && playing) cycle() }
        player.setOnErrorListener { failing, _, _ ->
            if (media === failing && playing) fail()
            true
        }
        player.prepareAsync()
    }

    private fun startCycle() {
        if (!playing || started) return
        runCatching {
            media?.start()
            carrier?.start()
            elapsed = SystemClock.uptimeMillis()
            haptics?.let {
                motorActive = true
                it()
            }
            started = true
            handler.post(tick)
        }.onFailure { fail() }
    }

    private fun currentPosition(): Int {
        carrier?.let { return it.position }
        val player = media
        return if (player == null) startPosition + (SystemClock.uptimeMillis() - elapsed).toInt()
        else runCatching { player.currentPosition }.getOrDefault(position)
    }

    private fun cycle() {
        val active = request ?: return
        if (!playing || !started) return
        handler.removeCallbacks(tick)
        if (!active.repeat || endPosition <= active.loopStart) {
            position = endPosition
            stop()
            return
        }
        stopMotor()
        started = false
        startPosition = active.loopStart
        position = startPosition
        haptics = loopHaptics
        positionChanged(position)
        if (carrier != null) {
            carrier?.close()
            carrier = null
            startCarrier(active, startPosition, requireNotNull(active.ringtone))
            return
        }
        val player = media
        if (player == null) startCycle()
        else runCatching {
            if (player.isPlaying) player.pause()
            player.seekTo(startPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
        }.onFailure { fail() }
    }

    fun stop() {
        playing = false
        request = null
        stopOutputs()
        positionChanged(position)
    }

    private fun stopOutputs() {
        started = false
        handler.removeCallbacksAndMessages(null)
        val player = media
        media = null
        carrier?.close()
        carrier = null
        stopMotor()
        runCatching { player?.release() }
        haptics = null
        loopHaptics = null
    }

    private fun stopMotor() {
        if (!motorActive) return
        motorActive = false
        motor.stop()
    }

    private fun fail() {
        stop()
        playbackFailed()
    }
}
