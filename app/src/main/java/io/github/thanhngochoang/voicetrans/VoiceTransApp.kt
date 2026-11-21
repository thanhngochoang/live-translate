package io.github.thanhngochoang.voicetrans

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.thanhngochoang.voicetrans.core.model.ModelStore
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayController
import io.github.thanhngochoang.voicetrans.feature.translate.nmt.NmtTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class VoiceTransApp : Application() {
    @Inject lateinit var overlayController: OverlayController
    @Inject lateinit var nmtTranslator: NmtTranslator
    @Inject lateinit var modelStore: ModelStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        overlayController.bindProcessLifecycle() // foreground/background drives the floating subtitle
        modelStore.startRefresh() // finds out whether the models still have to be downloaded
        nmtTranslator.warmUp(appScope) // waits for the models, then loads them
    }
}
