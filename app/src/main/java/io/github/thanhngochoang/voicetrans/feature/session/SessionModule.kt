package io.github.thanhngochoang.voicetrans.feature.session

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {
    @Provides @Singleton
    fun projectionProvider(@ApplicationContext context: Context): ProjectionProvider = MediaProjectionManagerProvider(context)
}
