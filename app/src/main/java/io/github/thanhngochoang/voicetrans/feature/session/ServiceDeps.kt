package io.github.thanhngochoang.voicetrans.feature.session

import android.content.Context
import android.media.projection.MediaProjection
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackCaptureSource
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayController

/** What the foreground service and the tile need. Resolved through Hilt in production, replaced directly in unit tests. */
class ServiceDeps(
    val session: TranslationSession,
    val settings: SettingsRepository,
    val projectionProvider: ProjectionProvider,
    val audioSource: (MediaProjection) -> AudioSource = { error("audioSource must be provided") },
    val overlay: OverlayController? = null,
) {
    companion object {
        fun fromEntryPoint(context: Context): ServiceDeps {
            val e = EntryPointAccessors.fromApplication(context.applicationContext, ServiceEntryPoint::class.java)
            val app = context.applicationContext
            return ServiceDeps(
                e.session(), e.settings(), e.projectionProvider(),
                audioSource = { PlaybackCaptureSource(it, app) }, overlay = e.overlayController(),
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun session(): TranslationSession
    fun settings(): SettingsRepository
    fun projectionProvider(): ProjectionProvider
    fun overlayController(): OverlayController
}
