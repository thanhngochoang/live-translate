package io.github.thanhngochoang.voicetrans.feature.ui

import io.github.thanhngochoang.voicetrans.core.model.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus

/** Everything the gate screens need. [modelState] tracks the model download, [engineState] NMT model readiness. */
data class AppUiState(
    val engineState: EngineState = EngineState.Ready,
    val backgroundSetupDone: Boolean = true,
    val background: BackgroundStatus = BackgroundStatus(false, false, false),
    val ready: Boolean = engineState is EngineState.Ready,
    val modelState: ModelState = ModelState.Ready,
)

class AppActions(
    val onDownload: () -> Unit = {},
    val onBackgroundContinue: () -> Unit = {},
    val onRequestNotifications: () -> Unit = {},
    val onRequestBattery: () -> Unit = {},
    val onRequestOverlay: () -> Unit = {},
)
