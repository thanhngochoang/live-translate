package io.github.thanhngochoang.voicetrans.feature.ui.live

import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import kotlinx.coroutines.flow.StateFlow

/** Asks for screen-capture consent. The handlers live in the implementation (registered in the activity's onCreate). */
interface ConsentRequester {
    fun request()
}

/** What the Live route needs; implemented by [LiveViewModel] and by fakes in tests. */
interface LiveModel {
    val utterances: StateFlow<List<Utterance>>
    val partial: StateFlow<String?>
    val sessionState: StateFlow<SessionState>
    val captureWarning: StateFlow<CaptureWarning?>
    val targetName: StateFlow<String>
    val consentExplainerSeen: StateFlow<Boolean>
    val consentDenied: StateFlow<Boolean>
    val background: StateFlow<BackgroundStatus>
    val subtitleSizeSp: StateFlow<Int>

    /** String-resource id of a recent abnormal exit (memory, battery...), shown once, or null. */
    val exitReason: StateFlow<Int?>

    suspend fun markExplainerSeen()
    fun dismissExitReason()
    fun stopSession()
    fun refreshBackground()
}
