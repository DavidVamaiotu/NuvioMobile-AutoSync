package com.nuvio.app.features.autosync.audio

import java.nio.ByteBuffer

/** Mono downmix that favours the centre channel, where film dialogue normally lives. */
internal object PcmDownmix {
    private const val CENTER_WEIGHT = 0.7f
    private const val SIDE_WEIGHT = 0.15f

    fun toMono(
        buffer: ByteBuffer,
        startByte: Int,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        centerIndex: Int,
        out: FloatArray,
    ) {
        val bytesPerSample = if (isFloat) 4 else 2
        for (frame in 0 until frames) {
            val base = startByte + frame * channels * bytesPerSample
            if (centerIndex in 0 until channels && channels >= 3) {
                val left = sample(buffer, base, isFloat)
                val right = sample(buffer, base + bytesPerSample, isFloat)
                val center = sample(buffer, base + centerIndex * bytesPerSample, isFloat)
                out[frame] = CENTER_WEIGHT * center + SIDE_WEIGHT * (left + right)
            } else {
                var sum = 0f
                for (c in 0 until channels) sum += sample(buffer, base + c * bytesPerSample, isFloat)
                out[frame] = sum / channels
            }
        }
    }

    private fun sample(buffer: ByteBuffer, byteIndex: Int, isFloat: Boolean): Float =
        if (isFloat) buffer.getFloat(byteIndex) else buffer.getShort(byteIndex) / 32_768f
}
