package io.github.thanhngochoang.voicetrans.feature.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsParityTest {
    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val vi get() = strings("src/main/res/values/strings.xml")
    private val en get() = strings("src/main/res/values-en/strings.xml")

    @Test fun keySetsAreEqual() {
        assertEquals("only in vi: ${vi.keys - en.keys}", emptySet<String>(), vi.keys - en.keys)
        assertEquals("only in en: ${en.keys - vi.keys}", emptySet<String>(), en.keys - vi.keys)
    }

    @Test fun noValueIsEmpty() {
        for ((k, v) in vi) assertTrue("vi $k is empty", v.isNotBlank())
        for ((k, v) in en) assertTrue("en $k is empty", v.isNotBlank())
    }

    @Test fun taskEightKeysExist() {
        for (k in listOf("download_packs", "start_listening", "background_title", "battery_optimized", "optional", "open_settings", "action_continue")) {
            assertTrue("missing $k in vi", k in vi)
            assertTrue("missing $k in en", k in en)
        }
    }

    @Test fun englishAndVietnameseDifferForUserFacingKeys() {
        for (k in listOf("download_packs", "start_listening", "background_title", "action_continue")) {
            assertTrue("$k identical in both languages", vi[k] != en[k])
        }
    }
}
