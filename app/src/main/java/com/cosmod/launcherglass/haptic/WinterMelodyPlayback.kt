package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.cosmod.launcherglass.R

/** Follows the player's clock instead of accumulating Handler delays between notes. */
internal class WinterMelodyPlayback(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var motor: HapticMotor? = null
    private var slide: (() -> Unit)? = null
    private var cursor = WinterMelody.Cursor()
    val playing get() = player != null
    val positionMs: Int? get() = player?.let { runCatching { it.currentPosition }.getOrNull() }

    private val tick = object : Runnable {
        override fun run() {
            val audio = player ?: return
            val ms = runCatching { audio.currentPosition }.getOrElse { stop(); return }
            // Never replay missed taps as a cluster after a stalled UI frame.
            for (note in cursor.due(ms)) motor?.celebrationTap(note)
            if (cursor.startSlide(ms)) runCatching { slide?.invoke() }
            handler.postDelayed(this, 6)
        }
    }

    fun play(onStarted: () -> Unit) {
        stop()
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1) {
            motor = runCatching { HapticMotor(context, touchFeedback = true) }.getOrNull()
            slide = motor?.let { engine ->
                runCatching { engine.prepare(WinterMelody.slide(engine.frequency), 0, WinterMelody.SLIDE_DURATION_MS) }.getOrNull()
            }
        }
        val audio = MediaPlayer()
        player = audio
        audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        audio.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            runCatching {
                cursor = WinterMelody.Cursor()
                it.start()
                onStarted()
                handler.post(tick)
            }.onFailure { stop() }
        }
        audio.setOnCompletionListener { if (player === it) stop() }
        audio.setOnErrorListener { p, _, _ -> if (player === p) stop(); true }
        runCatching {
            context.resources.openRawResourceFd(R.raw.cpi_winter).use { file ->
                audio.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            audio.prepareAsync()
        }.onFailure { stop() }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val audio = player
        player = null
        audio?.setOnCompletionListener(null)
        audio?.setOnErrorListener(null)
        audio?.setOnPreparedListener(null)
        runCatching { audio?.release() }
        motor?.stop(); motor = null; slide = null
    }
}
