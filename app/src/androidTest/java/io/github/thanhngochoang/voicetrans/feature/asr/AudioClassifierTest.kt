package io.github.thanhngochoang.voicetrans.feature.asr

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.WavReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Product-level criteria (coordinator, 2026-11-07). SINGING and MUSIC are both shown as a note and never translated,
 * so confusing them is harmless. The harmful errors are vocals turning into SPEECH and speech turning into non-SPEECH.
 * Clips are chosen by content, never by model output. The per-folder expected-class rate is logged, not asserted.
 */
class AudioClassifierTest {

    private lateinit var classifier: SherpaAudioClassifier
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun setUp() {
        classifier = SherpaAudioClassifier(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    }

    @After
    fun tearDown() = classifier.close()

    private fun clips(folder: String) = testAssets.list("clips/$folder")!!.filter { it.endsWith(".wav") }.sorted()

    private fun windows(path: String): List<FloatArray> {
        val samples = WavReader.toFloats(testAssets.open(path).use { it.readBytes() })
        return WavReader.chunks(samples, 16000).filter { it.size == 16000 }
    }

    /** Classes of every 1 s window per clip, logging one line per clip. */
    private fun classify(folder: String): Map<String, List<AudioClass>> {
        val result = LinkedHashMap<String, List<AudioClass>>()
        for (clip in clips(folder)) {
            val classes = windows("clips/$folder/$clip").map { classify(classifier.classify(it)) }
            result[clip] = classes
            Log.i("CLS", "$folder/$clip got=${classes.groupingBy { it }.eachCount()}")
        }
        return result
    }

    private fun rate(folder: String, classes: Map<String, List<AudioClass>>, ok: (AudioClass) -> Boolean): Double {
        val all = classes.values.flatten()
        val r = all.count(ok).toDouble() / all.size
        Log.i("CLS", "FOLDER $folder rate=${"%.3f".format(r)} (${all.count(ok)}/${all.size})")
        return r
    }

    private fun logExpected(folder: String, classes: Map<String, List<AudioClass>>, expected: AudioClass) {
        val all = classes.values.flatten()
        Log.i("CLS", "EXPECTED $folder $expected rate=${"%.3f".format(all.count { it == expected }.toDouble() / all.size)} (${all.count { it == expected }}/${all.size})")
    }

    @Test fun speech_atLeast90PercentSpeech() {
        val c = classify("speech")
        assertTrue(clips("speech").size >= 5)
        logExpected("speech", c, AudioClass.SPEECH)
        assertTrue(rate("speech", c) { it == AudioClass.SPEECH } >= 0.9)
    }

    @Test fun speechOverMusic_windowsAndClipMajority() {
        val c = classify("speech_over_music")
        assertTrue(clips("speech_over_music").size >= 5)
        logExpected("speech_over_music", c, AudioClass.SPEECH)
        assertTrue(rate("speech_over_music", c) { it == AudioClass.SPEECH } >= 0.8)
        for ((clip, classes) in c) {
            val timeline = ClassTimeline()
            classes.forEachIndexed { i, cls -> timeline.add(i * 1000L, cls) }
            assertEquals("clip $clip", AudioClass.SPEECH, timeline.majority(0, classes.size * 1000L))
        }
    }

    @Test fun singing_atLeast90PercentNotSpeech() {
        val c = classify("singing")
        assertTrue(clips("singing").size >= 5)
        logExpected("singing", c, AudioClass.SINGING)
        assertTrue(rate("singing", c) { it != AudioClass.SPEECH } >= 0.9)
    }

    @Test fun singingHard_atLeast90PercentNotSpeech() {
        val c = classify("singing_hard")
        logExpected("singing_hard", c, AudioClass.SINGING)
        assertTrue(rate("singing_hard", c) { it != AudioClass.SPEECH } >= 0.9)
    }

    @Test fun music_atLeast90PercentNotSpeech() {
        val c = classify("music")
        assertTrue(clips("music").size >= 5)
        logExpected("music", c, AudioClass.MUSIC)
        assertTrue(rate("music", c) { it != AudioClass.SPEECH } >= 0.9)
    }

    @Test
    fun latency_perWindow_under150ms() {
        val ws = ArrayList<FloatArray>()
        for (f in listOf("speech", "music", "singing")) for (c in clips(f)) ws += windows("clips/$f/$c")
        repeat(5) { classifier.classify(ws[it]) } // warm-up
        val times = ws.take(50).map { w ->
            val t = System.nanoTime(); classifier.classify(w); (System.nanoTime() - t) / 1_000_000
        }.sorted()
        val median = times[times.size / 2]
        Log.i("CLS", "latency median=${median}ms min=${times.first()} max=${times.last()} (emulator API 35)")
        assertTrue("median ${median}ms (emulator API 35)", median < 150)
    }
}
