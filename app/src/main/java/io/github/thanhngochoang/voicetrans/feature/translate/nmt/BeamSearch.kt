package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

fun interface StepScorer {
    fun logProbs(beamTokens: List<IntArray>): Array<FloatArray>
}

private data class Hypothesis(
    val tokens: IntArray,
    val sumLogProbs: Float,
    val score: Float
)

fun beamSearch(
    scorer: StepScorer,
    meta: NmtMeta,
    beams: Int,
    maxNewTokens: Int
): IntArray {
    if (beams <= 1) {
        return greedySearch(scorer, meta, maxNewTokens)
    }

    var active = listOf(Hypothesis(intArrayOf(meta.decoderStart), 0.0f, 0.0f))
    val finished = mutableListOf<Hypothesis>()

    for (step in 1..maxNewTokens) {
        val logitsArray = scorer.logProbs(active.map { it.tokens })

        val candidates = mutableListOf<Hypothesis>()

        for (b in active.indices) {
            val hyp = active[b]
            val logProbs = logSoftmax(logitsArray[b])

            if (step == 1 && meta.forcedBos != null) {
                val forced = meta.forcedBos
                for (i in logProbs.indices) {
                    if (i != forced) logProbs[i] = Float.NEGATIVE_INFINITY
                }
                logProbs[forced] = 0.0f
            }

            for (bw in meta.badWords) {
                if (bw.size == 1 && bw[0] in logProbs.indices) {
                    logProbs[bw[0]] = Float.NEGATIVE_INFINITY
                }
            }

            if (step == maxNewTokens && meta.forcedEosAtMax) {
                for (i in logProbs.indices) {
                    if (i != meta.eos) logProbs[i] = Float.NEGATIVE_INFINITY
                }
                logProbs[meta.eos] = 0.0f
            }

            // Pick top 2 * beams tokens
            val topTokens = findTopK(logProbs, 2 * beams)
            for (tokenId in topTokens) {
                val lp = logProbs[tokenId]
                if (lp == Float.NEGATIVE_INFINITY) continue
                val newTokens = hyp.tokens + tokenId
                val newSum = hyp.sumLogProbs + lp
                val genLen = newTokens.size - 1
                val penaltyFactor = genLen.toDouble().pow(meta.lengthPenalty.toDouble()).toFloat()
                val newScore = if (penaltyFactor > 0f) newSum / penaltyFactor else newSum

                candidates.add(Hypothesis(newTokens, newSum, newScore))
            }
        }

        // Sort candidates by score descending
        candidates.sortByDescending { it.score }

        val nextActive = mutableListOf<Hypothesis>()
        for (cand in candidates) {
            if (cand.tokens.last() == meta.eos) {
                finished.add(cand)
                finished.sortByDescending { it.score }
                if (finished.size > beams) {
                    finished.removeAt(finished.size - 1)
                }
            } else {
                if (nextActive.size < beams) {
                    nextActive.add(cand)
                }
            }
        }

        active = nextActive

        if (active.isEmpty()) {
            break
        }

        if (meta.earlyStopping && finished.size >= beams) {
            val worstFinished = finished.last().score
            val bestActive = active.first().score
            if (worstFinished >= bestActive) {
                break
            }
        }
    }

    val best = when {
        finished.isNotEmpty() -> finished.maxByOrNull { it.score }!!
        active.isNotEmpty() -> active.maxByOrNull { it.score }!!
        else -> return intArrayOf()
    }

    // Strip decoderStart prefix
    return if (best.tokens.size > 1) {
        best.tokens.copyOfRange(1, best.tokens.size)
    } else {
        intArrayOf()
    }
}

private fun greedySearch(
    scorer: StepScorer,
    meta: NmtMeta,
    maxNewTokens: Int
): IntArray {
    var tokens = intArrayOf(meta.decoderStart)

    for (step in 1..maxNewTokens) {
        val logitsArray = scorer.logProbs(listOf(tokens))
        val logProbs = logSoftmax(logitsArray[0])

        val nextToken = if (step == 1 && meta.forcedBos != null) {
            meta.forcedBos
        } else if (step == maxNewTokens && meta.forcedEosAtMax) {
            meta.eos
        } else {
            for (bw in meta.badWords) {
                if (bw.size == 1 && bw[0] in logProbs.indices) {
                    logProbs[bw[0]] = Float.NEGATIVE_INFINITY
                }
            }
            var bestId = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (i in logProbs.indices) {
                if (logProbs[i] > bestVal) {
                    bestVal = logProbs[i]
                    bestId = i
                }
            }
            bestId
        }

        tokens = tokens + nextToken
        if (nextToken == meta.eos) {
            break
        }
    }

    return if (tokens.size > 1) {
        tokens.copyOfRange(1, tokens.size)
    } else {
        intArrayOf()
    }
}

private fun logSoftmax(logits: FloatArray): FloatArray {
    var maxVal = Float.NEGATIVE_INFINITY
    for (v in logits) {
        if (v > maxVal) maxVal = v
    }
    if (maxVal == Float.NEGATIVE_INFINITY) {
        return logits.copyOf()
    }
    var sumExp = 0.0
    for (v in logits) {
        if (v != Float.NEGATIVE_INFINITY) {
            sumExp += exp((v - maxVal).toDouble())
        }
    }
    val logSumExp = (maxVal + ln(sumExp)).toFloat()
    val result = FloatArray(logits.size)
    for (i in logits.indices) {
        result[i] = if (logits[i] == Float.NEGATIVE_INFINITY) Float.NEGATIVE_INFINITY else (logits[i] - logSumExp)
    }
    return result
}

private fun findTopK(array: FloatArray, k: Int): IntArray {
    val count = minOf(k, array.size)
    // Priority queue of index by value ascending
    val pq = java.util.PriorityQueue<Int>(count + 1) { a, b ->
        array[a].compareTo(array[b])
    }
    for (i in array.indices) {
        if (array[i] == Float.NEGATIVE_INFINITY) continue
        pq.offer(i)
        if (pq.size > count) {
            pq.poll()
        }
    }
    val res = IntArray(pq.size)
    var idx = res.size - 1
    while (!pq.isEmpty()) {
        res[idx--] = pq.poll()!!
    }
    return res
}
