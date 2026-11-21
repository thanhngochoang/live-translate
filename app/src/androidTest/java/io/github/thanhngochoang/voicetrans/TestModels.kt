package io.github.thanhngochoang.voicetrans

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.core.model.ModelModule
import io.github.thanhngochoang.voicetrans.core.model.ModelState
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The models are downloaded files, and `connectedDebugAndroidTest` uninstalls the app (and its files) when it ends.
 * Tests that need the real models call [dir], which downloads whatever is missing in the same run (needs network).
 */
object TestModels {
    fun dir(): File {
        val store = ModelModule.modelStore(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { store.download() }
        check(store.state.value is ModelState.Ready) { "Models could not be downloaded: ${store.state.value}" }
        return store.root
    }
}
