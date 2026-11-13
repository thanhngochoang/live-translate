package io.github.thanhngochoang.voicetrans.feature.session

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "vi")
class SessionNotificationsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val last = Utterance(1, UtteranceKind.SPEECH, "good morning", SourceLang.EN, "vi", "chào buổi sáng", 0, 1000)

    private fun running(overlay: OverlayNotifState = OverlayNotifState.NONE, music: Boolean = false, u: Utterance? = last) =
        SessionNotifications.running(ctx, u, "Tiếng Việt", overlay, music)

    private fun Notification.bigText() = extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    private fun Notification.titles() = actions.orEmpty().map { it.title.toString() }

    @Test fun running_hasStopAction_andBigText() {
        val n = running()
        assertEquals("Dừng", n.actions[0].title.toString())
        assertTrue(n.bigText().contains("good morning") && n.bigText().contains("chào buổi sáng"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Tiếng Việt"))
    }

    @Test fun running_isSilentOngoing() {
        val n = running()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        val channel = (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("session", n.channelId)
    }

    @Test fun running_overlayStates_actions() {
        assertEquals(listOf("Dừng", "Ẩn phụ đề"), running(OverlayNotifState.VISIBLE).titles())
        assertEquals(listOf("Dừng", "Hiện phụ đề"), running(OverlayNotifState.HIDDEN).titles())
        assertEquals(listOf("Dừng", "Mở khoá phụ đề"), running(OverlayNotifState.LOCKED).titles())
        assertEquals(listOf("Dừng"), running(OverlayNotifState.NONE).titles())
    }

    @Test fun running_music_showsNote() {
        assertTrue(running(music = true).bigText().contains("♪ Đang phát nhạc"))
    }

    @Test fun running_withoutUtterance_saysListening() {
        assertTrue(running(u = null).bigText().contains(ctx.getString(R.string.notif_waiting)))
    }

    @Test fun running_pendingIntents_areImmutable() {
        val n = running(OverlayNotifState.VISIBLE)
        assertNotNull(n.contentIntent)
        for (a in n.actions) assertNotNull(a.actionIntent)
    }

    @Test fun stopped_isDismissible_andOpensApp() {
        val n = SessionNotifications.stopped(ctx, StopReason.AUDIO_ERROR)
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertNotNull(n.contentIntent)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun stopped_idleTimeout_saysSilence() {
        val n = SessionNotifications.stopped(ctx, StopReason.IDLE_TIMEOUT, idleMinutes = 30)
        assertEquals(ctx.getString(R.string.stopped_idle, 30), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun stopped_projectionEnded_text() {
        val n = SessionNotifications.stopped(ctx, StopReason.PROJECTION_ENDED)
        assertEquals(ctx.getString(R.string.stopped_projection), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun stopped_hasNoActions() = assertNull(SessionNotifications.stopped(ctx, StopReason.PROJECTION_ENDED).actions)
}
