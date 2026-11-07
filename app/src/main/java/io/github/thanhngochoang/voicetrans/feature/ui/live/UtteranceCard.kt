package io.github.thanhngochoang.voicetrans.feature.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang

fun formatClock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)

@Composable
fun UtteranceCard(u: Utterance, subtitleSizeSp: Int, compact: Boolean = false) {
    if (u.kind == UtteranceKind.MUSIC) {
        Text(
            stringResource(R.string.music_row, formatClock(u.endMs - u.timestampMs)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("music_row").padding(horizontal = 4.dp, vertical = 4.dp),
        )
        return
    }
    val badge = if (u.sourceLang == SourceLang.EN) "EN" else "ZH"
    val sourceName = stringResource(if (u.sourceLang == SourceLang.EN) R.string.source_en else R.string.source_zh)
    val sameLanguage = u.translation != null && u.translation == u.source
    val description = stringResource(R.string.card_desc, sourceName, u.source, u.translation ?: "")
    Column(
        Modifier.testTag("utterance_card").fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(if (compact) 8.dp else 16.dp).semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(formatClock(u.timestampMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compact) Text(u.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!compact) Text(u.source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            u.translationFailed -> Text(stringResource(R.string.translation_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            sameLanguage -> Unit // source equals target: no second line
            u.translation == null -> Text(stringResource(R.string.translating_placeholder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(
                u.translation, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = subtitleSizeSp.sp, lineHeight = (subtitleSizeSp * 1.4f).sp),
            )
        }
    }
}
