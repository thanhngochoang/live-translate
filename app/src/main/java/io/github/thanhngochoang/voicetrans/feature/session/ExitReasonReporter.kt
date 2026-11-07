package io.github.thanhngochoang.voicetrans.feature.session

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import io.github.thanhngochoang.voicetrans.R

/** JVM-friendly wrapper of [ApplicationExitInfo]. */
data class ExitInfo(val reason: Int, val description: String?)

object ExitReasonReporter {
    /** String-resource id explaining the most recent abnormal exit in [infos] (newest first), or null. */
    @StringRes
    fun lastAbnormalExit(infos: List<ExitInfo>): Int? = infos.firstNotNullOfOrNull { explain(it) }

    @StringRes
    private fun explain(info: ExitInfo): Int? = when (info.reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> R.string.exit_memory
        ApplicationExitInfo.REASON_OTHER -> if (info.description?.contains("MemoryLimiter") == true) R.string.exit_memory else null
        ApplicationExitInfo.REASON_USER_REQUESTED -> R.string.exit_user
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> R.string.exit_resources
        else -> null
    }

    /** Reads the system's exit records (API 30+). Returns null on older versions or when nothing abnormal happened. */
    @StringRes
    fun fromSystem(context: Context): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).map { ExitInfo(it.reason, it.description) }
        return lastAbnormalExit(infos)
    }
}
