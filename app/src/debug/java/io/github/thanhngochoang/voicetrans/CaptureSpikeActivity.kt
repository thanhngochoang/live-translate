package io.github.thanhngochoang.voicetrans

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Debug-only spike (plan Task 1 Step 6). One button: ask for screen-capture consent, start the debug
 * mediaProjection FGS, which records 10 s of AudioPlaybackCapture to filesDir/spike.wav.
 *
 * Launch extras (all optional, used by scripts/run-capture-spike.sh):
 *   auto (bool)  press the button automatically on start
 *   mic  (bool)  FGS type mediaProjection|microphone instead of mediaProjection alone
 *   rate (int)   sample rate, default 16000
 *   ch   (int)   channels 1 or 2, default 1
 */
class CaptureSpikeActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestConsent() else status.text = "RECORD_AUDIO denied"
        }

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, CaptureSpikeService::class.java)
                    .putExtra(CaptureSpikeService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureSpikeService.EXTRA_DATA, result.data)
                    .putExtra("mic", intent.getBooleanExtra("mic", false))
                    .putExtra("rate", intent.getIntExtra("rate", 16000))
                    .putExtra("ch", intent.getIntExtra("ch", 1))
                startForegroundService(svc)
                status.text = "Recording 10 s. Play audio now. Result: filesDir/spike.wav + spike.txt"
            } else {
                status.text = "Consent denied (resultCode=${result.resultCode})"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Idle"; textSize = 16f }
        val button = Button(this).apply {
            text = "Start 10 s playback capture"
            setOnClickListener { start() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(button)
            addView(status)
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (intent.getBooleanExtra("auto", false) && savedInstanceState == null) button.post { start() }
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestConsent()
        }
    }

    private fun requestConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        consent.launch(captureIntent)
    }
}
