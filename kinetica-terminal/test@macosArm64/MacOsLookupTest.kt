@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.math.ceil
import kotlin.test.*

class MacOsLookupTest {
    private class PressureEvent(private val value: Long, private val point: CValue<NSPoint>) : NSEvent() {
        override fun stage(): Long = value
        override fun locationInWindow(): CValue<NSPoint> = point
    }
    private class View {
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val width = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val height = ceil(font.ascender - font.descender + font.leading + 2.0)
        val session = TerminalSession(30, 4)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, ceil(width * 30), height * 4), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        data class Lookup(val text: NSAttributedString, val point: CValue<NSPoint>)
        val lookups = mutableListOf<Lookup>()
        init {
            window.setReleasedWhenClosed(false); window.contentView = view
            view.setFrameSize(NSMakeSize(ceil(width * 30), height * 4))
            view.forceClickLookupEnabled = { false }
            view.showLookup = { text, point -> lookups += Lookup(text, point) }
        }
        fun point(x: Int, y: Int = 0): CValue<NSPoint> = view.convertPoint(NSMakePoint((x + 0.5) * width, (y + 0.5) * height), toView = null)
        fun event(x: Int, type: NSEventType = NSEventTypeLeftMouseDown, count: Long = 1): NSEvent =
            assertNotNull(NSEvent.mouseEventWithType(type, point(x), 0u, 0.0, window.windowNumber, null, 0, count, 1.0f))
        fun close() { view.dispose(); window.close() }
    }

    @Test fun lookupReadsPointerWordAndFontWithoutReplacingTheSelection() {
        NSApplication.sharedApplication()
        val h = View()
        try {
            h.session.write("alpha-beta gamma")
            h.view.mouseDown(h.event(2)); h.view.mouseUp(h.event(2, NSEventTypeLeftMouseUp))
            h.view.mouseDown(h.event(2, count = 2)); h.view.mouseUp(h.event(2, NSEventTypeLeftMouseUp, 2))
            h.view.quickLookWithEvent(h.event(12))
            val lookup = h.lookups.single()
            assertEquals("gamma", lookup.text.string)
            assertEquals(h.font.fontName, (lookup.text.attribute(NSFontAttributeName, 0u, null) as NSFont).fontName)
            lookup.point.useContents {
                assertEquals(11 * h.width, x, 0.001)
                assertEquals(NSLayoutManager().defaultBaselineOffsetForFont(h.font), y, 0.001)
            }
            assertEquals("alpha-beta", h.view.accessibilitySelectedText())
            h.session.selectionWordChars = "- "
            h.session.write("\u001b[?2026h\u001b[Hchanged")
            h.view.quickLookWithEvent(h.event(7))
            assertEquals("beta", h.lookups.last().text.string)
            assertEquals("alpha-beta", h.view.accessibilitySelectedText())
        } finally { h.close() }
    }

    @Test fun forceClickLookupUsesSystemPreferenceAndRunsOncePerHeldGesture() {
        NSApplication.sharedApplication()
        val h = View()
        try {
            h.session.write("alpha beta")
            h.view.forceClickLookupEnabled = { true }
            h.view.mouseDown(h.event(1))
            h.view.pressureChangeWithEvent(PressureEvent(1, h.point(7)))
            assertTrue(h.lookups.isEmpty())
            h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            assertEquals("beta", h.lookups.single().text.string)
            assertEquals("alpha", h.view.accessibilitySelectedText(), "Selection uses the original press; lookup uses the pointer")
            h.view.pressureChangeWithEvent(PressureEvent(1, h.point(7)))
            h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            assertEquals(1, h.lookups.size)
            h.view.mouseUp(h.event(7, NSEventTypeLeftMouseUp))
            h.view.forceClickLookupEnabled = { false }
            h.view.mouseDown(h.event(1)); h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            h.view.forceClickLookupEnabled = { true }
            h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            assertEquals(1, h.lookups.size, "Enabling the preference does not replay a completed pressure transition")
            h.view.mouseUp(h.event(7, NSEventTypeLeftMouseUp))
            h.session.write("\u001b[?1000h")
            h.view.mouseDown(h.event(1)); h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            assertEquals(2, h.lookups.size, "Native lookup remains independent of application mouse reporting")
            h.view.dispose(); h.view.quickLookWithEvent(h.event(7))
            h.view.pressureChangeWithEvent(PressureEvent(2, h.point(7)))
            assertEquals(2, h.lookups.size)
        } finally { h.close() }
    }

    @Test fun definitionRectUsesSelectionBaselineAndImeKeepsItsCursorAnchor() {
        NSApplication.sharedApplication()
        val h = View()
        fun expected(x: Double, y: Double, width: Double): CValue<NSRect> = h.window.convertRectToScreen(
            h.view.convertRect(NSMakeRect(x, y, width, h.height), toView = null))
        fun check(actual: CValue<NSRect>, wanted: CValue<NSRect>) {
            val a = actual.useContents { listOf(origin.x, origin.y, size.width, size.height) }
            val b = wanted.useContents { listOf(origin.x, origin.y, size.width, size.height) }
            a.zip(b).forEach { (x, y) -> assertEquals(y, x, 0.001) }
        }
        try {
            h.session.write("alpha beta")
            h.view.mouseDown(h.event(7)); h.view.mouseUp(h.event(7, NSEventTypeLeftMouseUp))
            h.view.mouseDown(h.event(7, count = 2)); h.view.mouseUp(h.event(7, NSEventTypeLeftMouseUp, 2))
            val baseline = h.window.convertPointToScreen(h.view.convertPoint(NSMakePoint(6 * h.width - 2,
                NSLayoutManager().defaultBaselineOffsetForFont(h.font) + 2), toView = null))
            val definitionRect = baseline.useContents { NSMakeRect(x, y, h.width, h.height) }
            check(h.view.firstRectForCharacterRange(NSMakeRange(0u, 4u), null), definitionRect)
            check(h.view.firstRectForCharacterRange(NSMakeRange(0u, 0u), null), expected(10.5 * h.width, 0.0, 0.0))
            h.view.setMarkedText("日本", NSMakeRange(0u, 2u), NSMakeRange(NSNotFound.toULong(), 0u))
            check(h.view.firstRectForCharacterRange(NSMakeRange(0u, 2u), null),
                expected(10.5 * h.width, 0.0, 4 * h.width * h.window.backingScaleFactor))
        } finally { h.close() }
    }

    @Test fun imeGeometryUsesScalarWidthClippingCollapsedRangeAndLiveCursor() {
        NSApplication.sharedApplication()
        val h = View()
        fun local(range: CValue<NSRange>): List<Double> {
            val screen = h.view.firstRectForCharacterRange(range, null)
            val rect = h.view.convertRect(h.window.convertRectFromScreen(screen), fromView = null)
            return rect.useContents { listOf(origin.x, origin.y, size.width, size.height) }
        }
        fun check(range: CValue<NSRange>, x: Double, y: Double, width: Double) {
            local(range).zip(listOf(x, y, width, h.height)).forEach { (actual, expected) -> assertEquals(expected, actual, 0.001) }
        }
        try {
            h.session.write("\u001b[2;4H")
            check(NSMakeRange(0u, 0u), 3.5 * h.width, h.height, 0.0)
            // A + combining acute + CJK + surrogate-pair emoji = 1 + 0 + 2 + 2 cells.
            h.view.setMarkedText("A\u0301日😀", NSMakeRange(0u, 5u), NSMakeRange(NSNotFound.toULong(), 0u))
            check(NSMakeRange(0u, 5u), 3.5 * h.width, h.height, 5 * h.width * h.window.backingScaleFactor)
            check(NSMakeRange(2u, 0u), 5.5 * h.width, h.height, 0.0)
            check(NSMakeRange(NSNotFound.toULong(), 0u), 3.5 * h.width, h.height, 0.0)

            h.session.write("\u001b[?2026h\u001b[3;28H")
            val remaining = h.view.bounds.useContents { size.width } - 28 * h.width
            check(NSMakeRange(0u, 5u), 27.5 * h.width, 2 * h.height, remaining * h.window.backingScaleFactor)
            check(NSMakeRange(1u, 0u), 28.5 * h.width, 2 * h.height, 0.0)
            h.session.write("\u001b[?2026l")
            h.view.unmarkText()
            check(NSMakeRange(2u, 0u), 27.5 * h.width, 2 * h.height, 0.0)
            h.view.setMarkedText("\u0301\u200d", NSMakeRange(0u, 2u), NSMakeRange(NSNotFound.toULong(), 0u))
            check(NSMakeRange(0u, 2u), 27.5 * h.width, 2 * h.height, 0.0)
        } finally { h.close() }
    }
}
