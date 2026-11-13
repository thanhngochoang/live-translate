package io.github.thanhngochoang.voicetrans.feature.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import io.github.thanhngochoang.voicetrans.MainActivity
import io.github.thanhngochoang.voicetrans.R

enum class OverlayNotifState { NONE, VISIBLE, HIDDEN, LOCKED }

object SessionNotifications {
    const val CHANNEL_ID = "session"
    const val ID_RUNNING = 1
    const val ID_STOPPED = 2
    const val ACTION_STOP = "io.github.thanhngochoang.voicetrans.action.STOP"
    const val ACTION_TOGGLE_OVERLAY = "io.github.thanhngochoang.voicetrans.action.TOGGLE_OVERLAY"
    const val ACTION_UNLOCK_OVERLAY = "io.github.thanhngochoang.voicetrans.action.UNLOCK_OVERLAY"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.notif_channel_desc) },
            )
        }
    }

    /** Ongoing, silent notification. At most two actions: Stop plus one overlay action. */
    fun running(context: Context, last: Utterance?, targetName: String, overlay: OverlayNotifState, music: Boolean): Notification {
        ensureChannel(context)
        val body = SpannableStringBuilder()
        if (last != null && last.kind == UtteranceKind.SPEECH) {
            val start = body.length
            body.append(last.source)
            body.setSpan(ForegroundColorSpan(Color.GRAY), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) // muted source line
            val clean = last.translation?.let { io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.purgeCjk(it) }
            if (!clean.isNullOrBlank() && clean.any { it.isLetter() } && !io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.hasCjk(clean)) {
                body.append('\n').append(clean)
            }
        }
        if (music) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(context.getString(R.string.notif_music))
        }
        if (body.isEmpty()) body.append(context.getString(R.string.notif_waiting))
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_subtitles)
            .setContentTitle(context.getString(R.string.notif_title_running, targetName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.notif_action_stop), serviceAction(context, ACTION_STOP, 1))
        when (overlay) {
            OverlayNotifState.VISIBLE -> builder.addAction(0, context.getString(R.string.notif_action_hide_overlay), serviceAction(context, ACTION_TOGGLE_OVERLAY, 2))
            OverlayNotifState.HIDDEN -> builder.addAction(0, context.getString(R.string.notif_action_show_overlay), serviceAction(context, ACTION_TOGGLE_OVERLAY, 2))
            OverlayNotifState.LOCKED -> builder.addAction(0, context.getString(R.string.notif_action_unlock_overlay), serviceAction(context, ACTION_UNLOCK_OVERLAY, 3))
            OverlayNotifState.NONE -> Unit
        }
        return builder.build()
    }

    /** Dismissible notification after a system stop. Tapping it opens Live, where the user taps Start (new consent). */
    fun stopped(context: Context, reason: StopReason, idleMinutes: Int = 30): Notification {
        ensureChannel(context)
        val text = when (reason) {
            StopReason.IDLE_TIMEOUT -> context.getString(R.string.stopped_idle, idleMinutes)
            StopReason.PROJECTION_ENDED -> context.getString(R.string.stopped_projection)
            StopReason.AUDIO_ERROR -> context.getString(R.string.stopped_audio_error)
            StopReason.USER -> context.getString(R.string.stopped_generic)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_subtitles)
            .setContentTitle(context.getString(R.string.stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        context, code, Intent(context, TranslationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
