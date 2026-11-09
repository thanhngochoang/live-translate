package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SentencePieceModel(
    val pieces: List<String>,
    val scores: FloatArray,
    val pieceToIndex: Map<String, Int>
) {
    companion object {
        fun load(stream: InputStream): SentencePieceModel {
            val bytes = stream.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val pieces = ArrayList<String>(32000)
            val scoresList = ArrayList<Float>(32000)

            while (buf.hasRemaining()) {
                val tag = readVarint(buf)
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 7L).toInt()

                if (fieldNum == 1 && wireType == 2) {
                    // pieces: repeated SentencePiece
                    val len = readVarint(buf).toInt()
                    val pieceEnd = buf.position() + len
                    var pieceStr = ""
                    var pieceScore = 0.0f

                    while (buf.position() < pieceEnd) {
                        val subTag = readVarint(buf)
                        val subField = (subTag ushr 3).toInt()
                        val subWire = (subTag and 7L).toInt()

                        when (subField) {
                            1 -> {
                                val sLen = readVarint(buf).toInt()
                                val strBytes = ByteArray(sLen)
                                buf.get(strBytes)
                                pieceStr = String(strBytes, Charsets.UTF_8)
                            }
                            2 -> {
                                pieceScore = buf.float
                            }
                            else -> skipField(buf, subWire)
                        }
                    }
                    pieces.add(pieceStr)
                    scoresList.add(pieceScore)
                } else {
                    skipField(buf, wireType)
                }
            }

            val scores = FloatArray(scoresList.size) { scoresList[it] }
            val map = HashMap<String, Int>(pieces.size * 4 / 3 + 1)
            for (i in pieces.indices) {
                map[pieces[i]] = i
            }
            return SentencePieceModel(pieces, scores, map)
        }

        private fun readVarint(buf: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            while (buf.hasRemaining()) {
                val b = buf.get().toLong()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80L) == 0L) break
                shift += 7
            }
            return result
        }

        private fun skipField(buf: ByteBuffer, wireType: Int) {
            when (wireType) {
                0 -> readVarint(buf)
                1 -> buf.position(buf.position() + 8)
                2 -> {
                    val len = readVarint(buf).toInt()
                    buf.position(buf.position() + len)
                }
                5 -> buf.position(buf.position() + 4)
                else -> error("Unsupported wire type: $wireType")
            }
        }
    }
}
