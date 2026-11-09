package io.github.thanhngochoang.voicetrans.feature.overlay

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClass
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.TranslationSession
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * App-level singleton that owns the floating subtitle window. It is not owned by the service, so Settings can show
 * the setup overlay when no session runs. The session is resolved lazily (on the first [attach]) so creating the
 * controller never loads the speech models.
 *
 * Visible when: `setupMode || (sessionRunning && !appInForeground && !overlayHidden && enabled && canDrawOverlays)`.
 * Settings and rotation changes call `updateViewLayout`; the view is never re-added for them.
 */
class OverlayController(
    context: Context,
    private val settings: Flow<OverlaySettings>,
    private val sessionProvider: () -> TranslationSession,
    private val saveOverlay: suspend ((OverlaySettings) -> OverlaySettings) -> Unit = {},
    private val exitApp: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : io.github.thanhngochoang.voicetrans.feature.ui.settings.OverlayControls {
    private val appContext: Context = context.applicationContext
    private val windowContext: Context by lazy { createWindowContext() }
    private val wm: WindowManager by lazy { windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Ui(
        val lines: List<OverlayLine> = emptyList(),
        val livePartial: String? = null,
        val notice: OverlayNotice? = null,
        val settings: OverlaySettings = OverlaySettings(),
        val mode: OverlayMode = OverlayMode.TRANSLATING,
        val epoch: Int = 0,
    )

    private val ui = MutableStateFlow(Ui())
    private val _mode = MutableStateFlow(OverlayMode.TRANSLATING)
    val mode: StateFlow<OverlayMode> = _mode.asStateFlow()

    // inputs
    private var attachRequested = false
    private var setupMode = false
    private var appInForeground = true
    private var dragging = false
    private var flashing = false
    private var opacityDragging = false
    private var current = OverlaySettings()
    private var sessionRunning = false
    private var overlayHidden = false
    private var audioClass = AudioClass.OTHER
    private var captureWarning: io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning? = null
    private var utterances = emptyList<io.github.thanhngochoang.voicetrans.feature.session.Utterance>()
    private var partialText: String? = null
    private var partialDraft: String? = null
    private var lastSpeechAtMs = 0L
    private var lastSpeechId = -1L
    private var observing = false
    private var flashJob: Job? = null
    private var dragX: Int? = null
    private var dragY: Int? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private val _controlsVisible = MutableStateFlow(false)
    /** The long-press close/quit row. Transient: it closes on rotation and after 5 s. */
    internal val controlsVisible: StateFlow<Boolean> = _controlsVisible.asStateFlow()
    private var epoch = 0

    internal var attachedView: View? = null
        private set
    internal var attachCount = 0
        private set
    private var owner: OverlayOwner? = null
    private var firstSettings = true

    // ---- public API -------------------------------------------------------------------------------------------

    fun attach() = onMain {
        attachRequested = true
        ensureObserving()
        refresh()
    }

    /** Keeps the view while setup mode is active. */
    fun detach() = onMain {
        attachRequested = false
        refresh()
    }

    fun setAppInForeground(visible: Boolean) = onMain {
        appInForeground = visible
        refresh()
    }

    override fun setOpacityDragging(active: Boolean) = onMain {
        opacityDragging = active
        if (!active) flash()
        recomputeUi()
    }

    override fun setSetupMode(active: Boolean) = onMain {
        setupMode = active
        ensureSettingsObserver()
        refresh()
    }

    /** Drops everything (used by AppExit): no view, no setup mode. */
    fun shutdown() = onMain {
        attachRequested = false
        setupMode = false
        dragging = false
        refresh()
    }

    /** Toggles the locked (click-through) state of the overlay. */
    fun toggleLock() = onMain {
        val newLocked = !current.locked
        val update: (OverlaySettings) -> OverlaySettings = { it.copy(locked = newLocked) }
        current = update(current)
        scope.launch { saveOverlay(update) }
        flash()
        updateLayout()
    }

    /** Wires foreground/background tracking to the process lifecycle. Call once from the Application. */
    fun bindProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = setAppInForeground(true)
            override fun onStop(owner: LifecycleOwner) = setAppInForeground(false)
        })
    }

    fun onConfigurationChanged(newConfig: Configuration) = onMain {
        epoch++
        _controlsVisible.value = false // the long-press control row is transient: it closes on rotation
        dragging = false
        dragX = null
        dragY = null
        recomputeUi()
        updateLayout()
    }

    // ---- observation ------------------------------------------------------------------------------------------

    /** The controller's state lives on the main thread; calls from other threads are posted there. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun ensureSettingsObserver() {
        if (settingsObserved) return
        settingsObserved = true
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = this@OverlayController.onConfigurationChanged(newConfig)
            @Deprecated("Deprecated in Java") override fun onLowMemory() = Unit
            override fun onTrimMemory(level: Int) = Unit
        })
        scope.launch {
            settings.collect { s ->
                current = s
                if (!firstSettings) flash()
                firstSettings = false
                recomputeUi()
                refresh()
            }
        }
    }

    private var settingsObserved = false

    private fun ensureObserving() {
        ensureSettingsObserver()
        if (observing) return
        observing = true
        val session = sessionProvider()
        scope.launch { session.state.collect { sessionRunning = it == SessionState.Running; refresh() } }
        scope.launch { session.overlayHidden.collect { overlayHidden = it; refresh() } }
        scope.launch { session.audioClass.collect { audioClass = it; recomputeUi() } }
        scope.launch { session.captureWarning.collect { captureWarning = it; recomputeUi() } }
        scope.launch {
            session.partial.collect { p ->
                partialText = p
                recomputeUi()
                updateLayout()
            }
        }
        scope.launch {
            session.partialTranslation.collect { d ->
                partialDraft = d
                recomputeUi()
                updateLayout()
            }
        }
        scope.launch {
            session.utterances.collect { list ->
                utterances = list
                val lastSpeech = list.lastOrNull { it.kind == UtteranceKind.SPEECH && it.translation != null }
                if (lastSpeech != null && lastSpeech.id != lastSpeechId) { lastSpeechId = lastSpeech.id; lastSpeechAtMs = clock() }
                recomputeUi()
                updateLayout()
            }
        }
        scope.launch { while (true) { delay(500); if (attachedView != null) recomputeUi() } } // lets the music notice appear after 2 s
    }

    private fun flash() {
        flashJob?.cancel()
        flashing = true
        recomputeUi()
        flashJob = scope.launch { delay(2_000); flashing = false; recomputeUi() }
    }

    // ---- state to UI ------------------------------------------------------------------------------------------

    private fun recomputeUi() {
        val newMode = when {
            opacityDragging -> OverlayMode.PREVIEW
            setupMode || dragging || flashing -> OverlayMode.SETUP
            else -> OverlayMode.TRANSLATING
        }
        _mode.value = newMode
        val safe = currentSafeArea()
        val density = appContext.resources.displayMetrics.density
        val raw = overlayLines(utterances, current.maxLines, current.showSource)
        val width = overlayWidth(safe, density) - (24 * density).roundToInt()
        val notice = overlayNotice(audioClass, captureWarning, if (lastSpeechAtMs == 0L) Long.MAX_VALUE else clock() - lastSpeechAtMs)
        val inFlight = utterances.lastOrNull { it.kind == UtteranceKind.SPEECH && !it.translationFailed && it.translation == null }
        val live = overlayLiveLine(
            partialDraft = partialDraft,
            partialText = partialText,
            inFlight = inFlight != null,
            translatingText = appContext.getString(io.github.thanhngochoang.voicetrans.R.string.overlay_translating),
        )
        val heights = raw.map { lineHeightPx(it, width, density) }
        val noticeHeight = if (notice != null) (current.textSizeSp * density * 1.4f).roundToInt() else 0
        val liveHeight = if (live != null) (current.textSizeSp * density * 1.3f).roundToInt() else 0
        val max = maxBoxHeight(safe) - noticeHeight - liveHeight - (16 * density).roundToInt()
        ui.value = Ui(fitLines(raw, heights, max), live, notice, current, newMode, epoch)
    }

    private fun lineHeightPx(line: OverlayLine, widthPx: Int, density: Float): Int {
        val sp = if (line.isSource) current.textSizeSp * 0.8f else current.textSizeSp.toFloat()
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = sp * density * appContext.resources.configuration.fontScale; typeface = Typeface.DEFAULT_BOLD }
        return StaticLayout.Builder.obtain(line.text, 0, line.text.length, paint, widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.4f / 1.2f).build().height
    }

    // ---- visibility and window --------------------------------------------------------------------------------

    private fun shouldShow(): Boolean =
        setupMode || (attachRequested && sessionRunning && !appInForeground && !overlayHidden && current.enabled)

    private fun refresh() {
        if (shouldShow()) {
            if (attachedView == null) addView() else updateLayout()
        } else if (attachedView != null) {
            removeView()
        }
    }

    private fun addView() {
        if (!Settings.canDrawOverlays(appContext)) return // the user can revoke it at any time
        recomputeUi()
        val owner = OverlayOwner().also { it.start() }
        val frame = OverlayTouchFrame(windowContext, object : OverlayGestureDetector.Listener {
            override fun onLongPress() = Unit // the frame plays the haptic
            override fun onDragStart() = dragStart()
            override fun onDragTo(dx: Float, dy: Float) = dragTo(dx, dy)
            override fun onDragEnd() = dragEnd()
            override fun onShowControls() { _controlsVisible.value = true }
        })
        // Compose looks up the owners from the window's root view, which is the frame.
        frame.setViewTreeLifecycleOwner(owner)
        frame.setViewTreeSavedStateRegistryOwner(owner)
        val content = ComposeView(windowContext).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                val state by ui.collectAsState()
                val controls by controlsVisible.collectAsState()
                key(state.epoch) {
                    SubtitleOverlay(
                        lines = state.lines, notice = state.notice, settings = state.settings, mode = state.mode,
                        onClose = { scope.launch { sessionProvider().setOverlayHidden(true) } },
                        onExitApp = exitApp,
                        controlsVisible = controls,
                        onControlsHidden = { _controlsVisible.value = false },
                        onBoxBounds = { frame.boxBounds = it },
                        livePartial = state.livePartial,
                        onToggleLock = { toggleLock() },
                    )
                }
            }
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) updateLayout()
            }
        }
        frame.addView(content)
        val view: View = frame
        try {
            wm.addView(view, buildParams(view.height))
        } catch (e: Exception) {
            Log.w(TAG, "could not add overlay window", e)
            owner.destroy()
            return
        }
        this.owner = owner
        attachedView = view
        attachCount++
    }

    private fun removeView() {
        val view = attachedView ?: return
        attachedView = null
        try { wm.removeViewImmediate(view) } catch (e: Exception) { Log.w(TAG, "remove overlay failed", e) }
        owner?.destroy(); owner = null
    }

    private fun updateLayout() {
        val view = attachedView ?: return
        try { wm.updateViewLayout(view, buildParams(view.height)) } catch (e: Exception) { Log.w(TAG, "update overlay failed", e) }
    }

    private fun buildParams(measuredHeight: Int): WindowManager.LayoutParams {
        val safe = currentSafeArea()
        val density = appContext.resources.displayMetrics.density
        val width = overlayWidth(safe, density)
        val boxHeight = if (measuredHeight > 0) measuredHeight else (60 * density).roundToInt()
        val y = dragY ?: overlayY(current.position, customYFor(safe), orientation(safe), safe, boxHeight, density)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (current.locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dragX ?: overlayX(customXFor(safe), safe, width)
            this.y = y
            alpha = windowAlpha(current.locked)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "VoiceTrans subtitle"
        }
    }

    private fun customXFor(safe: SafeArea): Float? =
        if (orientation(safe) == Orientation.PORTRAIT) current.customXPortrait else current.customXLandscape

    private fun customYFor(safe: SafeArea): Float? =
        if (orientation(safe) == Orientation.PORTRAIT) current.customYPortrait else current.customYLandscape

    private fun orientation(safe: SafeArea) = if (safe.width > safe.height) Orientation.LANDSCAPE else Orientation.PORTRAIT

    // ---- drag ---------------------------------------------------------------------------------------------------

    /** Moves the box by ([dx], [dy]) px, keeping it wholly inside the safe area. */
    /** Starts a drag from the window's current position. */
    internal fun dragStart() {
        val view = attachedView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        dragStartX = params.x
        dragStartY = params.y
        _controlsVisible.value = false
        if (!dragging) { dragging = true; recomputeUi() }
    }

    /**
     * Places the window at its drag-start position plus the finger's offset ([dx], [dy] in px, from the down point,
     * screen coordinates), kept inside the safe area. Absolute, not incremental, so nothing accumulates.
     */
    internal fun dragTo(dx: Float, dy: Float) {
        val view = attachedView ?: return
        if (!dragging) dragStart()
        val safe = currentSafeArea()
        val width = (view.layoutParams as WindowManager.LayoutParams).width
        val maxY = (safe.bottom - view.height).coerceAtLeast(safe.top)
        dragY = (dragStartY + dy).roundToInt().coerceIn(safe.top, maxY)
        dragX = if (safe.width - width <= 0) overlayX(null, safe, width)
        else (dragStartX + dx).roundToInt().coerceIn(safe.left, safe.right - width)
        updateLayout()
    }

    internal fun dragEnd() {
        val view = attachedView
        val x = dragX
        val y = dragY
        dragging = false
        dragX = null
        dragY = null
        if (view != null && y != null) {
            val safe = currentSafeArea()
            val free = (safe.height - view.height).coerceAtLeast(1)
            val fractionY = ((y - safe.top).toFloat() / free).coerceIn(0f, 1f)
            val fractionX = xFraction(x ?: (view.layoutParams as WindowManager.LayoutParams).x, safe, (view.layoutParams as WindowManager.LayoutParams).width)
            val portrait = orientation(safe) == Orientation.PORTRAIT
            val update: (OverlaySettings) -> OverlaySettings = {
                if (portrait) it.copy(customXPortrait = fractionX, customYPortrait = fractionY)
                else it.copy(customXLandscape = fractionX, customYLandscape = fractionY)
            }
            // Apply it now: the save is async, and until it lands the window would snap back to the old position.
            current = update(current)
            scope.launch { saveOverlay(update) }
        }
        flash()
        updateLayout()
    }

    // ---- geometry -----------------------------------------------------------------------------------------------

    internal fun currentSafeArea(): SafeArea {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return SafeArea(insets.left, insets.top, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val statusId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (statusId > 0) appContext.resources.getDimensionPixelSize(statusId) else 0
        return SafeArea(0, status, dm.widthPixels, dm.heightPixels)
    }

    private fun createWindowContext(): Context {
        if (Build.VERSION.SDK_INT >= 31) {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            return appContext.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }
        return appContext
    }

    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object { const val TAG = "OverlayController" }
}
