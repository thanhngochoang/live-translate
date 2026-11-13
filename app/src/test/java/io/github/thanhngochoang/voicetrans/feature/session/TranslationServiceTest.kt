package io.github.thanhngochoang.voicetrans.feature.session

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.util.ArrayMap
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackState
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEvent
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkRatioLanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationServiceTest {
    private val events = mutableListOf<String>()
    private lateinit var session: TranslationSession
    private lateinit var service: TranslationService

    private class MemStore : DataStore<Preferences> {
        private val s = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = s
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(s.value).also { s.value = it }
    }

    /** A MediaProjection without a system binder: only what the service touches (callbacks map) is initialised. */
    private fun fakeProjection(): MediaProjection {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val f = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        val p = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MediaProjection::class.java) as MediaProjection
        MediaProjection::class.java.getDeclaredField("mCallbacks").apply { isAccessible = true }.set(p, ArrayMap<Any, Any>())
        return p
    }

    private fun foregroundType(shadow: org.robolectric.shadows.ShadowService): Int =
        org.robolectric.shadows.ShadowService::class.java.getDeclaredField("foregroundServiceType").apply { isAccessible = true }.getInt(shadow)

    @Before fun setUp() {
        val asr = object : AsrEngine {
            override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = emptyFlow()
            override fun close() {}
        }
        val cls = object : AudioClassifier {
            override fun classify(window: FloatArray) = emptyMap<String, Float>()
            override fun close() {}
        }
        val tr = object : Translator {
            override val state = MutableStateFlow<EngineState>(EngineState.Ready)
            override suspend fun translate(text: String, from: SourceLang, mode: TranslateMode) = Result.success(text)
            override fun close() {}
        }
        session = TranslationSession(asr, cls, CjkRatioLanguageDetector(), tr, SettingsRepository(MemStore()), PlaybackProbe { PlaybackState(false, false) })
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        TranslationService.depsOverride = ServiceDeps(
            session = session,
            settings = SettingsRepository(MemStore()),
            projectionProvider = ProjectionProvider { _, _ ->
                val s = shadowOf(service)
                events += "getProjection(foregroundAlready=${s.lastForegroundNotification != null}, type=${foregroundType(s)})"
                fakeProjection()
            },
            audioSource = { object : AudioSource { override fun frames() = flow<FloatArray> { awaitCancellation() } } },
        )
        service = Robolectric.buildService(TranslationService::class.java).create().get()
    }

    @After fun tearDown() { TranslationService.depsOverride = null }

    private fun startIntent() = Intent(ApplicationProvider.getApplicationContext<Context>(), TranslationService::class.java)
        .putExtra(TranslationService.EXTRA_RESULT_CODE, -1)
        .putExtra(TranslationService.EXTRA_DATA, Intent())

    @Test fun startForegroundBeforeGetProjection() {
        service.onStartCommand(startIntent(), 0, 1)
        assertEquals(
            listOf("getProjection(foregroundAlready=true, type=${ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION})"),
            events,
        )
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertEquals(SessionState.Running, session.state.value)
    }

    @Test fun returnsNotSticky() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 1))
    }

    @Test fun projectionOnStop_stopsSessionWithReason() {
        service.onStartCommand(startIntent(), 0, 1)
        val callbacks = MediaProjection::class.java.getDeclaredField("mCallbacks").apply { isAccessible = true }
        // The service registered its callback on the projection it obtained from the provider.
        val projection = service.projectionForTest!!
        @Suppress("UNCHECKED_CAST")
        val map = callbacks.get(projection) as ArrayMap<MediaProjection.Callback, Any>
        assertEquals(1, map.size)
        map.keyAt(0).onStop()
        assertEquals(SessionState.Stopped(StopReason.PROJECTION_ENDED), session.state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun withoutRecordAudio_stopsSelfWithoutForeground() {
        shadowOf(ApplicationProvider.getApplicationContext<Context>() as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(events.isEmpty())
    }

    @Test fun restartWithoutConsent_stopsSelf() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stopAction_stopsSelf() {
        service.onStartCommand(startIntent(), 0, 1)
        service.onStartCommand(Intent(SessionNotifications.ACTION_STOP), 0, 2)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
