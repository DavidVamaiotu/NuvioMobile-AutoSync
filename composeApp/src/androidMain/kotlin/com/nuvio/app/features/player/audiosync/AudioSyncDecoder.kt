@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the copied compressed audio on its own low-priority thread with a private software
 * [MediaCodec] and feeds mono PCM to a [SpeechAnalyzer].
 *
 * Only software decoders are used so the player's hardware or DSP decoder is never contended.
 * Formats without a usable software decoder (often DTS or TrueHD) are skipped. The queue is bounded;
 * if analysis falls behind, samples are dropped and the gap is simply left unknown.
 */
internal class AudioSyncDecoder(
    private val analyzer: SpeechAnalyzer,
) {
    private sealed interface Item {
        class Sample(val format: Format, val timeUs: Long, val data: ByteArray) : Item
        data object Discontinuity : Item
    }

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var queuedBytes = 0
    private var dropping = false
    private var released = false
    private var thread: Thread? = null

    // Decoder-thread state.
    private var codec: MediaCodec? = null
    private var codecFormat: Format? = null
    private var outputChannels = 0
    private var outputRate = 0
    private var outputFloat = false
    private var centerIndex = -1
    private var mono = FloatArray(0)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val unsupportedMimes = HashSet<String>()

    /** Called from the extractor (loading) thread. Returns false when the sample was dropped. */
    fun offer(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int): Boolean {
        val mime = format.sampleMimeType ?: return false
        synchronized(lock) {
            if (released) return false
            if (mime in unsupportedMimesSnapshot) return false
            if (queuedBytes + size > MAX_QUEUED_BYTES) {
                if (!dropping) {
                    dropping = true
                    queue.addLast(Item.Discontinuity)
                }
                return false
            }
            dropping = false
            queue.addLast(Item.Sample(format, timeUs, data.copyOfRange(offset, offset + size)))
            queuedBytes += size
            ensureThread()
            lock.notifyAll()
        }
        return true
    }

    fun discontinuity() {
        synchronized(lock) {
            if (released) return
            queue.clear()
            queuedBytes = 0
            queue.addLast(Item.Discontinuity)
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
            queuedBytes = 0
            lock.notifyAll()
        }
    }

    @Volatile
    private var unsupportedMimesSnapshot: Set<String> = emptySet()

    private fun ensureThread() {
        if (thread != null) return
        thread = Thread({ runLoop() }, "NuvioAudioSync").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            while (true) {
                val item = synchronized(lock) {
                    while (queue.isEmpty() && !released) lock.wait()
                    if (released) return
                    queue.removeFirst().also { if (it is Item.Sample) queuedBytes -= it.data.size }
                }
                when (item) {
                    is Item.Discontinuity -> {
                        flushCodec()
                        analyzer.reset()
                    }
                    is Item.Sample -> decode(item)
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            Log.w(TAG, "decoder loop stopped: ${error.message}")
        } finally {
            releaseCodec()
        }
    }

    private fun decode(sample: Item.Sample) {
        val codec = codecFor(sample.format) ?: return
        try {
            var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            var attempts = 0
            while (inputIndex < 0 && attempts < MAX_INPUT_ATTEMPTS) {
                drainOutput(codec)
                inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                attempts++
            }
            if (inputIndex < 0) return
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            if (input.remaining() < sample.data.size) {
                codec.queueInputBuffer(inputIndex, 0, 0, sample.timeUs, 0)
                return
            }
            input.put(sample.data)
            codec.queueInputBuffer(inputIndex, 0, sample.data.size, sample.timeUs, 0)
            drainOutput(codec)
        } catch (error: Exception) {
            Log.w(TAG, "decode failed for ${sample.format.sampleMimeType}: ${error.message}")
            releaseCodec()
            analyzer.reset()
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && bufferInfo.size > 0) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        deliverPcm(output.slice().order(ByteOrder.nativeOrder()), bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    private fun readOutputFormat(format: MediaFormat) {
        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        outputFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val mask = if (format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
        } else {
            0
        }
        centerIndex = when {
            outputChannels < 3 -> -1
            mask != 0 && mask and AudioFormat.CHANNEL_OUT_FRONT_CENTER != 0 ->
                Integer.bitCount(mask and (AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT))
            mask != 0 -> -1
            // Android decoders emit the WAVE order (FL, FR, FC, LFE, ...) for 3+ channel layouts.
            else -> 2
        }
    }

    /** Downmixes to mono, weighting the centre channel where dialogue normally lives. */
    private fun deliverPcm(buffer: ByteBuffer, timeUs: Long) {
        val channels = outputChannels
        val rate = outputRate
        if (channels <= 0 || rate <= 0) return
        val bytesPerSample = if (outputFloat) 4 else 2
        val frames = buffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0) return
        if (mono.size < frames) mono = FloatArray(frames)
        val center = centerIndex
        for (frame in 0 until frames) {
            val base = frame * channels
            if (center >= 0) {
                val left = sampleAt(buffer, base, bytesPerSample)
                val right = sampleAt(buffer, base + 1, bytesPerSample)
                val middle = sampleAt(buffer, base + center, bytesPerSample)
                mono[frame] = CENTER_WEIGHT * middle + SIDE_WEIGHT * (left + right)
            } else {
                var sum = 0f
                for (c in 0 until channels) sum += sampleAt(buffer, base + c, bytesPerSample)
                mono[frame] = sum / channels
            }
        }
        analyzer.accept(mono, frames, rate, timeUs)
    }

    private fun sampleAt(buffer: ByteBuffer, index: Int, bytesPerSample: Int): Float =
        if (bytesPerSample == 4) buffer.getFloat(index * 4) else buffer.getShort(index * 2) / 32_768f

    private fun codecFor(format: Format): MediaCodec? {
        val current = codec
        if (current != null && codecFormat.isSameStream(format)) return current
        releaseCodec()
        analyzer.reset()
        val mime = format.sampleMimeType ?: return null
        if (mime in unsupportedMimes) return null
        val created = runCatching { createCodec(format) }.onFailure {
            Log.w(TAG, "no usable decoder for $mime: ${it.message}")
        }.getOrNull()
        if (created == null) {
            unsupportedMimes += mime
            unsupportedMimesSnapshot = unsupportedMimes.toSet()
            Log.i(TAG, "audio sync unavailable for $mime (no software decoder)")
            return null
        }
        codec = created
        codecFormat = format
        outputChannels = format.channelCount
        outputRate = format.sampleRate
        outputFloat = false
        centerIndex = if (format.channelCount >= 3) 2 else -1
        Log.i(TAG, "decoding $mime ${format.channelCount}ch ${format.sampleRate}Hz with ${created.name}")
        return created
    }

    private fun createCodec(format: Format): MediaCodec? {
        val mime = format.sampleMimeType ?: return null
        val candidates = buildList {
            add(mime)
            if (mime == MimeTypes.AUDIO_E_AC3_JOC) add(MimeTypes.AUDIO_E_AC3)
        }
        for (candidate in candidates) {
            val name = findSoftwareDecoder(candidate) ?: continue
            val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format)
            mediaFormat.setString(MediaFormat.KEY_MIME, candidate)
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(mediaFormat, null, null, 0)
                codec.start()
                return codec
            } catch (error: Exception) {
                codec.release()
                Log.w(TAG, "failed to start $name: ${error.message}")
            }
        }
        return null
    }

    private fun findSoftwareDecoder(mime: String): String? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        return infos.firstOrNull { it.isSoftwareOnlyCompat() }?.name
    }

    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isSoftwareOnly
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") || lower.startsWith("c2.ffmpeg.")
    }

    private fun Format?.isSameStream(other: Format): Boolean =
        this != null && sampleMimeType == other.sampleMimeType && sampleRate == other.sampleRate &&
            channelCount == other.channelCount && initializationData.size == other.initializationData.size &&
            initializationData.indices.all { initializationData[it].contentEquals(other.initializationData[it]) }

    private fun flushCodec() {
        val current = codec ?: return
        try {
            current.flush()
        } catch (_: Exception) {
            releaseCodec()
        }
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        codecFormat = null
        try {
            current.stop()
        } catch (_: Exception) {
        }
        try {
            current.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "NuvioAudioSync"
        private const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val MAX_INPUT_ATTEMPTS = 40
        private const val CENTER_WEIGHT = 0.7f
        private const val SIDE_WEIGHT = 0.15f
    }
}
