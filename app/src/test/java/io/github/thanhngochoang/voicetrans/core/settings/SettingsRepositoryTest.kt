package io.github.thanhngochoang.voicetrans.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var repo: SettingsRepository

    private fun store(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File(tmp.root, "settings.preferences_pb")
        repo = SettingsRepository(store())
    }

    @After fun tearDown() = scope.cancel()

    @Test fun defaults() = runBlocking {
        assertEquals("vi", repo.targetLanguage.first())
        assertEquals(ThemeMode.DARK, repo.themeMode.first())
        assertEquals(20, repo.subtitleSizeSp.first())
        assertTrue(repo.wifiOnlyDownload.first())
        assertFalse(repo.backgroundSetupDone.first())
        assertFalse(repo.consentExplainerSeen.first())
    }

    @Test fun subtitleSize_clamped() = runBlocking {
        repo.setSubtitleSizeSp(40); assertEquals(32, repo.subtitleSizeSp.first())
        repo.setSubtitleSizeSp(5); assertEquals(14, repo.subtitleSizeSp.first())
    }

    @Test fun autoStop_default30_andRejectsOtherValues() = runBlocking {
        assertEquals(30, repo.autoStopMinutes.first())
        repo.setAutoStopMinutes(45); assertEquals(30, repo.autoStopMinutes.first())
        repo.setAutoStopMinutes(0); assertEquals(0, repo.autoStopMinutes.first())
        repo.setAutoStopMinutes(60); assertEquals(60, repo.autoStopMinutes.first())
        repo.setAutoStopMinutes(10); assertEquals(10, repo.autoStopMinutes.first())
    }

    @Test fun simpleSetters_roundTrip() = runBlocking {
        repo.setThemeMode(ThemeMode.LIGHT); assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
        repo.setWifiOnlyDownload(false); assertFalse(repo.wifiOnlyDownload.first())
        repo.setBackgroundSetupDone(true); assertTrue(repo.backgroundSetupDone.first())
        repo.setConsentExplainerSeen(true); assertTrue(repo.consentExplainerSeen.first())
    }

    @Test fun overlay_defaults() = runBlocking {
        val o = repo.overlay.first()
        assertEquals(OverlaySettings(), o)
        assertTrue(o.enabled); assertEquals(30, o.bgOpacityPercent); assertEquals(18, o.textSizeSp)
        assertEquals(2, o.maxLines); assertFalse(o.showSource); assertEquals(OverlayPosition.BOTTOM, o.position)
        assertNull(o.customYPortrait); assertNull(o.customYLandscape)
        assertEquals(8, o.autoHideSec); assertFalse(o.locked)
    }

    @Test fun overlay_opacityClampedAndStepped() = runBlocking {
        repo.updateOverlay { it.copy(bgOpacityPercent = 73) }; assertEquals(70, repo.overlay.first().bgOpacityPercent)
        repo.updateOverlay { it.copy(bgOpacityPercent = 140) }; assertEquals(100, repo.overlay.first().bgOpacityPercent)
        repo.updateOverlay { it.copy(bgOpacityPercent = -5) }; assertEquals(0, repo.overlay.first().bgOpacityPercent)
    }

    @Test fun overlay_otherFieldsClamped() = runBlocking {
        repo.updateOverlay { it.copy(textSizeSp = 40, maxLines = 9, autoHideSec = 7) }
        val o = repo.overlay.first()
        assertEquals(28, o.textSizeSp); assertEquals(3, o.maxLines)
        assertTrue(o.autoHideSec in setOf(0, 5, 8, 15))
    }

    @Test fun overlay_presetResetsBothCustomPositions() = runBlocking {
        repo.updateOverlay { it.copy(customYPortrait = 0.2f, customYLandscape = 0.7f) }
        assertEquals(0.2f, repo.overlay.first().customYPortrait)
        repo.updateOverlay { it.copy(position = OverlayPosition.TOP) }
        val o = repo.overlay.first()
        assertEquals(OverlayPosition.TOP, o.position)
        assertNull(o.customYPortrait); assertNull(o.customYLandscape)
    }

    @Test fun overlay_customYClamped() = runBlocking {
        repo.updateOverlay { it.copy(customYLandscape = 1.4f, customYPortrait = -0.3f) }
        assertEquals(1.0f, repo.overlay.first().customYLandscape)
        assertEquals(0.0f, repo.overlay.first().customYPortrait)
    }

    @Test fun overlay_persistsAcrossRepositories() = runBlocking {
        repo.updateOverlay { it.copy(showSource = true, locked = true, customYPortrait = 0.4f) }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val o = SettingsRepository(store()).overlay.first()
        assertTrue(o.showSource); assertTrue(o.locked); assertEquals(0.4f, o.customYPortrait)
    }

    @Test fun targetLanguage_persists() = runBlocking {
        repo.setTargetLanguage("ja")
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals("ja", SettingsRepository(store()).targetLanguage.first())
    }

    @Test fun overlay_xFractionsDefaultNull_andAreClamped() = runBlocking {
        assertNull(repo.overlay.first().customXPortrait)
        repo.updateOverlay { it.copy(customXPortrait = 1.6f, customXLandscape = -0.2f) }
        assertEquals(1.0f, repo.overlay.first().customXPortrait)
        assertEquals(0.0f, repo.overlay.first().customXLandscape)
    }

    @Test fun overlay_presetClearsXAndYForBothOrientations() = runBlocking {
        repo.updateOverlay { it.copy(customXPortrait = 0.2f, customYPortrait = 0.3f, customXLandscape = 0.7f, customYLandscape = 0.6f) }
        repo.updateOverlay { it.copy(position = OverlayPosition.CENTER) }
        val o = repo.overlay.first()
        assertNull(o.customXPortrait); assertNull(o.customYPortrait); assertNull(o.customXLandscape); assertNull(o.customYLandscape)
    }

    @Test fun overlay_orientationsAreIndependent() = runBlocking {
        repo.updateOverlay { it.copy(customXLandscape = 0.9f, customYLandscape = 0.1f) }
        val o = repo.overlay.first()
        assertNull(o.customXPortrait); assertNull(o.customYPortrait)
        assertEquals(0.9f, o.customXLandscape); assertEquals(0.1f, o.customYLandscape)
    }

    @Test fun overlay_xPersistsAcrossRepositories() = runBlocking {
        repo.updateOverlay { it.copy(customXPortrait = 0.4f) }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        assertEquals(0.4f, SettingsRepository(store()).overlay.first().customXPortrait)
    }

    @Test fun overlay_existingUsersWithoutXKeyGetNull() = runBlocking {
        repo.updateOverlay { it.copy(customYPortrait = 0.5f) } // only y stored, as before this feature
        assertNull(repo.overlay.first().customXPortrait)
    }
}
