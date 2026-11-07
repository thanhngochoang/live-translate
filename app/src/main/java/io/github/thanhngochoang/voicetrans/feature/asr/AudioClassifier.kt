package io.github.thanhngochoang.voicetrans.feature.asr

import java.io.Closeable

interface AudioClassifier : Closeable {
    /** [window] is 16 kHz mono float audio (about 1 s). Returns AudioSet label to probability for the top-k labels. */
    fun classify(window: FloatArray): Map<String, Float>
}
