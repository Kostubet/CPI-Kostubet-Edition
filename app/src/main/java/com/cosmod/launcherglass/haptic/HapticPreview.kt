package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioAttributes
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
    private data class Request(
        val pattern: HapticPattern,
        val audio: Uri?,
        val end: Int,
        val repeat: Boolean,
        val loopStart: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
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

    fun play(pattern: HapticPattern, audio: Uri?, start: Int, end: Int, repeat: Boolean, lower: Int = start) {
        stop()
        if (start >= end) return
        start(Request(pattern, audio, end, repeat, lower), start)
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
        haptics = motor.prepare(next.pattern, at, endPosition)
        loopHaptics = if (!next.repeat || next.loopStart == at) haptics
            else motor.prepare(next.pattern, next.loopStart, endPosition)
        if (next.audio == null) {
            startCycle()
            return
        }
        val player = MediaPlayer()
        media = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setHapticChannelsMuted(true)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        player.setDataSource(context, next.audio)
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
