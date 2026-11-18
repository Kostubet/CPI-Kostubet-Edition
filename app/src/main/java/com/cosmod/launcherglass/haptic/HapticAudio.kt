package com.cosmod.launcherglass.haptic

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object HapticAudio {
    private const val MAX_SAMPLES = HapticAnalysis.SAMPLE_RATE * (MAX_TRACK_MS / 1000)

    fun analyze(context: Context, uri: Uri): HapticAnalysis.Track {
        val cache = cacheFile(context, uri)
        if (cache != null) readCached(cache)?.let { return it }
        val track = readAudio(context, uri)
        if (cache != null) writeCached(cache, track)
        return track
    }

    private fun readAudio(context: Context, uri: Uri): HapticAnalysis.Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                return decode(extractor, decoder, format)
            } finally {
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun cacheFile(context: Context, uri: Uri): AtomicFile? = runCatching {
        val modifiedColumn = if (DocumentsContract.isDocumentUri(context, uri))
            DocumentsContract.Document.COLUMN_LAST_MODIFIED else MediaStore.MediaColumns.DATE_MODIFIED
        val identity = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, modifiedColumn),
            null, null, null)?.use { cursor ->
            if (cursor.columnCount != 2 || !cursor.moveToFirst()) return null
            val bytes = cursor.getLong(0)
            val changed = cursor.getLong(1)
            if (bytes <= 0 || changed <= 0) return null
            "$uri|$bytes|$changed"
        } ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHexString()
        AtomicFile(File(File(context.cacheDir, "haptic-analysis"), "$key.bin"))
    }.getOrNull()

    private fun readCached(file: AtomicFile): HapticAnalysis.Track? = runCatching {
        DataInputStream(file.openRead().buffered()).use { input ->
            require(input.readInt() == 2)
            val duration = input.readInt()
            val bpm = input.readInt().takeIf { it in 40..240 }
            val count = input.readInt()
            require(duration in 80..MAX_TRACK_MS && count in 4..(MAX_TRACK_MS / HapticAnalysis.HOP_MS))
            val frames = List(count) {
                HapticAnalysis.Frame(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readFloat(), input.readUnsignedByte())
            }
            file.baseFile.setLastModified(System.currentTimeMillis())
            HapticAnalysis.Track(frames, duration, bpm)
        }
    }.getOrNull()

    private fun writeCached(file: AtomicFile, track: HapticAnalysis.Track) {
        runCatching {
            val output = file.startWrite()
            try {
                val stream = DataOutputStream(output.buffered())
                stream.writeInt(2)
                stream.writeInt(track.length)
                stream.writeInt(track.bpm ?: 0)
                stream.writeInt(track.frames.size)
                for (frame in track.frames) {
                    stream.writeFloat(frame.rms)
                    stream.writeFloat(frame.low)
                    stream.writeFloat(frame.mid)
                    stream.writeFloat(frame.high)
                    stream.writeFloat(frame.flux)
                    stream.writeFloat(frame.rise)
                    stream.writeByte(frame.onsetMs)
                }
                stream.flush()
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            file.baseFile.parentFile?.listFiles().orEmpty().filter { it.extension == "bin" }
                .sortedByDescending { it.lastModified() }.drop(10).forEach { it.delete() }
        }
    }

    private fun decode(extractor: MediaExtractor, decoder: MediaCodec, initial: MediaFormat): HapticAnalysis.Track {
        val samples = FloatArray(MAX_SAMPLES)
        val info = MediaCodec.BufferInfo()
        var count = 0
        var inputEnded = false
        var outputEnded = false
        var format = initial
        var phase = 0
        var sum = 0f
        var mixed = 0
        var idle = 0
        while (!outputEnded && count < samples.size) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            if (!inputEnded) {
                val input = decoder.dequeueInputBuffer(5_000)
                if (input >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(input))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0 || extractor.sampleTime > (MAX_TRACK_MS + 1000L) * 1000) {
                        decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val output = decoder.dequeueOutputBuffer(info, 5_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    format = decoder.outputFormat
                    phase = 0
                    sum = 0f
                    mixed = 0
                    idle = 0
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> check(++idle < 1200) { "Audio decoder timed out" }
                else -> if (output >= 0) {
                    idle = 0
                    val buffer = decoder.getOutputBuffer(output)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.limit(info.offset + info.size)
                        buffer.position(info.offset)
                        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 4000..384000 && channels in 1..32)
                        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        val sampleBytes = when (encoding) {
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            AudioFormat.ENCODING_PCM_16BIT -> 2
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            else -> error("Unsupported PCM format")
                        }
                        if (count == 0 && phase == 0) {
                            count = (info.presentationTimeUs.coerceAtLeast(0) * HapticAnalysis.SAMPLE_RATE / 1_000_000)
                                .coerceAtMost(samples.size.toLong()).toInt()
                        }
                        while (buffer.remaining() >= sampleBytes * channels && count < samples.size) {
                            var mono = 0f
                            repeat(channels) {
                                mono += when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it else 0f }
                                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                                    else -> buffer.short / 32768f
                                }
                            }
                            val sample = (mono / channels).coerceIn(-1f, 1f)
                            sum += sample
                            mixed++
                            phase += HapticAnalysis.SAMPLE_RATE
                            if (phase >= rate) {
                                val downsampled = sum / mixed
                                do {
                                    samples[count++] = downsampled
                                    phase -= rate
                                } while (phase >= rate && count < samples.size)
                                sum = 0f
                                mixed = 0
                            }
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(output, false)
                }
            }
        }
        return HapticAnalysis.analyze(samples, count)
    }
}
