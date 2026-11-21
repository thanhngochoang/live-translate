package io.github.thanhngochoang.voicetrans.core.model

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM unit tests cannot catch regex syntax that only Android's ICU engine rejects (v0.2.0-8 crashed at start
 * for that reason), so parse the catalog the app ships on a real device too.
 */
class ModelCatalogDeviceTest {
    @Test fun bundledCatalogParsesOnDevice() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val catalog = ModelCatalog.parse(assets.open(ModelModule.CATALOG_ASSET).bufferedReader().use { it.readText() })
        assertTrue(catalog.files.size >= 17)
        assertTrue(catalog.files.any { it.dest == "nmt/nllb-600m/encoder_model.onnx" })
    }
}
