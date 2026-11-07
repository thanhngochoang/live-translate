package io.github.thanhngochoang.voicetrans

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayController
import javax.inject.Inject

@HiltAndroidApp
class VoiceTransApp : Application() {
    @Inject lateinit var overlayController: OverlayController

    override fun onCreate() {
        super.onCreate()
        overlayController.bindProcessLifecycle() // foreground/background drives the floating subtitle
    }
}
