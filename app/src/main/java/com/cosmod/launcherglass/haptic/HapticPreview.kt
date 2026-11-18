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
    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var elapsed = 0L
    private var startPosition = 0
    private var endPosition = 0
    private var loopStart = 0
    private var haptics: (() -> Unit)? = null
    private var loopHaptics: (() -> Unit)? = null
    private var repeating = false
    private var started = false
    private var hasVibrated = false
    var playing = false
        private set
    var position = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || !started) return
            val player = media
            val value = if (player == null) startPosition + (SystemClock.uptimeMillis() - elapsed).toInt()
                else runCatching { player.currentPosition }.getOrElse { fail(); return }
            if (value >= endPosition) cycle()
            else {
                position = value.coerceIn(startPosition, endPosition)
                positionChanged(position)
                handler.postDelayed(this, 20)
            }
        }
    }

    fun play(pattern: HapticPattern, audio: Uri?, start: Int, end: Int, repeat: Boolean, lower: Int = start) {
        stop()
        if (start >= end) return
        startPosition = start
        endPosition = end
        loopStart = lower
        repeating = repeat
        haptics = motor.prepare(pattern, start, end)
        loopHaptics = if (!repeat || lower == start) haptics else motor.prepare(pattern, lower, end)
        if (audio == null && haptics == null && loopHaptics == null) {
            fail()
            return
        }
        playing = true
        position = start
        positionChanged(position)
        if (audio == null) startCycle()
        else {
            val player = MediaPlayer()
            media = player
            runCatching {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                player.setDataSource(context, audio)
                player.setOnPreparedListener {
                    if (media === it) {
                        endPosition = minOf(end, it.duration)
                        when {
                            endPosition <= start -> fail()
                            start == 0 -> startCycle()
                            else -> it.seekTo(start.toLong(), MediaPlayer.SEEK_CLOSEST)
                        }
                    }
                }
                player.setOnSeekCompleteListener { if (media === it) startCycle() }
                player.setOnCompletionListener { if (media === it) cycle() }
                player.setOnErrorListener { failing, _, _ ->
                    if (media === failing) fail()
                    true
                }
                player.prepareAsync()
            }.onFailure { fail() }
        }
    }

    private fun startCycle() {
        if (!playing || started) return
        runCatching {
            media?.start()
            elapsed = SystemClock.uptimeMillis()
            haptics?.let { hasVibrated = true; it() }
            started = true
            handler.post(tick)
        }.onFailure { fail() }
    }

    private fun cycle() {
        handler.removeCallbacks(tick)
        if (!playing || !started) return
        cancelVibration()
        if (!repeating || endPosition <= loopStart) {
            position = endPosition
            stop()
            return
        }
        started = false
        startPosition = loopStart
        haptics = loopHaptics
        val player = media
        if (player == null) startCycle()
        else runCatching {
            if (player.isPlaying) player.pause()
            player.seekTo(startPosition.toLong(), MediaPlayer.SEEK_CLOSEST)
        }.onFailure { fail() }
    }

    fun stop() {
        playing = false
        started = false
        handler.removeCallbacks(tick)
        media?.release()
        media = null
        cancelVibration()
        haptics = null
        loopHaptics = null
        positionChanged(position)
    }

    private fun cancelVibration() {
        if (hasVibrated) motor.stop()
        hasVibrated = false
    }

    private fun fail() {
        stop()
        playbackFailed()
    }
}
