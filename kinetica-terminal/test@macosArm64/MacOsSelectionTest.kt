@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.math.ceil
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsSelectionTest {
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
        assertEquals("alpha-beta gamma\nnext\n", view.accessibilitySelectedText())
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
            assertEquals("", view.accessibilitySelectedText())
        } finally { subscription.dispose() }
        session.reset(); session.write("x".repeat(30) + "\r\nend")
        mouse(NSEventTypeLeftMouseDown, 2, 1, 3)
        mouse(NSEventTypeLeftMouseUp, 2, 1, 3)
        assertEquals("x".repeat(30) + "\n", view.accessibilitySelectedText())
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
        assertEquals("", view.accessibilitySelectedText())
    }

    private fun withView(block: (TerminalSession, AppKitTerminalView, (NSEventType, Int, Int, Int) -> Unit) -> Unit) {
        NSApplication.sharedApplication()
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val width = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val height = ceil(font.ascender - font.descender + font.leading + 2.0)
        val session = TerminalSession(20, 5)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, ceil(width * 20), height * 5), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        view.setFrameSize(NSMakeSize(ceil(width * 20), height * 5))
        assertEquals(20, session.columns); assertEquals(5, session.rows)
        val mouse: (NSEventType, Int, Int, Int) -> Unit = { type, x, y, count ->
            val event = requireNotNull(NSEvent.mouseEventWithType(type,
                view.convertPoint(NSMakePoint((x + 0.5) * width, (y + 0.5) * height), toView = null),
                0u, 0.0, window.windowNumber, null, 0, count.toLong(), 1.0f))
            when (type) {
                NSEventTypeLeftMouseDown -> view.mouseDown(event)
                NSEventTypeLeftMouseDragged -> view.mouseDragged(event)
                NSEventTypeLeftMouseUp -> view.mouseUp(event)
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
