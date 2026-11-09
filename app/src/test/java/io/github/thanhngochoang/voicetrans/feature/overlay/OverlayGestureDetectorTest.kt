package io.github.thanhngochoang.voicetrans.feature.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGestureDetectorTest {
    private val events = mutableListOf<String>()
    private val detector = OverlayGestureDetector(touchSlop = 20f, longPressMs = 500, listener = object : OverlayGestureDetector.Listener {
        override fun onLongPress() { events += "longPress" }
        override fun onDragStart() { events += "start" }
        override fun onDragTo(dx: Float, dy: Float) { events += "to(${dx.toInt()},${dy.toInt()})" }
        override fun onDragEnd() { events += "end" }
        override fun onShowControls() { events += "controls" }
    })

    @Test fun tap_doesNothing() {
        detector.onDown(100f, 100f, 0)
        detector.onMove(105f, 103f, 50)
        detector.onUp(105f, 103f, 120)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun quickDrag_reportsOffsetsFromTheDownPoint_notIncrements() {
        detector.onDown(100f, 1000f, 0)
        assertTrue(detector.onMove(103f, 985f, 8).not()) // within slop: not a drag yet
        assertTrue(detector.onMove(106f, 970f, 16))
        assertTrue(detector.onMove(109f, 955f, 24))
        detector.onUp(109f, 955f, 32)
        assertEquals(listOf("start", "to(6,-30)", "to(9,-45)", "to(9,-45)", "end"), events)
    }

    @Test fun holdThenDrag_hapticThenDrag_noControls() {
        detector.onDown(100f, 1000f, 0)
        detector.onLongPressTimeout(500)
        assertTrue(detector.onMove(100f, 950f, 600))
        assertTrue(detector.onMove(80f, 900f, 610))
        detector.onUp(80f, 900f, 620)
        assertEquals(listOf("longPress", "start", "to(0,-50)", "to(-20,-100)", "to(-20,-100)", "end"), events)
    }

    @Test fun holdAndRelease_showsControls() {
        detector.onDown(100f, 1000f, 0)
        detector.onMove(104f, 1004f, 300)
        detector.onLongPressTimeout(500)
        detector.onUp(104f, 1004f, 700)
        assertEquals(listOf("longPress", "controls"), events)
    }

    @Test fun lateTimer_holdIsDetectedFromEventTime() {
        detector.onDown(100f, 1000f, 0)
        detector.onMove(101f, 1001f, 650) // the timer has not fired yet, but 650 ms have passed
        detector.onUp(101f, 1001f, 660)
        assertEquals(listOf("longPress", "controls"), events)
    }

    @Test fun timeoutAfterDragStarted_isIgnored() {
        detector.onDown(100f, 1000f, 0)
        detector.onMove(100f, 900f, 50)
        detector.onLongPressTimeout(500)
        detector.onUp(100f, 900f, 700)
        assertEquals(listOf("start", "to(0,-100)", "to(0,-100)", "end"), events)
    }

    @Test fun releasePointBeyondLastMove_isApplied() {
        detector.onDown(100f, 1000f, 0)
        detector.onMove(100f, 950f, 8)
        detector.onUp(90f, 900f, 16) // the last MOVE was short of where the finger lifted
        assertEquals(listOf("start", "to(0,-50)", "to(-10,-100)", "end"), events)
    }

    @Test fun cancelWhileDragging_endsDrag() {
        detector.onDown(100f, 1000f, 0)
        detector.onMove(100f, 900f, 50)
        detector.onCancel()
        assertEquals(listOf("start", "to(0,-100)", "end"), events)
        assertFalse(detector.isDragging)
    }

    @Test fun cancelWhileHeld_showsNothing() {
        detector.onDown(100f, 1000f, 0)
        detector.onLongPressTimeout(500)
        detector.onCancel()
        assertEquals(listOf("longPress"), events)
    }

    @Test fun eventsWithoutDown_areIgnored() {
        assertFalse(detector.onMove(10f, 10f, 0))
        detector.onUp(10f, 10f, 5)
        detector.onLongPressTimeout(600)
        assertEquals(emptyList<String>(), events)
    }
}
