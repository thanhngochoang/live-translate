package io.github.thanhngochoang.voicetrans.di

import android.content.Context
import android.media.AudioManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.core.audio.AudioManagerPlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.asr.SherpaAsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.SherpaAudioClassifier
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkRatioLanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.LanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import io.github.thanhngochoang.voicetrans.feature.translate.hybrid.HybridTranslator
import io.github.thanhngochoang.voicetrans.feature.translate.mlkit.MlKitTranslator
import javax.inject.Singleton

/** Hot shared file: keep edits additive. `ProjectionProvider` is bound in Task 7. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds abstract fun languageDetector(impl: CjkRatioLanguageDetector): LanguageDetector

    @Binds abstract fun translator(impl: MlKitTranslator): Translator

    @Binds abstract fun asrEngine(impl: SherpaAsrEngine): AsrEngine

    @Binds abstract fun packManager(impl: MlKitTranslator): PackManager

    companion object {
        @Provides @Singleton
        fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

        @Provides @Singleton
        fun settingsRepository(dataStore: DataStore<Preferences>): SettingsRepository = SettingsRepository(dataStore)

        @Provides @Singleton
        fun sherpaAsrEngine(@ApplicationContext context: Context): SherpaAsrEngine = SherpaAsrEngine(context.assets)

        @Provides @Singleton
        fun audioClassifier(@ApplicationContext context: Context): AudioClassifier = SherpaAudioClassifier(context.assets)

        @Provides @Singleton
        fun playbackProbe(@ApplicationContext context: Context): PlaybackProbe =
            AudioManagerPlaybackProbe(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    }
}
