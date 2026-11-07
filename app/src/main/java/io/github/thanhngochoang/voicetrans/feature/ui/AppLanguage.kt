package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The UI language. Stored by the system (per-app locales), not in DataStore. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    VI("vi"),
    EN("en"),
}

fun AppLanguage.apply() {
    AppCompatDelegate.setApplicationLocales(if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
}

fun currentAppLanguage(): AppLanguage {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return AppLanguage.entries.firstOrNull { it.tag != null && tags.startsWith(it.tag) } ?: AppLanguage.SYSTEM
}
