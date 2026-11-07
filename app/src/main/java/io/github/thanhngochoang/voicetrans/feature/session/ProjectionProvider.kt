package io.github.thanhngochoang.voicetrans.feature.session

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/** Wraps `MediaProjectionManager.getMediaProjection` so tests can inject a fake. */
fun interface ProjectionProvider {
    fun get(resultCode: Int, data: Intent): MediaProjection
}

class MediaProjectionManagerProvider(private val context: Context) : ProjectionProvider {
    override fun get(resultCode: Int, data: Intent): MediaProjection {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return checkNotNull(manager.getMediaProjection(resultCode, data)) { "getMediaProjection returned null (consent token already used?)" }
    }
}
