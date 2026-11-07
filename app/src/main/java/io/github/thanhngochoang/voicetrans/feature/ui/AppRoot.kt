package io.github.thanhngochoang.voicetrans.feature.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thanhngochoang.voicetrans.feature.ui.background.readBackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.modelsetup.ModelSetupViewModel

/** Production wiring of [AppNav]: the view model plus the system permission and settings launches. */
@Composable
fun AppRoot(vm: ModelSetupViewModel = hiltViewModel()) {
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
            onDownload = vm::download,
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
    AppNav(state, actions)
}
