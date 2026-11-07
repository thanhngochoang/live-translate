package io.github.thanhngochoang.voicetrans.feature.ui

import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus

data class PackRow(val lang: String, val name: String, val state: ModelState)

/** Everything the gate screens need. [ready] means every required pack (en, zh and the target) is Ready. */
data class AppUiState(
    val ready: Boolean,
    val packs: List<PackRow>,
    val backgroundSetupDone: Boolean,
    val background: BackgroundStatus,
    val wifiOnly: Boolean,
)

class AppActions(
    val onDownload: () -> Unit,
    val onBackgroundContinue: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onRequestBattery: () -> Unit,
    val onRequestOverlay: () -> Unit,
)
