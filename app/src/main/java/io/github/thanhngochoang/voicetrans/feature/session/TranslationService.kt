package io.github.thanhngochoang.voicetrans.feature.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClass

/**
 * Foreground service (type mediaProjection) that owns the capture session. It must be started from a visible UI right
 * after the user's capture consent. `startForeground` is called before `getMediaProjection`, and the service is never
 * restarted by the system (START_NOT_STICKY).
 */
class TranslationService : LifecycleService() {

    private lateinit var deps: ServiceDeps
    private var projection: MediaProjection? = null
    internal val projectionForTest: MediaProjection? get() = projection

    override fun onCreate() {
        super.onCreate()
        deps = depsOverride ?: ServiceDeps.fromEntryPoint(applicationContext)
        SessionNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            SessionNotifications.ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            SessionNotifications.ACTION_TOGGLE_OVERLAY -> {
                deps.session.setOverlayHidden(!deps.session.overlayHidden.value)
                return START_NOT_STICKY
            }
            SessionNotifications.ACTION_UNLOCK_OVERLAY -> {
                lifecycleScope.launch { deps.settings.updateOverlay { it.copy(locked = false) } }
                return START_NOT_STICKY
            }
        }
        // A restart without consent data (or a missing RECORD_AUDIO grant) must not call startForeground:
        // on Android 14+ that throws SecurityException.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. startForeground first, 2. only then getMediaProjection.
        ServiceCompat.startForeground(
            this, SessionNotifications.ID_RUNNING,
            SessionNotifications.running(this, null, targetName("vi"), OverlayNotifState.NONE, music = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        val p = deps.projectionProvider.get(resultCode, data)
        projection = p
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    deps.session.stop(StopReason.PROJECTION_ENDED)
                    stopSelf()
                }
            },
            null,
        )
        deps.session.start(lifecycleScope, deps.audioSource(p))
        observeSession()
        TranslationTileService.requestRefresh(this)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun observeSession() {
        val session = deps.session
        val settings = deps.settings
        val nm = NotificationManagerCompat.from(this)
        // Running notification: last row, music flag, overlay state and target name, one update per change.
        lifecycleScope.launch {
            combine(
                session.utterances.map { it.lastOrNull() },
                session.audioClass.map { it == AudioClass.MUSIC || it == AudioClass.SINGING },
                session.overlayHidden,
                settings.overlay,
                settings.targetLanguage,
            ) { last, music, hidden, overlay, target ->
                SessionNotifications.running(this@TranslationService, last, targetName(target), overlayState(overlay, hidden), music)
            }.collect { notification ->
                if (session.state.value == SessionState.Running) nm.notify(SessionNotifications.ID_RUNNING, notification)
            }
        }
        lifecycleScope.launch {
            session.translatedAt.collect { /* the combine above already refreshes on every utterance change */ }
        }
        lifecycleScope.launch {
            session.state.collect { state ->
                if (state is SessionState.Stopped) {
                    if (state.reason != StopReason.USER) {
                        val minutes = settings.autoStopMinutes.first()
                        nm.notify(SessionNotifications.ID_STOPPED, SessionNotifications.stopped(this@TranslationService, state.reason, minutes))
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun overlayState(overlay: OverlaySettings, hidden: Boolean): OverlayNotifState = when {
        !overlay.enabled || !Settings.canDrawOverlays(this) -> OverlayNotifState.NONE
        overlay.locked -> OverlayNotifState.LOCKED
        hidden -> OverlayNotifState.HIDDEN
        else -> OverlayNotifState.VISIBLE
    }

    private fun targetName(code: String): String = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())

    override fun onDestroy() {
        if (deps.session.state.value == SessionState.Running) deps.session.stop()
        try {
            projection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "projection.stop failed", e)
        }
        projection = null
        TranslationTileService.requestRefresh(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        /** Tests set this to avoid Hilt. */
        internal var depsOverride: ServiceDeps? = null

        /** Takes an Activity so the service can only be started from a visible UI, right after the capture consent. */
        fun start(activity: Activity, resultCode: Int, data: Intent) {
            activity.startForegroundService(
                Intent(activity, TranslationService::class.java).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslationService::class.java))
        }
    }
}
