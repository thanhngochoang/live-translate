package io.github.thanhngochoang.voicetrans.feature.ui.about

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.ui.ScreenFrame
import io.github.thanhngochoang.voicetrans.feature.ui.settings.Section
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SubPageHeader

/** Settings → Giới thiệu: versions and "Vì sao không có phụ đề?" (what can and cannot be captured). */
@Composable
fun AboutScreen(versionName: String, modelVersions: List<String>, onBack: () -> Unit) {
    ScreenFrame {
        SubPageHeader(stringResource(R.string.settings_about), onBack)
        Section(stringResource(R.string.app_name)) {
            Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (modelVersions.isNotEmpty()) {
                Text(stringResource(R.string.about_models, modelVersions.joinToString(", ")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Section(stringResource(R.string.why_no_subtitles)) {
            for (id in listOf(R.string.about_row_ok, R.string.about_row_drm, R.string.about_row_brave, R.string.about_row_calls)) {
                Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
