package io.github.thanhngochoang.voicetrans

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test helper: 16-bit mono PCM WAV bytes to floats in [-1, 1]. */
object WavReader {
    fun toFloats(bytes: ByteArray): FloatArray {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                val sb = ByteBuffer.wrap(bytes, pos + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(n) { sb.get(it) / 32768f }
            }
            pos += 8 + size
        }
        error("no data chunk")
    }

    fun chunks(samples: FloatArray, size: Int = 1600): List<FloatArray> =
        samples.toList().chunked(size).map { it.toFloatArray() }
}
