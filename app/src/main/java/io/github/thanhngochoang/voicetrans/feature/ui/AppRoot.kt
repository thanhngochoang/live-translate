package io.github.thanhngochoang.voicetrans.feature.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thanhngochoang.voicetrans.feature.ui.background.readBackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.live.ConsentRequester
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveRoute
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveViewModel
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsActions
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsScreen
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsViewModel

/** Production wiring of [AppNav]: the view model plus the system permission and settings launches. */
@Composable
fun AppRoot(
    consent: ConsentRequester,
    autoStart: Boolean,
    onAutoStartConsumed: () -> Unit,
    vm: AppViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state = vm.uiState.collectAsStateWithLifecycle().value
    LifecycleResumeEffect(Unit) {
        vm.onResume(readBackgroundStatus(context)) // statuses can change in system settings while we are away
        onPauseOrDispose { }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onResume(readBackgroundStatus(context))
    }
    val actions = remember(vm, context) {
        AppActions(
            onDownload = {},
            onBackgroundContinue = vm::continueFromBackgroundSetup,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onRequestBattery = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onRequestOverlay = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
    val screens = remember(actions, consent, autoStart) {
        AppScreens(
            live = { nav ->
                val live: LiveViewModel = hiltViewModel()
                LiveRoute(
                    model = live, consent = consent, autoStart = autoStart, onAutoStartConsumed = onAutoStartConsumed,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onFixBackground = { nav.navigate(Routes.BACKGROUND_SETUP) },
                )
            },
            settings = { nav ->
                val vm2: SettingsViewModel = hiltViewModel()
                val ui = vm2.uiState.collectAsStateWithLifecycle().value
                LifecycleResumeEffect(Unit) { vm2.onResume(); onPauseOrDispose { } }
                var appLanguage by remember { mutableStateOf(currentAppLanguage()) }
                SettingsScreen(
                    ui.copy(appLanguage = appLanguage),
                    SettingsActions(
                        onAppLanguage = { appLanguage = it; it.apply() },
                        onTheme = vm2::setTheme, onSubtitleSize = vm2::setSubtitleSize, onOverlay = vm2::updateOverlay,
                        onRequestOverlayPermission = actions.onRequestOverlay, onRequestNotifications = actions.onRequestNotifications,
                        onRequestBattery = actions.onRequestBattery, onAutoStop = vm2::setAutoStop,
                        onDraftTranslation = vm2::setDraftTranslation,
                        onReopenBackgroundSetup = { nav.navigate(Routes.BACKGROUND_SETUP) }, overlayControls = vm2.overlayController,
                    ),
                    onBack = { nav.popBackStack() },
                )
            },
        )
    }
    AppNav(state, actions, screens)
}
