package io.github.thanhngochoang.voicetrans.feature.overlay

import android.content.Context
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.session.TranslationSession
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OverlayModule {
    /** The session is resolved lazily so creating the controller never loads the speech models. */
    @Provides @Singleton
    fun overlayController(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        session: Lazy<TranslationSession>,
    ): OverlayController = OverlayController(
        context = context,
        settings = settings.overlay,
        sessionProvider = { session.get() },
        saveOverlay = { settings.updateOverlay(it) },
        exitApp = { AppExit().exit(context) },
    )
}
