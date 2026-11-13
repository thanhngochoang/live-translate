package io.github.thanhngochoang.voicetrans.feature.ui.background

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.ui.ScreenFrame

/** First-run screen "Cho phép chạy nền". Continue is always enabled: users can't be forced. */
@Composable
fun BackgroundSetupScreen(
    status: BackgroundStatus,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestOverlay: () -> Unit,
    onContinue: () -> Unit,
) {
    ScreenFrame {
        Text(stringResource(R.string.background_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.background_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupRow(
            title = stringResource(R.string.bg_notifications_title), description = stringResource(R.string.bg_notifications_desc),
            granted = status.notificationsGranted,
            statusText = stringResource(if (status.notificationsGranted) R.string.status_granted else R.string.status_not_granted),
            onAction = onRequestNotifications,
        )
        SetupRow(
            title = stringResource(R.string.bg_battery_title), description = stringResource(R.string.bg_battery_desc),
            hint = stringResource(R.string.bg_battery_hint), granted = status.batteryUnrestricted,
            statusText = stringResource(if (status.batteryUnrestricted) R.string.battery_unrestricted else R.string.battery_optimized),
            onAction = onRequestBattery,
        )
        SetupRow(
            title = stringResource(R.string.bg_overlay_title), description = stringResource(R.string.bg_overlay_desc),
            optional = true, granted = status.overlayGranted,
            statusText = stringResource(if (status.overlayGranted) R.string.status_granted else R.string.status_not_granted),
            onAction = onRequestOverlay,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    description: String,
    granted: Boolean,
    statusText: String,
    onAction: () -> Unit,
    hint: String? = null,
    optional: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (optional) Text(stringResource(R.string.optional), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp),
            )
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (!granted) OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_grant)) }
        }
    }
}
