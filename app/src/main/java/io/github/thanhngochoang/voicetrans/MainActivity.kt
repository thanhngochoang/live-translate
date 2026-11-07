package io.github.thanhngochoang.voicetrans

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.AppRoot
import io.github.thanhngochoang.voicetrans.feature.ui.PermissionGate
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode by settings.themeMode.collectAsState(initial = ThemeMode.DARK)
            VoiceTransTheme(mode) {
                PermissionGate { AppRoot() }
            }
        }
    }

    companion object {
        /** Set by the Quick Settings tile; Live starts the session (with consent) when it becomes visible, then clears it. */
        const val EXTRA_AUTO_START = "io.github.thanhngochoang.voicetrans.EXTRA_AUTO_START"
    }
}
