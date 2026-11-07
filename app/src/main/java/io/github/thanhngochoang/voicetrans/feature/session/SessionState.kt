package io.github.thanhngochoang.voicetrans.feature.session

enum class StopReason { USER, AUDIO_ERROR, PROJECTION_ENDED, IDLE_TIMEOUT }

sealed interface SessionState {
    data object Idle : SessionState
    data object Running : SessionState
    data class Stopped(val reason: StopReason) : SessionState
}

enum class CaptureWarning { BLOCKED_BY_APP }
