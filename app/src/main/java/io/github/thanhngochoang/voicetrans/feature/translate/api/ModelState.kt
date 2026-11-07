package io.github.thanhngochoang.voicetrans.feature.translate.api

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data object Downloading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}
