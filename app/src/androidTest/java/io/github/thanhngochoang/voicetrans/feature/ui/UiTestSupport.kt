package io.github.thanhngochoang.voicetrans.feature.ui

import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import kotlinx.coroutines.flow.MutableStateFlow

val noBackground = BackgroundStatus(notificationsGranted = false, batteryUnrestricted = false, overlayGranted = false)

/** A mutable app state plus actions that mutate it, standing in for the view model. */
class FakeApp(initial: AppUiState) {
    val state = MutableStateFlow(initial)
    var downloads = 0
    var overlayRequests = 0
    val actions = AppActions(
        onDownload = { downloads++ },
        onBackgroundContinue = { state.value = state.value.copy(backgroundSetupDone = true) },
        onRequestNotifications = {},
        onRequestBattery = {},
        onRequestOverlay = { overlayRequests++ },
    )
}
