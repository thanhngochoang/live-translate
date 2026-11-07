package io.github.thanhngochoang.voicetrans.feature.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Screen-capture consent. Every session needs a fresh consent, requested from a visible activity; the result is never
 * cached. Create it in `onCreate` (it registers an activity-result launcher).
 */
class CaptureConsent(private val activity: ComponentActivity) {
    private var onGranted: ((Int, Intent) -> Unit)? = null
    private var onDenied: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val granted = onGranted
        val denied = onDenied
        onGranted = null; onDenied = null
        if (result.resultCode == Activity.RESULT_OK && data != null) granted?.invoke(result.resultCode, data) else denied?.invoke()
    }

    fun request(onGranted: (resultCode: Int, data: Intent) -> Unit, onDenied: () -> Unit) {
        this.onGranted = onGranted
        this.onDenied = onDenied
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }
}
