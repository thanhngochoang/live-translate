package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

class Seq2SeqEngine(
    val encoder: OrtSession,
    val decoder: OrtSession,
    val meta: NmtMeta,
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
) : Closeable {

    fun generate(input: IntArray, beams: Int, maxNewTokens: Int): IntArray {
        if (input.isEmpty()) return intArrayOf()

        val encSeqLen = input.size
        val hiddenDim = meta.numHeads * meta.headDim

        // Run encoder once
        val encInputIds = LongBuffer.wrap(LongArray(encSeqLen) { input[it].toLong() })
        val encMask = LongBuffer.wrap(LongArray(encSeqLen) { 1L })
        val encInputIdsTensor = OnnxTensor.createTensor(env, encInputIds, longArrayOf(1L, encSeqLen.toLong()))
        val encMaskTensor = OnnxTensor.createTensor(env, encMask, longArrayOf(1L, encSeqLen.toLong()))

        val encLastHiddenFloats: FloatArray
        try {
            val encResult = encoder.run(mapOf(
                "input_ids" to encInputIdsTensor,
                "attention_mask" to encMaskTensor
            ))
            try {
                val encLastHiddenTensor = encResult.get("last_hidden_state").get() as OnnxTensor
                val buf = encLastHiddenTensor.floatBuffer
                encLastHiddenFloats = FloatArray(buf.remaining())
                buf.get(encLastHiddenFloats)
            } finally {
                encResult.close()
            }
        } finally {
            encInputIdsTensor.close()
            encMaskTensor.close()
        }

        // State for decoder across steps
        var prevBeams = emptyList<IntArray>()
        val pastSelfKeys = Array<FloatArray?>(meta.numLayers) { null }
        val pastSelfVals = Array<FloatArray?>(meta.numLayers) { null }
        val pastCrossKeys = Array<FloatArray?>(meta.numLayers) { null }
        val pastCrossVals = Array<FloatArray?>(meta.numLayers) { null }
        var pastSelfSeqLen = 0
        var isFirstStep = true

        val scorer = StepScorer { beamTokens ->
            val batchSize = beamTokens.size
            val toClose = mutableListOf<AutoCloseable>()

            try {
                val inputs = mutableMapOf<String, OnnxTensor>()

                // 1. input_ids
                val inputIdsArr = LongArray(batchSize) { beamTokens[it].last().toLong() }
                val inputIdsTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(inputIdsArr),
                    longArrayOf(batchSize.toLong(), 1L)
                ).also { toClose.add(it) }
                inputs["input_ids"] = inputIdsTensor

                // 2. encoder_attention_mask
                val encMaskArr = LongArray(batchSize * encSeqLen) { 1L }
                val encMaskTensorDec = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(encMaskArr),
                    longArrayOf(batchSize.toLong(), encSeqLen.toLong())
                ).also { toClose.add(it) }
                inputs["encoder_attention_mask"] = encMaskTensorDec

                // 3. encoder_hidden_states
                val singleEncHiddenSize = encSeqLen * hiddenDim
                val encHiddenBatch = FloatArray(batchSize * singleEncHiddenSize)
                for (b in 0 until batchSize) {
                    System.arraycopy(encLastHiddenFloats, 0, encHiddenBatch, b * singleEncHiddenSize, singleEncHiddenSize)
                }
                val encHiddenTensor = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(encHiddenBatch),
                    longArrayOf(batchSize.toLong(), encSeqLen.toLong(), hiddenDim.toLong())
                ).also { toClose.add(it) }
                inputs["encoder_hidden_states"] = encHiddenTensor

                // 4. use_cache_branch
                val useCacheTensor = OnnxTensor.createTensor(
                    env,
                    booleanArrayOf(!isFirstStep)
                ).also { toClose.add(it) }
                inputs["use_cache_branch"] = useCacheTensor

                if (isFirstStep) {
                    // Dummy past_key_values for step 1
                    val dummySize = meta.numHeads * meta.headDim
                    val dummyFloats = FloatArray(dummySize)
                    for (pastName in meta.pastInputNames) {
                        val tensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(dummyFloats),
                            longArrayOf(1L, meta.numHeads.toLong(), 1L, meta.headDim.toLong())
                        ).also { toClose.add(it) }
                        inputs[pastName] = tensor
                    }
                } else {
                    // Determine beam_idx for each candidate
                    val beamIdx = IntArray(batchSize)
                    if (prevBeams.size == 1) {
                        beamIdx.fill(0)
                    } else {
                        for (b in 0 until batchSize) {
                            val prefix = beamTokens[b].copyOfRange(0, beamTokens[b].size - 1)
                            val parent = prevBeams.indexOfFirst { it.contentEquals(prefix) }
                            beamIdx[b] = if (parent >= 0) parent else 0
                        }
                    }

                    val strideSelf = meta.numHeads * pastSelfSeqLen * meta.headDim
                    val strideCross = meta.numHeads * encSeqLen * meta.headDim

                    for (l in 0 until meta.numLayers) {
                        // Decoder self key
                        val oldKey = pastSelfKeys[l]!!
                        val newKey = FloatArray(batchSize * strideSelf)
                        for (b in 0 until batchSize) {
                            val oldB = beamIdx[b]
                            System.arraycopy(oldKey, oldB * strideSelf, newKey, b * strideSelf, strideSelf)
                        }
                        val keyTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(newKey),
                            longArrayOf(batchSize.toLong(), meta.numHeads.toLong(), pastSelfSeqLen.toLong(), meta.headDim.toLong())
                        ).also { toClose.add(it) }
                        inputs["past_key_values.$l.decoder.key"] = keyTensor

                        // Decoder self val
                        val oldVal = pastSelfVals[l]!!
                        val newVal = FloatArray(batchSize * strideSelf)
                        for (b in 0 until batchSize) {
                            val oldB = beamIdx[b]
                            System.arraycopy(oldVal, oldB * strideSelf, newVal, b * strideSelf, strideSelf)
                        }
                        val valTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(newVal),
                            longArrayOf(batchSize.toLong(), meta.numHeads.toLong(), pastSelfSeqLen.toLong(), meta.headDim.toLong())
                        ).also { toClose.add(it) }
                        inputs["past_key_values.$l.decoder.value"] = valTensor

                        // Encoder cross key
                        val crossKey = pastCrossKeys[l]!!
                        val newCrossKey = FloatArray(batchSize * strideCross)
                        for (b in 0 until batchSize) {
                            System.arraycopy(crossKey, 0, newCrossKey, b * strideCross, strideCross)
                        }
                        val crossKeyTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(newCrossKey),
                            longArrayOf(batchSize.toLong(), meta.numHeads.toLong(), encSeqLen.toLong(), meta.headDim.toLong())
                        ).also { toClose.add(it) }
                        inputs["past_key_values.$l.encoder.key"] = crossKeyTensor

                        // Encoder cross val
                        val crossVal = pastCrossVals[l]!!
                        val newCrossVal = FloatArray(batchSize * strideCross)
                        for (b in 0 until batchSize) {
                            System.arraycopy(crossVal, 0, newCrossVal, b * strideCross, strideCross)
                        }
                        val crossValTensor = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(newCrossVal),
                            longArrayOf(batchSize.toLong(), meta.numHeads.toLong(), encSeqLen.toLong(), meta.headDim.toLong())
                        ).also { toClose.add(it) }
                        inputs["past_key_values.$l.encoder.value"] = crossValTensor
                    }
                }

                // Run decoder
                val decResult = decoder.run(inputs).also { toClose.add(it) }

                // Extract logits
                val logitsTensor = decResult.get("logits").get() as OnnxTensor
                val logitsBuf = logitsTensor.floatBuffer
                val vocabSize = logitsBuf.remaining() / batchSize
                val logitsArray = Array(batchSize) {
                    val row = FloatArray(vocabSize)
                    logitsBuf.get(row)
                    row
                }

                // Extract present states
                for (l in 0 until meta.numLayers) {
                    val presKeyBuf = (decResult.get("present.$l.decoder.key").get() as OnnxTensor).floatBuffer
                    val keyArr = FloatArray(presKeyBuf.remaining())
                    presKeyBuf.get(keyArr)
                    pastSelfKeys[l] = keyArr

                    val presValBuf = (decResult.get("present.$l.decoder.value").get() as OnnxTensor).floatBuffer
                    val valArr = FloatArray(presValBuf.remaining())
                    presValBuf.get(valArr)
                    pastSelfVals[l] = valArr

                    if (isFirstStep) {
                        val presCrossKeyBuf = (decResult.get("present.$l.encoder.key").get() as OnnxTensor).floatBuffer
                        val crossKeyArr = FloatArray(presCrossKeyBuf.remaining())
                        presCrossKeyBuf.get(crossKeyArr)
                        pastCrossKeys[l] = crossKeyArr

                        val presCrossValBuf = (decResult.get("present.$l.encoder.value").get() as OnnxTensor).floatBuffer
                        val crossValArr = FloatArray(presCrossValBuf.remaining())
                        presCrossValBuf.get(crossValArr)
                        pastCrossVals[l] = crossValArr
                    }
                }

                pastSelfSeqLen += 1
                prevBeams = beamTokens
                isFirstStep = false

                logitsArray
            } finally {
                toClose.forEach {
                    try {
                        it.close()
                    } catch (_: Throwable) {}
                }
            }
        }

        return beamSearch(scorer, meta, beams, maxNewTokens)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }
}
