package io.github.thanhngochoang.voicetrans.core.model

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {
    /** Asset path of the catalog. The file is small and ships in the APK; the models it lists do not. */
    const val CATALOG_ASSET = "models/models.json"

    @Provides @Singleton
    fun modelStore(@ApplicationContext context: Context): ModelStore = ModelStore(
        root = File(context.filesDir, "models"),
        catalog = { ModelCatalog.parse(context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }) },
        downloader = HttpModelDownloader(),
    )
}
