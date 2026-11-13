package io.github.thanhngochoang.voicetrans.feature.overlay

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Process
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.session.TranslationService
import io.github.thanhngochoang.voicetrans.feature.session.TranslationSession
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator

class AppExitDeps(
    val session: TranslationSession,
    val asr: AsrEngine,
    val classifier: AudioClassifier,
    val translator: Translator,
    val overlay: OverlayController,
    val stopService: (Context) -> Unit = TranslationService::stop,
) {
    companion object {
        fun fromEntryPoint(context: Context): AppExitDeps {
            val e = EntryPointAccessors.fromApplication(context.applicationContext, AppExitEntryPoint::class.java)
            return AppExitDeps(e.session(), e.asr(), e.classifier(), e.translator(), e.overlay())
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppExitEntryPoint {
    fun session(): TranslationSession
    fun asr(): AsrEngine
    fun classifier(): AudioClassifier
    fun translator(): Translator
    fun overlay(): OverlayController
}

/**
 * "Thoát app": stops the session and the foreground service (which stops the projection), removes the overlay,
 * cancels notifications, closes the app's tasks, releases the engines, then ends the process so the next launch
 * rebuilds every singleton fresh.
 */
class AppExit(
    private val terminate: () -> Unit = { Process.killProcess(Process.myPid()) },
    private val deps: AppExitDeps? = null,
) {
    fun exit(context: Context) {
        val d = deps ?: AppExitDeps.fromEntryPoint(context)
        d.session.stop()
        d.stopService(context)
        d.overlay.shutdown()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { it.finishAndRemoveTask() }
        closeQuietly("asr") { d.asr.close() }
        closeQuietly("classifier") { d.classifier.close() }
        closeQuietly("translator") { d.translator.close() }
        terminate()
    }

    private inline fun closeQuietly(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w("AppExit", "closing $what failed", e) }
    }
}
