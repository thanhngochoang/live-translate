package io.github.thanhngochoang.voicetrans.feature.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Gesture rules for the caption box, in **screen** coordinates (MotionEvent.rawX/rawY).
 *
 * The overlay window moves under the finger while it is dragged, so window-local coordinates would feed the
 * window's own movement back into the drag (the box oscillates and drifts). Drags are therefore reported as the
 * offset from the down point, and the controller places the window at its start position plus that offset.
 *
 * - Move beyond [touchSlop] before [longPressMs]: drag.
 * - Hold [longPressMs]: [Listener.onLongPress] (haptic), then a move beyond slop drags (no controls), and a release
 *   shows the control row.
 * - Tap: nothing.
 */
/** Hold time before a press counts as a long-press (spec: 500 ms). */
internal const val LONG_PRESS_MS = 500L

internal class OverlayGestureDetector(
    private val touchSlop: Float,
    private val longPressMs: Long = LONG_PRESS_MS,
    private val listener: Listener,
) {
    interface Listener {
        fun onLongPress()
        fun onDragStart()
        fun onDragTo(dx: Float, dy: Float)
        fun onDragEnd()
        fun onShowControls()
    }

    private enum class State { IDLE, PRESSED, HELD, DRAGGING }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    val isDragging: Boolean get() = state == State.DRAGGING

    fun onDown(rawX: Float, rawY: Float, eventTime: Long) {
        if (state == State.DRAGGING) listener.onDragEnd()
        state = State.PRESSED
        downX = rawX
        downY = rawY
        downTime = eventTime
    }

    /** Returns true while the gesture is a drag, so the caller can keep the event from the content. */
    fun onMove(rawX: Float, rawY: Float, eventTime: Long): Boolean {
        if (state == State.IDLE) return false
        val dx = rawX - downX
        val dy = rawY - downY
        if (state != State.DRAGGING) {
            if (hypot(dx, dy) <= touchSlop) {
                onLongPressTimeout(eventTime)
                return false
            }
            state = State.DRAGGING
            listener.onDragStart()
        }
        listener.onDragTo(dx, dy)
        return true
    }

    fun onUp(rawX: Float, rawY: Float, eventTime: Long) {
        if (state == State.PRESSED) onLongPressTimeout(eventTime)
        when (state) {
            State.DRAGGING -> {
                listener.onDragTo(rawX - downX, rawY - downY) // the finger may lift beyond the last MOVE
                listener.onDragEnd()
            }
            State.HELD -> listener.onShowControls()
            else -> Unit // tap: nothing
        }
        state = State.IDLE
    }

    fun onCancel() {
        if (state == State.DRAGGING) listener.onDragEnd()
        state = State.IDLE
    }

    /** Called by the long-press timer, and from move/up event times in case the timer runs late. */
    fun onLongPressTimeout(now: Long) {
        if (state == State.PRESSED && now - downTime >= longPressMs) {
            state = State.HELD
            listener.onLongPress()
        }
    }
}

/**
 * Root of the overlay window. It sees every touch before the Compose content, feeds the caption box's gestures to
 * [OverlayGestureDetector] in raw screen coordinates, and keeps drag moves away from the content. Touches outside
 * [boxBounds] (window-local, e.g. on the control row) go to the content untouched.
 */
@SuppressLint("ViewConstructor")
internal class OverlayTouchFrame(
    context: Context,
    private val callbacks: OverlayGestureDetector.Listener,
) : FrameLayout(context) {
    /** The caption box in window coordinates, reported by the content. Empty until it is laid out. */
    var boxBounds = Rect()

    private val detector = OverlayGestureDetector(
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        listener = object : OverlayGestureDetector.Listener by callbacks {
            override fun onLongPress() {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                callbacks.onLongPress()
            }
        },
    )
    private var tracking = false
    private var contentCancelled = false
    private val longPress = Runnable { detector.onLongPressTimeout(android.os.SystemClock.uptimeMillis()) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = boxBounds.contains(ev.x.toInt(), ev.y.toInt())
                contentCancelled = false
                if (tracking) {
                    detector.onDown(ev.rawX, ev.rawY, ev.eventTime)
                    postDelayed(longPress, LONG_PRESS_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> if (tracking && detector.onMove(ev.rawX, ev.rawY, ev.eventTime)) {
                removeCallbacks(longPress)
                cancelContent(ev)
                return true
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                removeCallbacks(longPress)
                val dragged = detector.isDragging
                detector.onUp(ev.rawX, ev.rawY, ev.eventTime)
                if (dragged) return true
            }
            MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                removeCallbacks(longPress)
                detector.onCancel()
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit // a second finger does not change the gesture
        }
        return super.dispatchTouchEvent(ev)
    }

    /** The content saw the down; send it a cancel once so it doesn't treat the drag as a press. */
    private fun cancelContent(ev: MotionEvent) {
        if (contentCancelled) return
        contentCancelled = true
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        if (tracking) { tracking = false; detector.onCancel() }
        super.onDetachedFromWindow()
    }
}
