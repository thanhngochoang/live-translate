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

    @Test fun drag_inLandscape_savesLandscapeOnly() {
        attachVisible()
        rotate(landscape = true)
        onMain { controller.dragBy(-80f); controller.dragEnd() }
        waitFor(what = "landscape saved") { settings.value.customYLandscape != null }
        assertNull(settings.value.customYPortrait)
        assertNotNull(settings.value.customYLandscape)
    }
}
