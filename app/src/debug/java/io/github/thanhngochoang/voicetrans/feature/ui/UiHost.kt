package io.github.thanhngochoang.voicetrans.feature.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.NonNull
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityOptionsCompat
import io.github.thanhngochoang.voicetrans.feature.session.CaptureConsent

/** Process-wide test state, so a recreated [UiHostActivity] sees the same content, registry and counters. */
object UiHost {
    var content: @Composable (UiHostActivity) -> Unit = {}
    var consentStarted = 0
    var consentDenied = 0
    val registry = TestRegistry()
    var consent: CaptureConsent? = null

    fun reset() { content = {}; consentStarted = 0; consentDenied = 0; consent = null; registry.launched.clear() }
}

class TestRegistry : ActivityResultRegistry() {
    val launched = mutableListOf<Int>()
    override fun <I, O> onLaunch(requestCode: Int, @NonNull contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += requestCode
    }
    fun deliverOk() = dispatchResult(launched.last(), Activity.RESULT_OK, Intent())
}

class UiHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registered unconditionally in onCreate so a result in flight is redelivered after recreate().
        UiHost.consent = CaptureConsent(this, onGranted = { _, _ -> UiHost.consentStarted++ }, onDenied = { UiHost.consentDenied++ }, registry = UiHost.registry)
        setContent { UiHost.content(this) }
    }
}
