package io.github.thanhngochoang.voicetrans.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Captures audio playing on the phone (AudioPlaybackCapture, no microphone). [projection] must come from a
 * mediaProjection foreground service that already called startForeground. Asks for 16 kHz mono PCM16 and falls back
 * to 48 kHz stereo (downmixed and decimated here) if that cannot be initialised. Emits 100 ms frames.
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : AudioSource {

    override fun frames(): Flow<FloatArray> = flow {
        val (format, record) = openWithFallback { buildRecord(it) }
        try {
            record.startRecording()
            val buffer = ShortArray(format.shortsPer100ms)
            while (true) {
                currentCoroutineContext().ensureActive()
                var filled = 0
                while (filled < buffer.size) {
                    val n = record.read(buffer, filled, buffer.size - filled)
                    if (n < 0) throw AudioUnavailableException("AudioRecord.read returned $n")
                    filled += n
                }
                emit(
                    when (format) {
                        CaptureFormat.MONO_16K -> buffer.toFloatPcm()
                        CaptureFormat.STEREO_48K -> downmixDecimate48kStereoTo16kMono(buffer).toFloatPcm()
                    },
                )
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRecord(format: CaptureFormat): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val mask = if (format.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, format.shortsPer100ms * 2 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return record
    }
}
