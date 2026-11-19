package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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

    private val tick = object : Runnable {
        override fun run() {
            val audio = player ?: return
            val ms = runCatching { audio.currentPosition }.getOrElse { stop(); return }
            // Never replay missed taps as a cluster after a stalled UI frame.
            for (note in cursor.due(ms)) motor?.auditionClick(note.strength)
            if (cursor.startSlide(ms)) runCatching { slide?.invoke() }
            handler.postDelayed(this, 6)
        }
    }

    fun play() {
        stop()
        // Respect the user's media volume, ringer mode and global haptic switch.
        if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1 &&
            context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            motor = HapticMotor(context)
            slide = motor?.let { it.prepare(WinterMelody.slide(it.frequency), 0, WinterMelody.SLIDE_DURATION_MS) }
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
