package io.github.thanhngochoang.voicetrans.feature.ui.modelsetup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.AppUiState
import io.github.thanhngochoang.voicetrans.feature.ui.PackRow
import io.github.thanhngochoang.voicetrans.feature.ui.ScreenFrame

/** Gate before Live: the required packs (English, Chinese and the target) must be Ready. */
@Composable
fun ModelSetupScreen(state: AppUiState, onDownload: () -> Unit) {
    val busy = state.packs.any { it.state == ModelState.Downloading }
    val failed = state.packs.any { it.state is ModelState.Error }
    ScreenFrame {
        Text(stringResource(R.string.model_setup_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.model_setup_explain), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(stringResource(R.string.speech_recognition), ModelState.Ready, stringResource(R.string.pack_builtin))
            for (row in state.packs) PackRowView(row)
        }
        Button(
            onClick = onDownload,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (failed) R.string.retry else R.string.download_packs))
        }
        if (busy && state.wifiOnly) {
            Text(stringResource(R.string.wifi_helper), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PackRowView(row: PackRow) {
    val text = when (val s = row.state) {
        ModelState.Ready -> stringResource(R.string.pack_ready)
        ModelState.Downloading -> stringResource(R.string.pack_downloading)
        ModelState.NotDownloaded -> stringResource(R.string.pack_not_downloaded)
        is ModelState.Error -> stringResource(R.string.pack_error)
    }
    StatusRow(row.name, row.state, text)
}

/** Icon plus text, never color alone. */
@Composable
private fun StatusRow(name: String, state: ModelState, statusText: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            ModelState.Ready -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            ModelState.Downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            is ModelState.Error -> Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            ModelState.NotDownloaded -> Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = if (state is ModelState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

