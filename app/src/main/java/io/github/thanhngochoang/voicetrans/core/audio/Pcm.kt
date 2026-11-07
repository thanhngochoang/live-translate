package io.github.thanhngochoang.voicetrans.core.audio

/** PCM16 to float in [-1, 1] (divides by 32768). */
fun ShortArray.toFloatPcm(length: Int = size): FloatArray = FloatArray(length) { this[it] / 32768f }

/**
 * Fallback for devices where playback capture only gives 48 kHz stereo: averages L/R and decimates by 3
 * (mean of 3 mono samples, a crude low-pass) to 16 kHz mono. [interleaved] is L,R,L,R...; an incomplete trailing
 * group of 3 stereo frames is ignored, so feed multiples of 6 shorts (a 100 ms block is 9 600 shorts).
 */
fun downmixDecimate48kStereoTo16kMono(interleaved: ShortArray, length: Int = interleaved.size): ShortArray {
    val outLen = length / 6
    return ShortArray(outLen) { j ->
        var sum = 0
        val base = j * 6
        for (k in 0 until 6) sum += interleaved[base + k]
        (sum / 6).toShort()
    }
}
