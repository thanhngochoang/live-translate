package io.github.thanhngochoang.voicetrans.feature.ui

import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus

data class PackRow(val lang: String, val name: String, val state: ModelState)

/** Everything the gate screens need. [engineState] tracks NMT model readiness. */
data class AppUiState(
    val engineState: EngineState = EngineState.Ready,
    val backgroundSetupDone: Boolean = true,
    val background: BackgroundStatus = BackgroundStatus(false, false, false),
    val wifiOnly: Boolean = false,
    val ready: Boolean = engineState is EngineState.Ready,
    val packs: List<PackRow> = emptyList(),
)

class AppActions(
    val onDownload: () -> Unit = {},
    val onBackgroundContinue: () -> Unit = {},
    val onRequestNotifications: () -> Unit = {},
    val onRequestBattery: () -> Unit = {},
    val onRequestOverlay: () -> Unit = {},
)
