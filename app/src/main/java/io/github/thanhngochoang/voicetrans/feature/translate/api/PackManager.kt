package io.github.thanhngochoang.voicetrans.feature.translate.api

import kotlinx.coroutines.flow.StateFlow

/** Language-pack management seen by the UI (implemented by the ML Kit translator, faked in tests). */
interface PackManager {
    val packs: StateFlow<Map<String, ModelState>>
    suspend fun refreshPacks()
    suspend fun download(lang: String, wifiOnly: Boolean): Result<Unit>
    suspend fun delete(lang: String): Result<Unit>
    fun requiredPacks(target: String): Set<String>
    fun isReadyFor(target: String): Boolean
}
