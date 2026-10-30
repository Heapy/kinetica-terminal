@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.math.ceil
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsSelectionTest {
    @Test fun repeatClicksUseActualPressesConfiguredTimeAndCellDistanceInsteadOfNativeHints() = withView { session, view, _ ->
        session.write("alpha beta")
        assertFailsWith<IllegalArgumentException> { view.clickRepeatIntervalMillis = -1 }
        assertFailsWith<IllegalArgumentException> { view.clickRepeatIntervalMillis = 0x1_0000_0000L }
        view.clickRepeatIntervalMillis = 100
        fun click(x: Double, millis: Int) {
            val point = view.bounds.useContents { NSMakePoint(size.width / session.columns * x, size.height / session.rows * 0.5) }
            fun event(type: NSEventType) = assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(point, toView = null),
                0uL, millis / 1000.0, view.window!!.windowNumber, null, 0, 99, 1.0f))
            view.mouseDown(event(NSEventTypeLeftMouseDown)); view.mouseUp(event(NSEventTypeLeftMouseUp))
        }
        click(1.2, 0); assertNull(view.accessibilitySelectedText(), "Native clickCount=99 does not fabricate a multi-click")
        click(1.2, 100); assertEquals("alpha", view.accessibilitySelectedText())
        click(1.2, 201); assertNull(view.accessibilitySelectedText(), "Configured interval, not system interval")
        click(2.3, 202); assertNull(view.accessibilitySelectedText(), "More than a cell from the first press")
        click(2.3, 203); assertEquals("alpha", view.accessibilitySelectedText())
        click(2.3, 204); assertEquals("alpha beta", view.accessibilitySelectedText())
        session.write("\u001b[?1049hother")
        click(2.3, 205); assertNull(view.accessibilitySelectedText())
    }

    @Test fun shiftClickContinuesSelectionAfterSystemRepeatIntervalAndRespectsCapture() = withView { session, view, _ ->
        session.write("alpha beta gamma")
        var time = 1.0
        fun mouse(type: NSEventType, x: Double, shift: Boolean = false, count: Long = 1) {
            val point = view.bounds.useContents { NSMakePoint(size.width / session.columns * x, size.height / session.rows * 0.5) }
            val event = assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(point, toView = null),
                if (shift) NSEventModifierFlagShift else 0uL, time, view.window!!.windowNumber, null, 0, count, 1.0f))
            when (type) {
                NSEventTypeLeftMouseDown -> view.mouseDown(event)
                NSEventTypeLeftMouseDragged -> view.mouseDragged(event)
                else -> view.mouseUp(event)
            }
        }
        repeat(2) { mouse(NSEventTypeLeftMouseDown, 1.2); mouse(NSEventTypeLeftMouseUp, 1.2) }
        assertEquals("alpha", view.accessibilitySelectedText())
        time += NSEvent.doubleClickInterval + 0.1
        mouse(NSEventTypeLeftMouseDown, 7.2, shift = true); mouse(NSEventTypeLeftMouseUp, 7.2, shift = true)
        assertEquals("alpha beta", view.accessibilitySelectedText())
        time += 0.01
        mouse(NSEventTypeLeftMouseDown, 12.2, shift = true, count = 2); mouse(NSEventTypeLeftMouseUp, 12.2, shift = true, count = 2)
        assertEquals("alpha beta gamma", view.accessibilitySelectedText(), "Continuation does not replace the origin or restart the interval")
        time += 1.0
        repeat(2) { mouse(NSEventTypeLeftMouseDown, 7.2); mouse(NSEventTypeLeftMouseUp, 7.2) }
        time += NSEvent.doubleClickInterval / 2
        mouse(NSEventTypeLeftMouseDown, 7.3, shift = true); mouse(NSEventTypeLeftMouseUp, 7.3, shift = true)
        assertEquals("alpha beta gamma", view.accessibilitySelectedText(), "A quick nearby Shift click becomes the third press")
        val output = mutableListOf<String>()
        val subscription = session.onInput { output += it.toByteArray().decodeToString() }
        try {
            time += 1.0; session.write("\u001b[?1000h\u001b[?1006h")
            mouse(NSEventTypeLeftMouseDown, 1.2, shift = true); mouse(NSEventTypeLeftMouseUp, 1.2, shift = true)
            assertEquals("alpha beta gamma", view.accessibilitySelectedText()); assertTrue(output.isEmpty())
            session.write("\u001b[>1s")
            mouse(NSEventTypeLeftMouseDown, 1.2, shift = true); mouse(NSEventTypeLeftMouseUp, 1.2, shift = true)
            assertEquals(listOf("\u001b[<4;2;1M", "\u001b[<4;2;1m"), output)
        } finally { subscription.dispose() }
    }

    @Test fun optionDragSelectsRectanglesAndModifiersCanChangeMidGesture() = withView { session, view, _ ->
        session.write("0123456\r\nabcdefg\r\nABCDEFG")
        fun mouse(type: NSEventType, x: Double, y: Int, flags: ULong = NSEventModifierFlagOption) {
            val point = view.bounds.useContents { NSMakePoint(size.width / session.columns * x, size.height / session.rows * (y + 0.5)) }
            val event = assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(point, toView = null),
                flags, 0.0, view.window!!.windowNumber, null, 0, 1, 1.0f))
            when (type) {
                NSEventTypeLeftMouseDown -> view.mouseDown(event)
                NSEventTypeLeftMouseDragged -> view.mouseDragged(event)
                else -> view.mouseUp(event)
            }
        }
        mouse(NSEventTypeLeftMouseDown, 1.2, 0)
        mouse(NSEventTypeLeftMouseDragged, 3.8, 2)
        assertEquals("123\nbcd\nBCD", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 3.8, 2, 0uL)
        assertEquals("123456\nabcdefg\nABCD", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 3.8, 2)
        mouse(NSEventTypeLeftMouseUp, 3.8, 2)
        assertEquals("123\nbcd\nBCD", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDown, 3.8, 2)
        mouse(NSEventTypeLeftMouseDragged, 1.2, 0)
        mouse(NSEventTypeLeftMouseUp, 1.2, 0)
        assertEquals("123\nbcd\nBCD", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDown, 2.2, 0, 0uL)
        mouse(NSEventTypeLeftMouseDragged, 2.8, 0, 0uL)
        assertEquals("2", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 2.3, 0, 0uL)
        mouse(NSEventTypeLeftMouseUp, 2.3, 0, 0uL)
        assertNull(view.accessibilitySelectedText())
    }

    @Test fun commandTripleClickSelectsAndDragsWholeCommandOutput() = withView { session, view, _ ->
        session.write("\u001b]133;A\u0007$ \u001b]133;B\u0007one\u001b]133;C\u0007\r\nfirst\r\nsecond\r\n" +
            "\u001b]133;A\u0007$ \u001b]133;B\u0007two\u001b]133;C\u0007\r\nthird")
        fun event(type: NSEventType, y: Int, flags: ULong = NSEventModifierFlagCommand) {
            val point = view.bounds.useContents { NSMakePoint(size.width / session.columns * 1.5, size.height / session.rows * (y + 0.5)) }
            val native = assertNotNull(NSEvent.mouseEventWithType(type, view.convertPoint(point, toView = null),
                flags, 0.0, view.window!!.windowNumber, null, 0, 3, 1.0f))
            when (type) {
                NSEventTypeLeftMouseDown -> {
                    repeat(2) { view.mouseDown(native); view.mouseUp(native) }
                    view.mouseDown(native)
                }
                NSEventTypeLeftMouseDragged -> view.mouseDragged(native)
                else -> view.mouseUp(native)
            }
        }
        event(NSEventTypeLeftMouseDown, 1)
        assertEquals("first\nsecond", view.accessibilitySelectedText())
        event(NSEventTypeLeftMouseDragged, 4)
        assertEquals("first\nsecond\n$ two\nthird", view.accessibilitySelectedText())
        event(NSEventTypeLeftMouseDragged, 3); event(NSEventTypeLeftMouseUp, 3)
        assertEquals("first\nsecond", view.accessibilitySelectedText())
        event(NSEventTypeLeftMouseDown, 1, NSEventModifierFlagControl)
        event(NSEventTypeLeftMouseUp, 1, NSEventModifierFlagControl)
        assertEquals("first", view.accessibilitySelectedText(), "Darwin uses Command, not Control, for output granularity")
    }

    private class PressureEvent(private val pressureStage: Long) : NSEvent() {
        override fun stage(): Long = pressureStage
    }

    @Test fun nativeForceClickSelectsOriginalWordAndConsumesTheHeldGesture() = withView { session, view, mouse ->
        session.write("alpha-beta gamma")
        view.pressureChangeWithEvent(PressureEvent(2))
        assertNull(view.accessibilitySelectedText(), "Pressure without a held press does nothing")
        mouse(NSEventTypeLeftMouseDown, 2, 0, 1)
        mouse(NSEventTypeLeftMouseDragged, 14, 0, 1)
        val dragged = view.accessibilitySelectedText()
        view.pressureChangeWithEvent(PressureEvent(1)); assertEquals(dragged, view.accessibilitySelectedText())
        view.pressureChangeWithEvent(PressureEvent(2)); assertEquals("alpha-beta", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 18, 1, 1)
        view.pressureChangeWithEvent(PressureEvent(2))
        mouse(NSEventTypeLeftMouseUp, 18, 1, 1)
        assertEquals("alpha-beta", view.accessibilitySelectedText())
        // AppKit may continue its native click count, while Ghostty clears its selection sequence.
        mouse(NSEventTypeLeftMouseDown, 2, 0, 2)
        mouse(NSEventTypeLeftMouseUp, 2, 0, 2)
        assertNull(view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDown, 2, 0, 3)
        mouse(NSEventTypeLeftMouseUp, 2, 0, 3)
        assertEquals("alpha-beta", view.accessibilitySelectedText())
    }

    @Test fun nativeForceClickStopsTimersAndNeverSynthesizesPromptOrMouseReports() = withView { session, view, mouse ->
        val replies = StringBuilder()
        val subscription = session.onInput { replies.append(it.toByteArray().decodeToString()) }
        try {
            repeat(80) { session.write("line-$it\r\n") }
            mouse(NSEventTypeLeftMouseDown, 2, 2, 1)
            mouse(NSEventTypeLeftMouseDragged, 4, -2, 1)
            view.pressureChangeWithEvent(PressureEvent(2))
            val selected = view.accessibilitySelectedText(); val visible = view.accessibilityValue()
            pump(180); assertEquals(visible, view.accessibilityValue()); assertEquals(selected, view.accessibilitySelectedText())
            mouse(NSEventTypeLeftMouseUp, 4, -2, 1)
            session.reset(); session.write("\u001b]133;A;click_events=1\u0007$ \u001b]133;B\u0007hello")
            mouse(NSEventTypeLeftMouseDown, 4, 0, 1)
            view.pressureChangeWithEvent(PressureEvent(2))
            mouse(NSEventTypeLeftMouseUp, 4, 0, 1)
            assertEquals("hello", view.accessibilitySelectedText()); assertEquals("", replies.toString())
            session.write("\u001b[?1000h\u001b[?1006h")
            mouse(NSEventTypeLeftMouseDown, 4, 0, 1)
            view.pressureChangeWithEvent(PressureEvent(2))
            mouse(NSEventTypeLeftMouseUp, 4, 0, 1)
            assertEquals("\u001b[<0;5;1M\u001b[<0;5;1m", replies.toString())
            assertNull(view.accessibilitySelectedText())
        } finally { subscription.dispose() }
    }

    @Test fun nativeDoubleClicksRecognizePathsWithSpacesAndOtherSchemes() = withView { session, view, mouse ->
        for (link in listOf("mailto:me@example.org", "../test folder/file.txt", "ssh://example.org/a", "src/config/url.zig")) {
            session.reset(); session.write("($link)")
            mouse(NSEventTypeLeftMouseDown, 3, 0, 2)
            mouse(NSEventTypeLeftMouseUp, 3, 0, 2)
            assertEquals(link, view.accessibilitySelectedText())
        }
    }

    @Test fun nativeWordBoundariesGroupDelimitersAndHonorHostSettings() = withView { session, view, mouse ->
        session.write("one  (two);three")
        mouse(NSEventTypeLeftMouseDown, 3, 0, 2)
        mouse(NSEventTypeLeftMouseUp, 3, 0, 2)
        assertEquals("  (", view.accessibilitySelectedText())
        session.selectionWordChars = " "
        mouse(NSEventTypeLeftMouseDown, 7, 0, 2)
        mouse(NSEventTypeLeftMouseUp, 7, 0, 2)
        assertEquals("(two);three", view.accessibilitySelectedText())
    }

    @Test fun nativeDoubleClickCopiesTheVisibleUrlAndDragResumesWords() = withView { session, view, mouse ->
        val url = "https://e.test/a(b);q=1"
        session.write("[$url].")
        mouse(NSEventTypeLeftMouseDown, 3, 0, 2)
        assertEquals(url, view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseUp, 3, 0, 2)
        assertEquals(url, view.accessibilitySelectedText(), "Release must retain the complete URL")
        mouse(NSEventTypeLeftMouseDown, 3, 0, 2)
        mouse(NSEventTypeLeftMouseDragged, 3, 0, 2)
        mouse(NSEventTypeLeftMouseUp, 3, 0, 2)
        assertEquals("https", view.accessibilitySelectedText())
    }

    @Test fun nativeTripleClicksStopAtSemanticBoundariesAndTrimEdges() = withView { session, view, mouse ->
        session.write("\u001b]133;A\u0007 p \u001b]133;B\u0007 cmd \u001b]133;C\u0007 out ")
        mouse(NSEventTypeLeftMouseDown, 4, 0, 3)
        assertEquals("cmd", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 10, 0, 3)
        assertEquals("cmd  out", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDragged, 1, 0, 3)
        mouse(NSEventTypeLeftMouseUp, 1, 0, 3)
        assertEquals("p  cmd", view.accessibilitySelectedText())
    }

    @Test fun nativeLegacyPromptClicksUseNegotiatedCursorKeys() = withView { session, _, mouse ->
        val replies = StringBuilder()
        val subscription = session.onInput { replies.append(it.toByteArray().decodeToString()) }
        try {
            for (mode in listOf("line", "m", "v", "w")) {
                session.reset()
                session.write("\u001b]133;A;cl=$mode\u0007$ \u001b]133;B\u0007hello")
                replies.clear()
                mouse(NSEventTypeLeftMouseDown, 3, 0, 1)
                assertTrue(replies.isEmpty())
                mouse(NSEventTypeLeftMouseUp, 3, 0, 1)
                assertEquals("\u001b[D".repeat(4), replies.toString())
                session.write("\u001b[1;3H\u001b[?1h")
                replies.clear()
                mouse(NSEventTypeLeftMouseDown, 11, 0, 1)
                mouse(NSEventTypeLeftMouseUp, 11, 0, 1)
                assertEquals("\u001bOC".repeat(5), replies.toString())
                replies.clear(); session.cursorClickToMove = false
                mouse(NSEventTypeLeftMouseDown, 11, 0, 1)
                mouse(NSEventTypeLeftMouseUp, 11, 0, 1)
                assertTrue(replies.isEmpty())
                session.cursorClickToMove = true
            }
        } finally { subscription.dispose() }
    }

    @Test fun nativePromptClicksReportOnReleaseButDragsAndTrackedPressesDoNot() = withView { session, _, mouse ->
        val replies = mutableListOf<String>()
        val subscription = session.onInput { replies += it.toByteArray().decodeToString() }
        try {
            for (mode in 1..2) {
                session.reset()
                session.write("output\r\n\u001b]133;A;click_events=$mode\u0007$ \u001b]133;B\u0007abc")
                replies.clear()
                mouse(NSEventTypeLeftMouseDown, 4, 1, 1)
                assertTrue(replies.isEmpty())
                mouse(NSEventTypeLeftMouseUp, 4, 1, 1)
                assertEquals(listOf("\u001b[<0;5;${if (mode == 1) 2 else 1}M"), replies)
                replies.clear()
                mouse(NSEventTypeLeftMouseDown, 4, 1, 1)
                mouse(NSEventTypeLeftMouseDragged, 5, 1, 1)
                mouse(NSEventTypeLeftMouseDragged, 4, 1, 1)
                mouse(NSEventTypeLeftMouseUp, 4, 1, 1)
                mouse(NSEventTypeLeftMouseDown, 4, 1, 2)
                mouse(NSEventTypeLeftMouseUp, 4, 1, 2)
                assertTrue(replies.isEmpty())
                session.write("\u001b[?1000h\u001b[?1006h")
                mouse(NSEventTypeLeftMouseDown, 4, 1, 1)
                mouse(NSEventTypeLeftMouseUp, 4, 1, 1)
                assertEquals(listOf("\u001b[<0;5;2M", "\u001b[<0;5;2m"), replies)
            }
        } finally { subscription.dispose() }
    }

    @Test fun nativeClickCountsSelectWordsLogicalLinesAndWideGlyphs() = withView { session, view, mouse ->
        session.write("alpha-beta gamma\r\nnext")
        mouse(NSEventTypeLeftMouseDown, 2, 0, 2)
        mouse(NSEventTypeLeftMouseUp, 2, 0, 2)
        assertEquals("alpha-beta", view.accessibilitySelectedText())
        session.write("\u001b[3;1Hunrelated")
        assertEquals("alpha-beta", view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDown, 2, 0, 3)
        mouse(NSEventTypeLeftMouseDragged, 1, 1, 3)
        mouse(NSEventTypeLeftMouseUp, 1, 1, 3)
        assertEquals("alpha-beta gamma\nnext", view.accessibilitySelectedText())
        session.reset(); session.write("abc界é xyz")
        mouse(NSEventTypeLeftMouseDown, 4, 0, 1)
        mouse(NSEventTypeLeftMouseDragged, 6, 0, 1)
        mouse(NSEventTypeLeftMouseUp, 6, 0, 1)
        assertEquals("界é", view.accessibilitySelectedText())
        // A committed IME input clears the old selection even when the peer sends no output.
        val sent = mutableListOf<String>()
        val subscription = session.onInput { sent.add(it.toByteArray().decodeToString()) }
        try {
            view.insertText("日", NSMakeRange(NSNotFound.toULong(), 0u))
            assertEquals(listOf("日"), sent)
            assertNull(view.accessibilitySelectedText())
        } finally { subscription.dispose() }
        session.reset(); session.write("x".repeat(30) + "\r\nend")
        mouse(NSEventTypeLeftMouseDown, 2, 1, 3)
        mouse(NSEventTypeLeftMouseUp, 2, 1, 3)
        assertEquals("x".repeat(30), view.accessibilitySelectedText())
    }

    @Test fun nativeDragAutoscrollStopsOnReleaseFocusLossAndDisposal() = withView { session, view, mouse ->
        repeat(80) { session.write("line-$it\r\n") }
        mouse(NSEventTypeLeftMouseDown, 4, 2, 1)
        mouse(NSEventTypeLeftMouseDragged, 4, -2, 1)
        await { view.accessibilitySelectedText().orEmpty().contains("line-70") }
        mouse(NSEventTypeLeftMouseUp, 4, -2, 1)
        val released = view.accessibilityValue()
        val text = view.accessibilitySelectedText()
        pump(180)
        assertEquals(released, view.accessibilityValue(), "Mouse release cancels the repeating drag timer")
        assertEquals(text, view.accessibilitySelectedText())
        session.write("more\r\n")
        assertEquals(released, view.accessibilityValue(), "Output preserves the scrolled top row")
        assertEquals(text, view.accessibilitySelectedText())
        mouse(NSEventTypeLeftMouseDown, 4, 2, 1)
        mouse(NSEventTypeLeftMouseDragged, 4, -2, 1)
        val before = view.accessibilityValue()
        await { view.accessibilityValue() != before }
        view.resignFirstResponder()
        val blurred = view.accessibilityValue()
        pump(180)
        assertEquals(blurred, view.accessibilityValue())
        mouse(NSEventTypeLeftMouseDown, 4, 2, 1)
        mouse(NSEventTypeLeftMouseDragged, 4, -2, 1)
        view.dispose()
        val disposed = view.accessibilityValue()
        pump(180)
        assertEquals(disposed, view.accessibilityValue())
        assertNull(view.accessibilitySelectedText())
    }

    private fun withView(block: (TerminalSession, AppKitTerminalView, (NSEventType, Int, Int, Int) -> Unit) -> Unit) {
        NSApplication.sharedApplication()
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val width = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val height = ceil(font.ascender - font.descender + font.leading + 2.0)
        val session = TerminalSession(20, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        view.forceClickLookupEnabled = { false }
        val window = NSWindow(NSMakeRect(0.0, 0.0, ceil(width * 20), height * 5), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        view.setFrameSize(NSMakeSize(ceil(width * 20), height * 5))
        assertEquals(20, session.columns); assertEquals(5, session.rows)
        var clickTime = 0.0
        var lastCount = 0
        var lastScreen = session.screenEpoch
        var lastPoint = -1 to -1
        val mouse: (NSEventType, Int, Int, Int) -> Unit = { type, x, y, count ->
            fun event(type: NSEventType) = requireNotNull(NSEvent.mouseEventWithType(type,
                view.convertPoint(NSMakePoint((x + 0.5) * width, (y + 0.5) * height), toView = null),
                0u, clickTime, window.windowNumber, null, 0, 1, 1.0f))
            if (type == NSEventTypeLeftMouseDown) {
                // Emit the missing presses; host click-count hints are intentionally always one.
                if (count == 1 || count <= lastCount || lastScreen != session.screenEpoch || lastPoint != (x to y)) {
                    clickTime += 1.0; lastCount = 0
                }
                repeat((count - lastCount - 1).coerceAtLeast(0)) {
                    clickTime += 0.001; view.mouseDown(event(NSEventTypeLeftMouseDown)); view.mouseUp(event(NSEventTypeLeftMouseUp))
                }
                clickTime += 0.001; lastCount = count; lastScreen = session.screenEpoch; lastPoint = x to y
            }
            when (type) {
                NSEventTypeLeftMouseDown -> view.mouseDown(event(type))
                NSEventTypeLeftMouseDragged -> view.mouseDragged(event(type))
                NSEventTypeLeftMouseUp -> view.mouseUp(event(type))
                else -> error("Unexpected selection event")
            }
        }
        try { block(session, view, mouse) } finally { view.dispose(); window.close() }
    }

    private fun pump(millis: Long) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(millis / 1000.0))
    }
    private fun await(condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition() && start.elapsedNow().inWholeSeconds < 5) pump(10)
        assertTrue(condition(), "Selection update timed out")
    }
}
