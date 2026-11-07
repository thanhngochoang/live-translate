package io.github.thanhngochoang.voicetrans.feature.asr

import java.util.TreeMap

/** Per-window classes keyed by window start. Keeps the last 60 s. Not thread-safe; use from one thread. */
class ClassTimeline(private val windowMs: Long = 1_000) {
    private val windows = TreeMap<Long, AudioClass>()

    fun add(startMs: Long, cls: AudioClass) {
        windows[startMs] = cls
        val cutoff = windows.lastKey() - RETAIN_MS
        while (windows.firstKey() < cutoff) windows.pollFirstEntry()
    }

    /** Majority class of windows overlapping [startMs, endMs). A tie goes to SPEECH, so speech is never dropped by mistake. */
    fun majority(startMs: Long, endMs: Long): AudioClass {
        val counts = HashMap<AudioClass, Int>()
        for ((s, c) in windows.subMap(startMs - windowMs + 1, endMs)) counts[c] = (counts[c] ?: 0) + 1
        if (counts.isEmpty()) return AudioClass.OTHER
        val max = counts.values.max()
        val top = counts.filterValues { it == max }.keys
        return if (AudioClass.SPEECH in top) AudioClass.SPEECH else top.first()
    }

    private companion object { const val RETAIN_MS = 60_000L }
}
