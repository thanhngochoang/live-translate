package io.github.thanhngochoang.voicetrans.feature.asr

sealed interface AsrEvent {
    /** Interim text for the utterance in progress. Display only; never translated. */
    data class Partial(val text: String) : AsrEvent

    /** A finished utterance. [startMs] and [endMs] are stream positions (frames x frame length), not wall clock. */
    data class Final(val text: String, val startMs: Long, val endMs: Long) : AsrEvent
}
