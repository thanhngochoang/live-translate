package io.github.thanhngochoang.voicetrans.feature.asr

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.WavReader
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioClassifierTest {

    private lateinit var classifier: SherpaAudioClassifier
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun setUp() {
        classifier = SherpaAudioClassifier(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    }

    @After
    fun tearDown() = classifier.close()

    private fun windows(path: String): List<FloatArray> {
        val samples = WavReader.toFloats(testAssets.open(path).use { it.readBytes() })
        return WavReader.chunks(samples, 16000).filter { it.size == 16000 }
    }

    /** Classifies every 1 s window of every clip in [folder]; logs one confusion line per clip. */
    private fun accuracy(folder: String, expected: AudioClass): Double {
        var ok = 0
        var total = 0
        val clips = testAssets.list("clips/$folder")!!.filter { it.endsWith(".wav") }.sorted()
        assertTrue("need >= 5 clips in $folder", clips.size >= 5)
        for (clip in clips) {
            val counts = HashMap<AudioClass, Int>()
            val tops = ArrayList<String>()
            for (w in windows("clips/$folder/$clip")) {
                val scores = classifier.classify(w)
                val cls = classify(scores)
                counts[cls] = (counts[cls] ?: 0) + 1
                tops += scores.entries.sortedByDescending { it.value }.take(3)
                    .joinToString("+") { "${it.key.substringBefore(',')}:${"%.2f".format(it.value)}" }
                total++
                if (cls == expected) ok++
            }
            Log.i("CLS", "$folder/$clip expected=$expected got=$counts")
            Log.i("CLS", "  top3 per window: ${tops.joinToString(" | ")}")
        }
        val acc = ok.toDouble() / total
        Log.i("CLS", "FOLDER $folder expected=$expected accuracy=${"%.2f".format(acc)} ($ok/$total)")
        return acc
    }

    @Test fun speech_atLeast80Percent() = assertTrue(accuracy("speech", AudioClass.SPEECH) >= 0.8)

    @Test fun singing_atLeast80Percent() = assertTrue(accuracy("singing", AudioClass.SINGING) >= 0.8)

    @Test fun music_atLeast80Percent() = assertTrue(accuracy("music", AudioClass.MUSIC) >= 0.8)

    @Test fun speechOverMusic_isSpeech_atLeast80Percent() =
        assertTrue(accuracy("speech_over_music", AudioClass.SPEECH) >= 0.8)

    /** Product view: SINGING and MUSIC are both shown as a note and never translated, so what matters is not SPEECH. */
    @Test fun singing_rarelyMistakenForSpeech() {
        var notSpeech = 0
        var total = 0
        for (clip in testAssets.list("clips/singing")!!.filter { it.endsWith(".wav") }) {
            for (w in windows("clips/singing/$clip")) {
                total++
                if (classify(classifier.classify(w)) != AudioClass.SPEECH) notSpeech++
            }
        }
        Log.i("CLS", "singing not-SPEECH: $notSpeech/$total")
        assertTrue("$notSpeech/$total", notSpeech.toDouble() / total >= 0.9)
    }

    /** Informational: old recordings, rap and AI-generated songs, which the tagging model does not recognise as singing. Logged, not asserted. */
    @Test fun singingHard_loggedOnly() {
        accuracy("singing_hard", AudioClass.SINGING)
    }

    @Test
    fun latency_perWindow_under150ms() {
        val ws = ArrayList<FloatArray>()
        for (f in listOf("speech", "music", "singing")) {
            for (c in testAssets.list("clips/$f")!!.filter { it.endsWith(".wav") }.sorted()) ws += windows("clips/$f/$c")
        }
        repeat(5) { classifier.classify(ws[it]) } // warm-up
        val times = ws.take(50).map { w ->
            val t = System.nanoTime(); classifier.classify(w); (System.nanoTime() - t) / 1_000_000
        }.sorted()
        val median = times[times.size / 2]
        Log.i("CLS", "latency median=${median}ms min=${times.first()} max=${times.last()} (emulator API 35)")
        assertTrue("median ${median}ms (emulator API 35)", median < 150)
    }
}
