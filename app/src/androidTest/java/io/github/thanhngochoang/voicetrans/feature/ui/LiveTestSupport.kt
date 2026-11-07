package io.github.thanhngochoang.voicetrans.feature.ui

import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.live.ConsentRequester
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun utt(
    id: Long, source: String, translation: String?, lang: SourceLang = SourceLang.EN, target: String = "vi",
    kind: UtteranceKind = UtteranceKind.SPEECH, start: Long = id * 1000, end: Long = id * 1000 + 900, failed: Boolean = false,
) = Utterance(id, kind, source, lang, target, translation, start, end, failed)

val goodBackground = BackgroundStatus(notificationsGranted = true, batteryUnrestricted = true, overlayGranted = true)

class FakeLiveModel : LiveModel {
    override val utterances = MutableStateFlow<List<Utterance>>(emptyList())
    override val partial = MutableStateFlow<String?>(null)
    override val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    override val captureWarning = MutableStateFlow<CaptureWarning?>(null)
    override val targetName = MutableStateFlow("Tiếng Việt")
    override val consentExplainerSeen = MutableStateFlow(true)
    override val consentDenied = MutableStateFlow(false)
    override val background = MutableStateFlow(goodBackground)
    override val subtitleSizeSp = MutableStateFlow(20)
    override val exitReason = MutableStateFlow<Int?>(null)
    var stops = 0
    override suspend fun markExplainerSeen() { consentExplainerSeen.value = true }
    override fun dismissExitReason() { exitReason.value = null }
    override fun stopSession() { stops++ }
    override fun refreshBackground() {}
}

class FakeConsent(private val model: FakeLiveModel? = null, private val deny: Boolean = false) : ConsentRequester {
    var requests = 0
    override fun request() {
        requests++
        if (deny) model?.consentDenied?.value = true
    }
}

@Suppress("unused") val liveStateFlowType: StateFlow<Int>? = null
