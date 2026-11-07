package io.github.thanhngochoang.voicetrans.feature.overlay

import android.app.UiAutomation
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.core.settings.OverlayPosition
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverlayControllerTest {
    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext
    private val pkg = "io.github.thanhngochoang.voicetrans"
    private lateinit var rig: SessionRig
    private val settings = MutableStateFlow(OverlaySettings())
    private lateinit var controller: OverlayController
    // A foreground activity that allows rotation: with only the launcher in front the display may refuse to rotate.
    private var host: androidx.test.core.app.ActivityScenario<io.github.thanhngochoang.voicetrans.feature.ui.UiHostActivity>? = null

    private fun shell(cmd: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() } }

    @Before fun setUp() {
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        io.github.thanhngochoang.voicetrans.feature.ui.UiHost.content = {}
        host = androidx.test.core.app.ActivityScenario.launch(io.github.thanhngochoang.voicetrans.feature.ui.UiHostActivity::class.java)
        rig = SessionRig()
        controller = OverlayController(ctx, settings, { rig.session }, saveOverlay = { t -> settings.value = t(settings.value) })
    }

    @After fun tearDown() {
        onMain { controller.detach(); controller.setSetupMode(false) }
        rig.close()
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        runCatching { host?.close() }
        io.github.thanhngochoang.voicetrans.feature.ui.UiHost.reset()
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
    }

    private fun running() {
        rig.startRunning()
        waitFor(what = "running") { rig.session.state.value.toString() == "Running" }
    }

    private val params get() = controller.attachedView?.layoutParams as WindowManager.LayoutParams

    private fun attachVisible() {
        running()
        onMain { controller.attach(); controller.setAppInForeground(false) }
        waitFor(what = "view attached") { controller.attachedView != null }
    }

    /** The window is first placed with a guessed height, then moved once the box is measured: wait for that. */
    private fun awaitLaidOut() {
        waitFor(what = "box measured") { controller.attachedView?.let { it.height > 0 } == true }
        var last = Int.MIN_VALUE
        waitFor(what = "position stable") {
            var y = 0
            onMain { y = params.y }
            (y == last).also { last = y; Thread.sleep(100) }
        }
    }

    @Test fun hiddenWhileAppInForeground() {
        running()
        onMain { controller.attach(); controller.setAppInForeground(true) }
        Thread.sleep(300)
        assertNull(controller.attachedView)
        onMain { controller.setAppInForeground(false) }
        waitFor(what = "view attached") { controller.attachedView != null }
    }

    @Test fun noPermission_noCrash_noView() {
        shell("appops set $pkg SYSTEM_ALERT_WINDOW deny")
        running()
        onMain { controller.attach(); controller.setAppInForeground(false) }
        Thread.sleep(400)
        assertNull(controller.attachedView)
    }

    @Test fun lockedMode_setsNotTouchableFlag() {
        settings.value = OverlaySettings(locked = true)
        attachVisible()
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0.8f, params.alpha)
    }

    @Test fun settingsChange_appliesWhileAttached() {
        attachVisible()
        val view = controller.attachedView
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        settings.value = settings.value.copy(bgOpacityPercent = 20)
        settings.value = settings.value.copy(locked = true)
        waitFor(what = "locked applied") { controller.attachedView?.let { (it.layoutParams as WindowManager.LayoutParams).alpha == 0.8f } == true }
        assertSame(view, controller.attachedView)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(1, controller.attachCount)
    }

    @Test fun overlayHidden_detachesUntilShown() {
        attachVisible()
        onMain { rig.session.setOverlayHidden(true) }
        waitFor(what = "view removed") { controller.attachedView == null }
        onMain { rig.session.setOverlayHidden(false) }
        waitFor(what = "view re-added") { controller.attachedView != null }
    }

    @Test fun setupMode_showsEvenInForegroundWithoutSession() {
        onMain { controller.setAppInForeground(true); controller.setSetupMode(true) }
        waitFor(what = "setup view") { controller.attachedView != null }
        assertEquals(OverlayMode.SETUP, controller.mode.value)
        onMain { controller.setSetupMode(false) }
        waitFor(what = "detached") { controller.attachedView == null }
    }

    @Test fun settingsChange_flashesSetupFor2s() {
        attachVisible()
        assertEquals(OverlayMode.TRANSLATING, controller.mode.value)
        settings.value = settings.value.copy(textSizeSp = 22)
        waitFor(what = "setup flash") { controller.mode.value == OverlayMode.SETUP }
        waitFor(timeoutMs = 4000, what = "back to translating") { controller.mode.value == OverlayMode.TRANSLATING }
    }

    private fun rotate(landscape: Boolean) {
        instr.uiAutomation.setRotation(if (landscape) UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val target = if (landscape) android.view.Surface.ROTATION_90 else android.view.Surface.ROTATION_0
        @Suppress("DEPRECATION")
        waitFor(timeoutMs = 15_000, what = "display rotation $target (now ${wm.defaultDisplay.rotation})") { wm.defaultDisplay.rotation == target }
        waitFor(timeoutMs = 10_000, what = "window metrics orientation") { wm.currentWindowMetrics.bounds.let { it.width() > it.height() } == landscape }
        onMain { controller.onConfigurationChanged(Configuration(ctx.resources.configuration)) }
        Thread.sleep(300)
    }

    private fun expectedY(custom: Float?, pos: OverlayPosition = OverlayPosition.BOTTOM): Int {
        val safe = controller.currentSafeArea()
        val density = ctx.resources.displayMetrics.density
        val orientation = if (safe.right - safe.left > safe.bottom - safe.top) Orientation.LANDSCAPE else Orientation.PORTRAIT
        return overlayY(pos, custom, orientation, safe, controller.attachedView!!.height, density)
    }

    @Test fun rotation_relayoutsWithoutReadd() {
        attachVisible()
        val view = controller.attachedView
        val widthBefore = params.width
        rotate(landscape = true)
        assertSame(view, controller.attachedView)
        assertEquals(1, controller.attachCount)
        val safe = controller.currentSafeArea()
        assertEquals(overlayWidth(safe, ctx.resources.displayMetrics.density), params.width)
        assertTrue("width should change on rotation", params.width != widthBefore)
        assertEquals(expectedY(null), params.y)
    }

    @Test fun rotation_usesPerOrientationPosition() {
        settings.value = OverlaySettings(customYPortrait = 0.1f, customYLandscape = null)
        attachVisible()
        waitFor(what = "portrait custom y") { params.y == expectedY(0.1f) }
        rotate(landscape = true)
        waitFor(what = "landscape bottom preset") { params.y == expectedY(null) }
        rotate(landscape = false)
        waitFor(what = "portrait position restored") { params.y == expectedY(0.1f) }
    }

    @Test fun drag_inLandscape_savesXAndYForLandscapeOnly() {
        attachVisible()
        rotate(landscape = true)
        onMain { controller.dragStart(); controller.dragTo(-150f, -80f); controller.dragEnd() }
        waitFor(what = "landscape saved") { settings.value.customYLandscape != null && settings.value.customXLandscape != null }
        assertNull(settings.value.customYPortrait)
        assertNull(settings.value.customXPortrait)
        assertNotNull(settings.value.customXLandscape)
    }

    /** The save is async: until it lands the window must stay where it was released, not snap back for a frame. */
    @Test fun dragEnd_keepsWindowWhereReleased_beforeSaveLands() {
        attachVisible()
        awaitLaidOut()
        val startY = params.y
        var yRightAfterRelease = 0
        onMain {
            controller.dragStart()
            controller.dragTo(0f, -300f)
            controller.dragEnd()
            yRightAfterRelease = params.y // same main-thread turn: the save coroutine has not run yet
        }
        assertTrue("y right after release $yRightAfterRelease, expected ${startY - 300} ±1", kotlin.math.abs(yRightAfterRelease - (startY - 300)) <= 1)
        waitFor(what = "saved") { settings.value.customYPortrait != null }
        Thread.sleep(200)
        assertTrue("y after save ${params.y}, expected ${startY - 300} ±1", kotlin.math.abs(params.y - (startY - 300)) <= 1)
    }

    @Test fun drag_movesWindowInX_andStaysInsideSafeArea() {
        attachVisible()
        val before = params.x
        onMain { controller.dragStart(); controller.dragTo(-100000f, 0f) }
        val safe = controller.currentSafeArea()
        waitFor(what = "x clamped to the safe left edge") { params.x == safe.left || params.width > safe.width }
        assertTrue(params.x <= before)
        onMain { controller.dragTo(100000f, 0f) }
        waitFor(what = "x clamped to the safe right edge") { params.x == safe.right - params.width || params.width > safe.width }
        onMain { controller.dragEnd() }
    }

    @Test fun reset_recentersHorizontally() {
        settings.value = OverlaySettings(customXPortrait = 0.0f, customYPortrait = 0.2f)
        attachVisible()
        val safe = controller.currentSafeArea()
        waitFor(what = "off-center x") { params.x == overlayX(0.0f, safe, params.width) }
        settings.value = settings.value.copy(position = OverlayPosition.BOTTOM, customXPortrait = null, customYPortrait = null, customXLandscape = null, customYLandscape = null)
        waitFor(what = "x re-centered") { params.x == overlayX(null, safe, params.width) }
        assertEquals(overlayX(null, safe, params.width), params.x)
    }

    // ---- real touch streams through the window system (the feedback-loop regression) ----

    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        instr.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun showSetupBox(): Triple<Float, Float, IntArray> {
        onMain { controller.setAppInForeground(true); controller.setSetupMode(true) }
        waitFor(what = "setup box laid out") { controller.attachedView?.let { it.width > 0 && it.height > 0 } == true }
        Thread.sleep(500) // let the first compose frame settle
        val frame = controller.attachedView as OverlayTouchFrame
        waitFor(what = "box bounds reported") { !frame.boxBounds.isEmpty }
        awaitLaidOut()
        // The window's screen position is its layout params (TOP|LEFT gravity); getLocationOnScreen can lag a move.
        // Aim at the caption box itself: the window also holds the "drag to move" chip below it.
        val loc = intArrayOf(params.x, params.y)
        return Triple(loc[0] + frame.boxBounds.exactCenterX(), loc[1] + frame.boxBounds.exactCenterY(), loc)
    }

    /** Streams 30 fast MOVEs (-15 px y, +3 px x each, about 8 ms apart) and checks the window tracks the finger. */
    private fun fastDragAndCheck(holdMs: Long) {
        val (cx, cy, _) = showSetupBox()
        val safe = controller.currentSafeArea()
        val startX = params.x
        val startY = params.y
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        val downTime = android.os.SystemClock.uptimeMillis()
        inject(android.view.MotionEvent.ACTION_DOWN, cx, cy, downTime)
        if (holdMs > 0) Thread.sleep(holdMs)
        val ys = mutableListOf<Int>()
        val xs = mutableListOf<Int>()
        for (i in 1..30) {
            inject(android.view.MotionEvent.ACTION_MOVE, cx + 3f * i, cy - 15f * i, downTime)
            Thread.sleep(8)
            onMain { val p = controller.attachedView!!.layoutParams as WindowManager.LayoutParams; ys += p.y; xs += p.x }
        }
        inject(android.view.MotionEvent.ACTION_UP, cx + 90f, cy - 450f, downTime)
        Thread.sleep(300)
        val finalParams = params
        val reversals = ys.zipWithNext().filter { (a, b) -> b - a > 2 }
        assertTrue("y reversed (oscillation): $ys", reversals.isEmpty())
        val view = controller.attachedView!!
        val maxY = (safe.bottom - view.height).coerceAtLeast(safe.top)
        val expectedY = (startY - 450).coerceIn(safe.top, maxY)
        val expectedX = (startX + 90).coerceIn(safe.left, (safe.right - finalParams.width).coerceAtLeast(safe.left))
        assertTrue("final y ${finalParams.y} should be $expectedY +-${slop + 2} (start $startY, ys=$ys)", kotlin.math.abs(finalParams.y - expectedY) <= slop + 2)
        assertTrue("final x ${finalParams.x} should be $expectedX +-${slop + 2} (start $startX)", kotlin.math.abs(finalParams.x - expectedX) <= slop + 2)
    }

    @Test fun fastDrag_windowTracksFingerWithoutOscillation() = fastDragAndCheck(holdMs = 0)

    @Test fun fastLongPressThenDrag_tracksFinger() = fastDragAndCheck(holdMs = 600)

    @Test fun holdAndRelease_showsControlRow_withoutMoving() {
        val (cx, cy, _) = showSetupBox()
        val startY = params.y
        val downTime = android.os.SystemClock.uptimeMillis()
        inject(android.view.MotionEvent.ACTION_DOWN, cx, cy, downTime)
        Thread.sleep(700)
        inject(android.view.MotionEvent.ACTION_UP, cx, cy, downTime)
        waitFor(what = "control row") { controller.controlsVisible.value }
        assertEquals(startY, params.y)
    }

    @Test fun tap_neitherMovesNorShowsControls() {
        val (cx, cy, _) = showSetupBox()
        val startX = params.x
        val startY = params.y
        val downTime = android.os.SystemClock.uptimeMillis()
        inject(android.view.MotionEvent.ACTION_DOWN, cx, cy, downTime)
        Thread.sleep(80)
        inject(android.view.MotionEvent.ACTION_UP, cx, cy, downTime)
        Thread.sleep(700)
        assertTrue(!controller.controlsVisible.value)
        assertEquals(startX, params.x)
        assertEquals(startY, params.y)
    }
}
