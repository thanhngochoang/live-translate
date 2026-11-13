package io.github.thanhngochoang.voicetrans.feature.overlay

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.MainActivity
import io.github.thanhngochoang.voicetrans.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExitTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exit_stopsEverything_thenTerminates() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set io.github.thanhngochoang.voicetrans SYSTEM_ALERT_WINDOW allow").close()
        Thread.sleep(300)
        val rig = SessionRig()
        rig.startRunning()
        waitFor(what = "session running") { rig.session.state.value.toString() == "Running" }
        val controller = OverlayController(ctx, kotlinx.coroutines.flow.flowOf(io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings()), { rig.session })
        onMain { controller.setSetupMode(true) }
        waitFor(what = "overlay attached") { controller.attachedView != null }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("t", "t", NotificationManager.IMPORTANCE_LOW))
        nm.notify(77, NotificationCompat.Builder(ctx, "t").setSmallIcon(R.drawable.ic_stat_subtitles).setContentTitle("x").build())

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var terminated = false
        var serviceStops = 0
        val exit = AppExit(
            terminate = { terminated = true },
            deps = AppExitDeps(rig.session, rig.asr, rig.classifier, rig.translator, controller, stopService = { serviceStops++ }),
        )
        exit.exit(ctx)

        assertEquals("Stopped", rig.session.state.value.javaClass.simpleName)
        assertEquals(1, serviceStops)
        waitFor(what = "overlay removed") { controller.attachedView == null }
        waitFor(what = "notifications cancelled") { nm.activeNotifications.isEmpty() }
        waitFor(what = "asr released") { rig.asr.isReleased }
        assertTrue(rig.classifier.isClosed && rig.translator.isClosed)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        waitFor(what = "app tasks empty") { am.appTasks.isEmpty() }
        assertTrue(terminated)
        runCatching { scenario.close() }
        rig.close()
    }
}
