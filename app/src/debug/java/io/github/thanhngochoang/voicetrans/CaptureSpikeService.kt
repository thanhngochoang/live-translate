package io.github.thanhngochoang.voicetrans

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Debug-only FGS for the playback-capture spike. Order matters on Android 14+:
 * startForeground(type mediaProjection) first, then getMediaProjection, then AudioRecord.
 * Writes filesDir/spike.wav and filesDir/spike.txt (findings) and logs under tag CaptureSpike.
 */
class CaptureSpikeService : Service() {

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mic = intent.getBooleanExtra("mic", false)
        val rate = intent.getIntExtra("rate", 16000)
        val channels = intent.getIntExtra("ch", 1)
        val report = StringBuilder()
        fun log(msg: String) { Log.i(TAG, msg); report.appendLine(msg) }

        val type = if (mic) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
            log("startForeground OK, type=${if (mic) "mediaProjection|microphone" else "mediaProjection"}")
        } catch (t: Throwable) {
            log("startForeground FAILED: $t")
            save(report)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            log("no consent data")
            save(report); stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "MediaProjection onStop") }
        }, null)

        thread(name = "capture-spike") {
            try {
                record(projection!!, rate, channels, ::log)
            } catch (t: Throwable) {
                log("record FAILED: $t")
            } finally {
                save(report)
                projection?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun record(mp: MediaProjection, rate: Int, channels: Int, log: (String) -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("RECORD_AUDIO not granted")
            return
        }
        val capture = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(mask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val chunkSamples = rate / 10 * channels // 100 ms
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, chunkSamples * 2 * 4))
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        log("AudioRecord state=${rec.state} (1=INITIALIZED) format=${rate}Hz ch=$channels PCM16 minBuf=$minBuf")
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }

        val out = File(filesDir, "spike.wav")
        val raf = RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(44)) }
        val buf = ShortArray(chunkSamples)
        var total = 0L
        var nonSilent = 0L
        var peak = 0
        val seenPolicies = linkedSetOf<String>()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        rec.startRecording()
        log("recording state=${rec.recordingState} (3=RECORDING)")
        val end = System.currentTimeMillis() + 10_000
        var lastPoll = 0L
        while (System.currentTimeMillis() < end) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) { log("read returned $n"); break }
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val s = buf[i].toInt()
                bb.putShort(buf[i])
                if (abs(s) > 64) nonSilent++
                if (abs(s) > peak) peak = abs(s)
            }
            raf.write(bb.array())
            total += n
            val now = System.currentTimeMillis()
            if (now - lastPoll > 1000) {
                lastPoll = now
                for (c in am.activePlaybackConfigurations) {
                    val a = c.audioAttributes
                    seenPolicies += "usage=${a.usage} allowedCapturePolicy=${a.allowedCapturePolicy}"
                }
            }
        }
        rec.stop(); rec.release()
        writeWavHeader(raf, total, rate, channels)
        raf.close()
        log("captured samples=$total (${total / channels / rate.toFloat()} s) nonSilent(>64)=$nonSilent peak=$peak")
        log("verdict: ${if (peak < 100) "SILENT" else "AUDIO PRESENT"}")
        log("active playback configs seen: ${if (seenPolicies.isEmpty()) "none" else seenPolicies.joinToString("; ")} (policy 1=ALLOW_ALL 2=SYSTEM 3=NONE)")
    }

    private fun writeWavHeader(raf: RandomAccessFile, samples: Long, rate: Int, channels: Int) {
        val dataLen = (samples * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataLen)
        raf.seek(0); raf.write(h.array())
    }

    private fun save(report: StringBuilder) {
        File(filesDir, "spike.txt").writeText(report.toString())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capture spike", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Capture spike running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        const val TAG = "CaptureSpike"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture_spike"
        private const val NOTIF_ID = 4242
    }
}
