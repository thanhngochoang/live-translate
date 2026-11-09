package io.github.thanhngochoang.voicetrans.feature.translate.nmt

data class NmtMeta(
    val model: String,
    val eos: Int,
    val pad: Int,
    val unk: Int,
    val decoderStart: Int,
    val forcedBos: Int?,
    val srcLangToken: Int?,
    val badWords: List<List<Int>>,
    val forcedEosAtMax: Boolean,
    val lengthPenalty: Float,
    val earlyStopping: Boolean,
    val numLayers: Int,
    val numHeads: Int,
    val headDim: Int,
    val pastInputNames: List<String>
) {
    companion object {
        fun parse(json: String): NmtMeta {
            val stringMap = mutableMapOf<String, String>()
            val intMap = mutableMapOf<String, Int>()
            val boolMap = mutableMapOf<String, Boolean>()
            val floatMap = mutableMapOf<String, Float>()
            val badWords = mutableListOf<List<Int>>()
            val pastInputNames = mutableListOf<String>()

            // Extract string values: "key": "value"
            Regex(""""(\w+)"\s*:\s*"([^"]*)"""").findAll(json).forEach {
                stringMap[it.groupValues[1]] = it.groupValues[2]
            }
            // Extract int/null values: "key": 123 or "key": null
            Regex(""""(\w+)"\s*:\s*(-?\d+|null)""").findAll(json).forEach {
                val v = it.groupValues[2]
                if (v != "null") {
                    intMap[it.groupValues[1]] = v.toInt()
                }
            }
            // Extract boolean values: "key": true / false
            Regex(""""(\w+)"\s*:\s*(true|false)""").findAll(json).forEach {
                boolMap[it.groupValues[1]] = it.groupValues[2].toBoolean()
            }
            // Extract float values: "key": 1.0
            Regex(""""(\w+)"\s*:\s*(-?\d+\.\d+)""").findAll(json).forEach {
                floatMap[it.groupValues[1]] = it.groupValues[2].toFloat()
            }
            // Extract pastInputNames: ["...", "..."]
            val pinMatch = Regex(""""pastInputNames"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(json)
            if (pinMatch != null) {
                Regex(""""([^"]+)"""").findAll(pinMatch.groupValues[1]).forEach {
                    pastInputNames.add(it.groupValues[1])
                }
            }
            // Extract badWords: [[65000]]
            val bwMatch = Regex(""""badWords"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(json)
            if (bwMatch != null) {
                Regex("""\[([\d,\s]*)\]""").findAll(bwMatch.groupValues[1]).forEach { sub ->
                    val ids = Regex("""\d+""").findAll(sub.groupValues[1]).map { it.value.toInt() }.toList()
                    if (ids.isNotEmpty()) badWords.add(ids)
                }
            }

            return NmtMeta(
                model = stringMap["model"] ?: error("Missing model in meta.json"),
                eos = intMap["eos"] ?: 0,
                pad = intMap["pad"] ?: 1,
                unk = intMap["unk"] ?: 3,
                decoderStart = intMap["decoderStart"] ?: 2,
                forcedBos = intMap["forcedBos"],
                srcLangToken = intMap["srcLangToken"],
                badWords = badWords,
                forcedEosAtMax = boolMap["forcedEosAtMax"] ?: false,
                lengthPenalty = floatMap["lengthPenalty"] ?: 1.0f,
                earlyStopping = boolMap["earlyStopping"] ?: true,
                numLayers = intMap["numLayers"] ?: 6,
                numHeads = intMap["numHeads"] ?: 8,
                headDim = intMap["headDim"] ?: 64,
                pastInputNames = pastInputNames
            )
        }
    }
}
