package io.github.thanhngochoang.voicetrans.feature.ui.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.ExitReasonReporter
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.TranslationService
import io.github.thanhngochoang.voicetrans.feature.session.TranslationSession
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.ui.background.readBackgroundStatus
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live state. The session is a process singleton created off the main thread (it creates the speech engines), so the
 * flows below follow it once it exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionProvider: Provider<TranslationSession>,
    private val settings: SettingsRepository,
) : ViewModel(), LiveModel {

    private val session = MutableStateFlow<TranslationSession?>(null)

    private fun <T> fromSession(initial: T, pick: (TranslationSession) -> Flow<T>): StateFlow<T> =
        session.flatMapLatest { s -> if (s == null) flowOf(initial) else pick(s) }.stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    override val utterances = fromSession(emptyList<Utterance>()) { it.utterances }
    override val partial = fromSession<String?>(null) { it.partial }
    override val sessionState = fromSession<SessionState>(SessionState.Idle) { it.state }
    override val captureWarning = fromSession<CaptureWarning?>(null) { it.captureWarning }
    override val targetName = settings.targetLanguage.map { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    override val consentExplainerSeen = settings.consentExplainerSeen.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    override val subtitleSizeSp = settings.subtitleSizeSp.stateIn(viewModelScope, SharingStarted.Eagerly, 20)
    override val consentDenied = MutableStateFlow(false)
    override val background = MutableStateFlow(readBackgroundStatus(context))
    override val exitReason = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch(Dispatchers.Default) { session.value = sessionProvider.get() }
        if (!exitReasonShown) {
            exitReasonShown = true
            exitReason.value = ExitReasonReporter.fromSystem(context)
        }
        viewModelScope.launch { sessionState.collect { if (it == SessionState.Running) consentDenied.value = false } }
    }

    fun onConsentDenied() { consentDenied.value = true }

    override suspend fun markExplainerSeen() = settings.setConsentExplainerSeen(true)

    override fun dismissExitReason() { exitReason.value = null }

    override fun stopSession() {
        session.value?.stop()
        TranslationService.stop(context)
    }

    override fun refreshBackground() { background.value = readBackgroundStatus(context) }

    private companion object {
        /** The exit reason is reported once per process, not on every screen visit. */
        @Volatile var exitReasonShown = false
    }
}

