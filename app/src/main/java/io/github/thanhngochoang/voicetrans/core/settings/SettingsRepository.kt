package io.github.thanhngochoang.voicetrans.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val subtitleSize = intPreferencesKey("subtitle_size_sp")
        val bgSetupDone = booleanPreferencesKey("background_setup_done")
        val autoStop = intPreferencesKey("auto_stop_minutes")
        val consentSeen = booleanPreferencesKey("consent_explainer_seen")
        val draftTranslation = booleanPreferencesKey("draft_translation")
        val ovEnabled = booleanPreferencesKey("overlay_enabled")
        val ovOpacity = intPreferencesKey("overlay_bg_opacity")
        val ovTextSize = intPreferencesKey("overlay_text_size")
        val ovMaxLines = intPreferencesKey("overlay_max_lines")
        val ovShowSource = booleanPreferencesKey("overlay_show_source")
        val ovPosition = stringPreferencesKey("overlay_position")
        val ovYPortrait = floatPreferencesKey("overlay_custom_y_portrait")
        val ovYLandscape = floatPreferencesKey("overlay_custom_y_landscape")
        val ovXPortrait = floatPreferencesKey("overlay_custom_x_portrait")
        val ovXLandscape = floatPreferencesKey("overlay_custom_x_landscape")
        val ovAutoHide = intPreferencesKey("overlay_auto_hide_sec")
        val ovLocked = booleanPreferencesKey("overlay_locked")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { p ->
        p[Keys.theme]?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: ThemeMode.DARK
    }
    suspend fun setThemeMode(mode: ThemeMode) { dataStore.edit { it[Keys.theme] = mode.name } }

    val subtitleSizeSp: Flow<Int> = dataStore.data.map { (it[Keys.subtitleSize] ?: 20).coerceIn(14, 32) }
    suspend fun setSubtitleSizeSp(sp: Int) { dataStore.edit { it[Keys.subtitleSize] = sp.coerceIn(14, 32) } }

    val backgroundSetupDone: Flow<Boolean> = dataStore.data.map { it[Keys.bgSetupDone] ?: false }
    suspend fun setBackgroundSetupDone(value: Boolean) { dataStore.edit { it[Keys.bgSetupDone] = value } }

    /** 0 = never, 10, 30 or 60. Other values are ignored. */
    val autoStopMinutes: Flow<Int> = dataStore.data.map { it[Keys.autoStop] ?: 30 }
    suspend fun setAutoStopMinutes(minutes: Int) {
        if (minutes in AUTO_STOP_ALLOWED) dataStore.edit { it[Keys.autoStop] = minutes }
    }

    val consentExplainerSeen: Flow<Boolean> = dataStore.data.map { it[Keys.consentSeen] ?: false }
    suspend fun setConsentExplainerSeen(value: Boolean) { dataStore.edit { it[Keys.consentSeen] = value } }

    val draftTranslation: Flow<Boolean> = dataStore.data.map { it[Keys.draftTranslation] ?: true }
    suspend fun setDraftTranslation(value: Boolean) { dataStore.edit { it[Keys.draftTranslation] = value } }

    val overlay: Flow<OverlaySettings> = dataStore.data.map { readOverlay(it) }

    /**
     * Applies [transform] to the current settings and stores the normalised result: opacity 0..100 in steps of 10,
     * text size 14..28, lines 1..3, auto-hide in {0, 5, 8, 15}, fractions 0..1. Changing `position` without also
     * changing a custom fraction in the same transform resets all custom x and y fractions to null (a preset was chosen).
     */
    suspend fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) {
        dataStore.edit { prefs ->
            val old = readOverlay(prefs)
            var new = transform(old)
            if (new.position != old.position &&
                new.customYPortrait == old.customYPortrait && new.customYLandscape == old.customYLandscape &&
                new.customXPortrait == old.customXPortrait && new.customXLandscape == old.customXLandscape
            ) {
                new = new.copy(customYPortrait = null, customYLandscape = null, customXPortrait = null, customXLandscape = null)
            }
            writeOverlay(prefs, normalize(new))
        }
    }

    private fun normalize(o: OverlaySettings) = o.copy(
        bgOpacityPercent = ((o.bgOpacityPercent / 10.0).roundToInt() * 10).coerceIn(0, 100),
        textSizeSp = o.textSizeSp.coerceIn(14, 28),
        maxLines = o.maxLines.coerceIn(1, 3),
        customYPortrait = o.customYPortrait?.coerceIn(0f, 1f),
        customYLandscape = o.customYLandscape?.coerceIn(0f, 1f),
        customXPortrait = o.customXPortrait?.coerceIn(0f, 1f),
        customXLandscape = o.customXLandscape?.coerceIn(0f, 1f),
        autoHideSec = AUTO_HIDE_ALLOWED.minBy { kotlin.math.abs(it - o.autoHideSec) },
    )

    private fun readOverlay(p: Preferences): OverlaySettings {
        val d = OverlaySettings()
        return normalize(
            OverlaySettings(
                enabled = p[Keys.ovEnabled] ?: d.enabled,
                bgOpacityPercent = p[Keys.ovOpacity] ?: d.bgOpacityPercent,
                textSizeSp = p[Keys.ovTextSize] ?: d.textSizeSp,
                maxLines = p[Keys.ovMaxLines] ?: d.maxLines,
                showSource = p[Keys.ovShowSource] ?: d.showSource,
                position = p[Keys.ovPosition]?.let { n -> OverlayPosition.entries.firstOrNull { it.name == n } } ?: d.position,
                customYPortrait = p[Keys.ovYPortrait],
                customYLandscape = p[Keys.ovYLandscape],
                customXPortrait = p[Keys.ovXPortrait],
                customXLandscape = p[Keys.ovXLandscape],
                autoHideSec = p[Keys.ovAutoHide] ?: d.autoHideSec,
                locked = p[Keys.ovLocked] ?: d.locked,
            ),
        )
    }

    private fun writeOverlay(p: androidx.datastore.preferences.core.MutablePreferences, o: OverlaySettings) {
        p[Keys.ovEnabled] = o.enabled
        p[Keys.ovOpacity] = o.bgOpacityPercent
        p[Keys.ovTextSize] = o.textSizeSp
        p[Keys.ovMaxLines] = o.maxLines
        p[Keys.ovShowSource] = o.showSource
        p[Keys.ovPosition] = o.position.name
        o.customYPortrait?.let { p[Keys.ovYPortrait] = it } ?: p.remove(Keys.ovYPortrait)
        o.customYLandscape?.let { p[Keys.ovYLandscape] = it } ?: p.remove(Keys.ovYLandscape)
        o.customXPortrait?.let { p[Keys.ovXPortrait] = it } ?: p.remove(Keys.ovXPortrait)
        o.customXLandscape?.let { p[Keys.ovXLandscape] = it } ?: p.remove(Keys.ovXLandscape)
        p[Keys.ovAutoHide] = o.autoHideSec
        p[Keys.ovLocked] = o.locked
    }

    private companion object {
        val AUTO_STOP_ALLOWED = setOf(0, 10, 30, 60)
        val AUTO_HIDE_ALLOWED = listOf(0, 5, 8, 15)
    }
}
