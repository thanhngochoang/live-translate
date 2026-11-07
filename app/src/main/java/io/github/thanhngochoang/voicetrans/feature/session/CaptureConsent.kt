package io.github.thanhngochoang.voicetrans.feature.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import io.github.thanhngochoang.voicetrans.feature.ui.live.ConsentRequester

/**
 * Screen-capture consent. Every session needs a fresh consent, requested from a visible activity; the result is never
 * cached. Create it in `onCreate`, unconditionally: it registers an activity-result launcher, and the handlers given
 * here receive a result that arrives after the activity was recreated (rotation while the system dialog is open).
 */
class CaptureConsent(
    private val activity: ComponentActivity,
    private val onGranted: (resultCode: Int, data: Intent) -> Unit,
    private val onDenied: () -> Unit,
    registry: ActivityResultRegistry? = null,
) : ConsentRequester {

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), registry ?: activity.activityResultRegistry) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) onGranted(result.resultCode, data) else onDenied()
    }

    override fun request() {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }
}
