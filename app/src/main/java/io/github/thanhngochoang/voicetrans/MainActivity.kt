package io.github.thanhngochoang.voicetrans

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.session.CaptureConsent
import io.github.thanhngochoang.voicetrans.feature.session.TranslationService
import io.github.thanhngochoang.voicetrans.feature.ui.AppRoot
import io.github.thanhngochoang.voicetrans.feature.ui.PermissionGate
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveViewModel
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settings: SettingsRepository
    private val live: LiveViewModel by viewModels()
    private lateinit var consent: CaptureConsent
    private val autoStart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoStart.value = intent.getBooleanExtra(EXTRA_AUTO_START, false)
        // Registered unconditionally so a consent result that arrives after a rotation still starts the session.
        consent = CaptureConsent(
            this,
            onGranted = { resultCode, data -> TranslationService.start(this, resultCode, data) },
            onDenied = { live.onConsentDenied() },
        )
        setContent {
            val mode by settings.themeMode.collectAsState(initial = ThemeMode.DARK)
            VoiceTransTheme(mode) {
                PermissionGate {
                    AppRoot(
                        consent = consent,
                        autoStart = autoStart.value,
                        onAutoStartConsumed = { intent.removeExtra(EXTRA_AUTO_START); autoStart.value = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoStart.value = intent.getBooleanExtra(EXTRA_AUTO_START, false)
    }

    companion object {
        /** Set by the Quick Settings tile; Live starts the session (with consent) when it becomes visible, then clears it. */
        const val EXTRA_AUTO_START = "io.github.thanhngochoang.voicetrans.EXTRA_AUTO_START"
    }
}
