package io.github.thanhngochoang.voicetrans.feature.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.model.ModelState

/**
 * Shown while the model files are not on the device. Nothing is downloaded until the user asks: the models are
 * large, and the user may be on mobile data.
 */
@Composable
fun ModelDownloadScreen(state: ModelState, onDownload: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
    ScreenFrame {
        Text(stringResource(R.string.model_download_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.model_download_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is ModelState.Checking, is ModelState.Ready -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                is ModelState.Missing -> {
                    Text(
                        stringResource(R.string.model_download_size, size(state.bytes)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    )
                    DownloadButton(R.string.model_download_action, onDownload)
                }
                is ModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.model_download_progress, size(state.doneBytes), size(state.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is ModelState.Failed -> {
                    Text(
                        when (state.reason) {
                            ModelState.Reason.NETWORK -> stringResource(R.string.model_download_error_network)
                            ModelState.Reason.STORAGE -> stringResource(R.string.model_download_error_storage, size(state.bytes))
                            ModelState.Reason.CORRUPT -> stringResource(R.string.model_download_error_corrupt)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    )
                    DownloadButton(R.string.model_download_retry, onDownload)
                }
            }
        }
    }
}

@Composable
private fun DownloadButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(label)) }
}
