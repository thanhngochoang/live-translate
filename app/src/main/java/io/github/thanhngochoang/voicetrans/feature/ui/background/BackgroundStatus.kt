package io.github.thanhngochoang.voicetrans.feature.ui.background

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class BackgroundStatus(
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val overlayGranted: Boolean,
)

fun readBackgroundStatus(context: Context): BackgroundStatus = BackgroundStatus(
    notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    batteryUnrestricted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName),
    overlayGranted = Settings.canDrawOverlays(context),
)
