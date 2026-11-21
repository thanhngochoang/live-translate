package io.github.thanhngochoang.voicetrans.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    private val sha = "a".repeat(64)

    private fun entry(dest: String, url: String = "https://example.test/$dest", sha256: String = sha, size: Long = 3) =
        """{ "id": "x", "url": "$url", "dest": "$dest", "sha256": "$sha256", "size": $size }"""

    private fun json(files: String, nmt: String = "") =
        """{ "version": 2, "release": "r1", "archives": [], "files": [$files], "nmt": [$nmt] }"""

    @Test fun readsSpeechAndTranslationGroups() {
        val catalog = ModelCatalog.parse(json(entry("vad/a.onnx", size = 5), entry("nmt/m/b.onnx", size = 7)))
        assertEquals("r1", catalog.release)
        assertEquals(listOf("vad/a.onnx", "nmt/m/b.onnx"), catalog.files.map { it.dest })
        assertEquals(12L, catalog.totalBytes)
        assertEquals("https://example.test/vad/a.onnx", catalog.files[0].url)
    }

    @Test fun rejectsPathsThatLeaveTheModelsDirectory() {
        for (dest in listOf("../evil.onnx", "/abs/evil.onnx", "a/../../evil.onnx", "a//b", "")) {
            assertThrows("dest \"$dest\"", IllegalArgumentException::class.java) { ModelCatalog.parse(json(entry(dest))) }
        }
    }

    @Test fun rejectsPlainHttpAndBadChecksums() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(json(entry("a.onnx", url = "http://example.test/a"))) }
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(json(entry("a.onnx", sha256 = "abc"))) }
    }

    @Test fun rejectsDuplicateDestinations() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.parse(json(entry("a.onnx"), entry("a.onnx"))) }
    }

    /** The real catalog must parse and cover every file the engines open. */
    @Test fun bundledCatalogListsEveryFileTheEnginesOpen() {
        val catalog = ModelCatalog.parse(File("src/main/assets/models/models.json").readText())
        val dests = catalog.files.map { it.dest }.toSet()
        val needed = listOf(
            "asr/encoder-epoch-99-avg-1.int8.onnx", "asr/decoder-epoch-99-avg-1.onnx", "asr/joiner-epoch-99-avg-1.int8.onnx",
            "asr/tokens.txt", "vad/silero_vad.onnx", "tagging/model.int8.onnx", "tagging/class_labels_indices.csv",
            "nmt/nllb-600m/meta.json", "nmt/nllb-600m/encoder_model.onnx", "nmt/nllb-600m/decoder_model_merged.onnx",
            "nmt/nllb-600m/sentencepiece.bpe.model",
            "nmt/opus-zh-vi/meta.json", "nmt/opus-zh-vi/encoder_model.onnx", "nmt/opus-zh-vi/decoder_model_merged.onnx",
            "nmt/opus-zh-vi/source.spm", "nmt/opus-zh-vi/target.spm", "nmt/opus-zh-vi/vocab.json",
        )
        assertEquals("missing from models.json", emptyList<String>(), needed.filterNot { it in dests })
        assertTrue(catalog.release.isNotBlank())
        assertTrue(catalog.files.all { it.size > 0 })
    }
}
