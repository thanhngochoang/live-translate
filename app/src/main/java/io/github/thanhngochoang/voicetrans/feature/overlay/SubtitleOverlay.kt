package io.github.thanhngochoang.voicetrans.feature.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** SETUP: solid 85% box with outline and handle. PREVIEW: same chrome but the real opacity (while the opacity slider is dragged). */
enum class OverlayMode { TRANSLATING, SETUP, PREVIEW }

/** Exposed for tests: the opacity of the box background. */
val OverlayBackgroundAlphaKey = SemanticsPropertyKey<Float>("OverlayBackgroundAlpha")
var SemanticsPropertyReceiver.overlayBackgroundAlpha by OverlayBackgroundAlphaKey

private val PrimaryOutline = Color(0xFF7CB0FF)
private val SourceColor = Color(0xFFC8D0E0)
private val TextShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset.Zero, 4f)

/**
 * The caption box. Colors are fixed (black background, white text) because it sits over arbitrary content.
 * Drag beyond touch slop calls [onDrag]; a 500 ms long-press with no movement shows the close/quit row for 5 s; a tap
 * does nothing.
 */
@Composable
fun SubtitleOverlay(
    lines: List<OverlayLine>,
    notice: OverlayNotice?,
    settings: OverlaySettings,
    mode: OverlayMode,
    onClose: () -> Unit,
    onExitApp: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val setup = mode != OverlayMode.TRANSLATING
    val bgAlpha = if (mode == OverlayMode.SETUP) 0.85f else settings.bgOpacityPercent / 100f
    val haptics = LocalHapticFeedback.current
    var controlsVisible by remember { mutableStateOf(false) }
    var originX by remember { mutableFloatStateOf(0f) }
    var originY by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) { delay(5_000); controlsVisible = false }
    }
    // Auto-hide: fade the whole box after N s without a new sentence (suspended in setup mode).
    val newestId = lines.lastOrNull { !it.isSource }?.id
    LaunchedEffect(newestId, notice, setup, settings.autoHideSec) {
        visible = true
        if (!setup && settings.autoHideSec > 0) { delay(settings.autoHideSec * 1_000L); visible = false }
    }

    AnimatedVisibility(visible = visible || setup, enter = fadeIn(tween(200)), exit = fadeOut(tween(400))) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .testTag("overlay_box")
                    .semantics { overlayBackgroundAlpha = bgAlpha }
                    .fillMaxWidth()
                    .onGloballyPositioned { val p = it.positionOnScreen(); originX = p.x; originY = p.y }
                    .background(Color.Black.copy(alpha = bgAlpha), shape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            val outcome: String? = withTimeoutOrNull(500L) {
                                var result = "never"
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    if (change == null) { result = "cancel"; break }
                                    if (change.changedToUp()) { result = "up"; break }
                                    if ((change.position - down.position).getDistance() > slop) { result = "drag"; break }
                                }
                                result
                            }
                            // Drags use screen coordinates: the window moves under the finger, so local deltas would feed back.
                            suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.dragLoop() {
                                var lastX = originX + down.position.x
                                var lastY = originY + down.position.y
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    val x = originX + change.position.x
                                    val y = originY + change.position.y
                                    if (x != lastX || y != lastY) { onDrag(x - lastX, y - lastY); lastX = x; lastY = y }
                                    change.consume()
                                    if (change.changedToUp() || !change.pressed) break
                                }
                                onDragEnd()
                            }
                            when (outcome) {
                                null -> { // held 500 ms: haptic, then either a 2D drag (finger moves) or the control row (release)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    var moved = false
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if ((change.position - down.position).getDistance() > slop) { moved = true; break }
                                        if (change.changedToUp() || !change.pressed) break
                                    }
                                    if (moved) {
                                        controlsVisible = false
                                        dragLoop()
                                    } else {
                                        controlsVisible = true
                                    }
                                }
                                "drag" -> dragLoop()
                                else -> Unit // tap: nothing
                            }
                        }
                    },
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (setup) {
                        Box(
                            Modifier.testTag("drag_handle").align(Alignment.CenterHorizontally)
                                .padding(bottom = 6.dp).width(40.dp).height(4.dp)
                                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                        )
                    }
                    val shown = if (lines.isEmpty() && setup) {
                        listOf(
                            OverlayLine(-1, stringResource(R.string.overlay_sample_1), false, false),
                            OverlayLine(-2, stringResource(R.string.overlay_sample_2), false, true),
                        )
                    } else lines
                    val newest = shown.lastOrNull { it.isNewest }?.id
                    for (line in shown) {
                        val older = line.id != newest
                        Text(
                            text = line.text,
                            color = if (line.isSource) SourceColor else Color.White,
                            style = TextStyle(
                                fontSize = (if (line.isSource) settings.textSizeSp * 0.8f else settings.textSizeSp.toFloat()).sp,
                                lineHeight = (settings.textSizeSp * 1.4f).sp,
                                fontWeight = FontWeight.Medium,
                                shadow = TextShadow,
                            ),
                            modifier = Modifier.alpha(if (older && !setup) 0.7f else 1f),
                        )
                    }
                    if (notice != null) {
                        Text(
                            text = stringResource(if (notice == OverlayNotice.MUSIC) R.string.overlay_music else R.string.overlay_capture_blocked),
                            color = Color.White,
                            style = TextStyle(fontSize = (settings.textSizeSp * 0.9f).sp, fontWeight = FontWeight.Medium, shadow = TextShadow),
                            modifier = Modifier.testTag("notice"),
                        )
                    }
                }
                if (setup) Box(Modifier.testTag("outline").matchParentSize().border(1.dp, PrimaryOutline, shape))
            }
            if (setup) {
                Text(
                    stringResource(R.string.overlay_drag_hint), color = Color.White,
                    style = TextStyle(fontSize = 12.sp),
                    modifier = Modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (controlsVisible) {
                Row(
                    Modifier.padding(top = 6.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ControlButton(Icons.Filled.Close, stringResource(R.string.overlay_close)) { controlsVisible = false; onClose() }
                    ControlButton(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.overlay_exit_app)) { controlsVisible = false; onExitApp() }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, color = Color.White, style = TextStyle(fontSize = 14.sp))
    }
}
