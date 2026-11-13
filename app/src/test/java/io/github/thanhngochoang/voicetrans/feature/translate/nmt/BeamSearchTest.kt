package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class BeamSearchTest {

    private fun testMeta(
        eos: Int = 2,
        pad: Int = 1,
        unk: Int = 3,
        decoderStart: Int = 2,
        forcedBos: Int? = null,
        badWords: List<List<Int>> = emptyList(),
        forcedEosAtMax: Boolean = false,
        lengthPenalty: Float = 1.0f,
        earlyStopping: Boolean = true
    ) = NmtMeta(
        model = "test",
        eos = eos,
        pad = pad,
        unk = unk,
        decoderStart = decoderStart,
        forcedBos = forcedBos,
        srcLangToken = null,
        badWords = badWords,
        forcedEosAtMax = forcedEosAtMax,
        lengthPenalty = lengthPenalty,
        earlyStopping = earlyStopping,
        numLayers = 1,
        numHeads = 1,
        headDim = 1,
        pastInputNames = emptyList()
    )

    @Test
    fun greedy_picksArgmaxUntilEos() {
        val meta = testMeta(eos = 2, decoderStart = 0)
        // Vocab size 5: 0=start, 1=pad, 2=eos, 3=tokenA, 4=tokenB
        val scorer = StepScorer { beams ->
            val step = beams[0].size // starts with [0] -> step 1
            Array(beams.size) {
                when (step) {
                    1 -> floatArrayOf(-10f, -10f, -10f, 0f, -1f) // pick 3
                    2 -> floatArrayOf(-10f, -10f, -10f, -1f, 0f) // pick 4
                    3 -> floatArrayOf(-10f, -10f, 0f, -10f, -10f) // pick 2 (eos)
                    else -> floatArrayOf(-10f, -10f, 0f, -10f, -10f)
                }
            }
        }
        val result = beamSearch(scorer, meta, beams = 1, maxNewTokens = 10)
        assertArrayEquals(intArrayOf(3, 4, 2), result)
    }

    @Test
    fun beam_prefersHigherFinalScoreOverGreedyPrefix() {
        val meta = testMeta(eos = 2, decoderStart = 0)
        // Vocab: 0=start, 1=pad, 2=eos, 3=tokenA, 4=tokenB
        // Greedy:
        //   step 1: token 3 (logit 5) beats token 4 (logit 4) -> picks 3
        //   step 2: from [0, 3], eos 2 (logit -0.9) beats others (-1.0) -> greedy picks 2. Result: [3, 2]
        // Beam (beams = 2):
        //   step 1: beams = [[0, 3], [0, 4]]
        //   step 2: from [0, 4], token 3 has logit 10 (logProb ~ 0)
        //   step 3: from [0, 4, 3], eos 2 has logit 10 (logProb ~ 0)
        //   Result: [4, 3, 2] total score (-0.44) beats [3, 2] (-0.92).
        val scorer = StepScorer { beams ->
            Array(beams.size) { b ->
                val tokens = beams[b]
                val last = tokens.last()
                when {
                    tokens.size == 1 -> floatArrayOf(-10f, -10f, -10f, 5f, 4f)
                    tokens.size == 2 && last == 3 -> floatArrayOf(-1f, -1f, -0.9f, -1f, -1f) // greedy picks eos 2
                    tokens.size == 2 && last == 4 -> floatArrayOf(-10f, -10f, -10f, 10f, -10f) // emits 3
                    tokens.size == 3 && last == 3 -> floatArrayOf(-10f, -10f, 10f, -10f, -10f) // emits eos
                    else -> floatArrayOf(-10f, -10f, 0f, -10f, -10f)
                }
            }
        }
        val greedy = beamSearch(scorer, meta, beams = 1, maxNewTokens = 5)
        assertArrayEquals(intArrayOf(3, 2), greedy)

        val beam = beamSearch(scorer, meta, beams = 2, maxNewTokens = 5)
        assertArrayEquals(intArrayOf(4, 3, 2), beam)
    }

    @Test
    fun forcedBos_isFirstToken() {
        val meta = testMeta(eos = 2, decoderStart = 2, forcedBos = 256140)
        val scorer = StepScorer { beams ->
            Array(beams.size) {
                // Return dummy logits where token 5 has highest logit
                FloatArray(256200) { if (it == 5) 0f else -10f }
            }
        }
        val result = beamSearch(scorer, meta, beams = 1, maxNewTokens = 1)
        assertEquals(256140, result[0])
    }

    @Test
    fun badWords_neverEmitted() {
        val meta = testMeta(eos = 0, pad = 65000, decoderStart = 65000, badWords = listOf(listOf(65000)))
        val scorer = StepScorer { beams ->
            Array(beams.size) {
                // Pad has highest logit 10.0, token 5 has 5.0, eos 0 has 1.0
                FloatArray(65001) { idx ->
                    when (idx) {
                        65000 -> 10f
                        5 -> 5f
                        0 -> 1f
                        else -> -10f
                    }
                }
            }
        }
        val result = beamSearch(scorer, meta, beams = 1, maxNewTokens = 2)
        // Pad 65000 must be banned, so token 5 is emitted
        assertEquals(5, result[0])
    }

    @Test
    fun forcedEosAtMax_endsWithEos() {
        val meta = testMeta(eos = 0, pad = 65000, decoderStart = 65000, forcedEosAtMax = true)
        val scorer = StepScorer { beams ->
            Array(beams.size) {
                // Token 5 always has highest score, eos 0 has low score
                FloatArray(65001) { if (it == 5) 0f else -10f }
            }
        }
        val result = beamSearch(scorer, meta, beams = 1, maxNewTokens = 3)
        assertEquals(3, result.size)
        assertEquals(0, result.last())
    }

    @Test
    fun lengthPenalty_appliedAsHuggingFace() {
        // Shorter sequence with lower raw score vs longer sequence with higher raw score
        val metaHighPenalty = testMeta(eos = 2, decoderStart = 0, lengthPenalty = 2.0f)
        val metaLowPenalty = testMeta(eos = 2, decoderStart = 0, lengthPenalty = -2.0f)

        // Branch A: [3, eos] -> length 2, sumLogProbs = -1.0
        // Branch B: [4, 5, eos] -> length 3, sumLogProbs = -1.2
        val scorer = StepScorer { beams ->
            Array(beams.size) { b ->
                val tokens = beams[b]
                val last = tokens.last()
                when {
                    tokens.size == 1 -> floatArrayOf(-10f, -10f, -10f, -0.5f, -0.4f, -10f)
                    tokens.size == 2 && last == 3 -> floatArrayOf(-10f, -10f, -0.5f, -10f, -10f, -10f) // finishes A
                    tokens.size == 2 && last == 4 -> floatArrayOf(-10f, -10f, -10f, -10f, -10f, -0.4f) // emits 5
                    tokens.size == 3 && last == 5 -> floatArrayOf(-10f, -10f, -0.4f, -10f, -10f, -10f) // finishes B
                    else -> floatArrayOf(-10f, -10f, 0f, -10f, -10f, -10f)
                }
            }
        }

        // High penalty favors longer sequence (divides by length^2)
        val resLong = beamSearch(scorer, metaHighPenalty, beams = 2, maxNewTokens = 4)
        assertArrayEquals(intArrayOf(4, 5, 2), resLong)

        // Low / negative penalty favors shorter sequence
        val resShort = beamSearch(scorer, metaLowPenalty, beams = 2, maxNewTokens = 4)
        assertArrayEquals(intArrayOf(3, 2), resShort)
    }
}
