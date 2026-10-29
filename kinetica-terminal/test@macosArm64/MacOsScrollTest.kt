@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.*
import platform.Foundation.*
import kotlin.math.ceil
import kotlin.test.*

class MacOsScrollTest {
    @Test fun tuiScrollStopsWithTheFingersAndReversesWithoutMomentumReports() = withView { session, view, input, height ->
        session.write("\u001b[?1000h\u001b[?1006h")
        wheel(view, height, phase = kCGScrollPhaseBegan.toLong())
        assertEquals(listOf(64, 64), input.map(::button))
        input.clear()
        // A flick can generate many events after the fingers leave the trackpad.
        wheel(view, height, momentum = 1)
        repeat(100) { wheel(view, height, momentum = 2) }
        wheel(view, 0, momentum = 3)
        assertTrue(input.isEmpty(), "Momentum must not build an uncancellable TUI input backlog")
        wheel(view, 0, phase = kCGScrollPhaseMayBegin.toLong())
        wheel(view, -height, phase = kCGScrollPhaseBegan.toLong())
        assertEquals(listOf(65, 65), input.map(::button))
        // Reverse within the same gesture as well, discarding the fractional remainder.
        input.clear()
        wheel(view, -(height / 2 - 1), phase = kCGScrollPhaseChanged.toLong())
        assertTrue(input.isEmpty())
        wheel(view, height, phase = kCGScrollPhaseChanged.toLong())
        assertEquals(listOf(64, 64), input.map(::button))
        input.clear()
        wheel(view, 1, precise = false)
        assertEquals(64, button(input.single()), "An ordinary wheel still sends one report per tick")
    }

    @Test fun preciseTuiScrollAccumulatesSmallMovementsAndBoundsLargeBursts() = withView { session, view, input, height ->
        session.write("\u001b[?1000h\u001b[?1006h")
        wheel(view, 0, phase = kCGScrollPhaseBegan.toLong())
        repeat(height / 2 - 1) { wheel(view, 1, phase = kCGScrollPhaseChanged.toLong()) }
        assertTrue(input.isEmpty(), "Sub-row input must accumulate instead of rounding every event up")
        repeat(height + 1 - (height / 2 - 1)) { wheel(view, 1, phase = kCGScrollPhaseChanged.toLong()) }
        assertEquals(listOf(64, 64), input.map(::button), "Small movements accumulate at the faster TUI rate")
        input.clear()
        wheel(view, height * 100, phase = kCGScrollPhaseChanged.toLong())
        assertEquals(List(64) { 64 }, input.map(::button), "A large event must retain the existing report limit")
        input.clear()
        wheel(view, 0, phase = kCGScrollPhaseChanged.toLong())
        assertTrue(input.isEmpty())
        wheel(view, -height, phase = kCGScrollPhaseChanged.toLong())
        assertEquals(listOf(65, 65), input.map(::button))
    }

    @Test fun alternateScreenArrowScrollingDoesNotForwardMomentum() = withView { session, view, input, height ->
        session.write("\u001b[?1049h")
        wheel(view, height, phase = kCGScrollPhaseBegan.toLong())
        assertEquals(listOf("\u001b[A", "\u001b[A"), input)
        input.clear()
        wheel(view, height, momentum = 1)
        wheel(view, height, momentum = 2)
        wheel(view, 0, momentum = 3)
        assertTrue(input.isEmpty())
        wheel(view, -height, phase = kCGScrollPhaseBegan.toLong())
        assertEquals(listOf("\u001b[B", "\u001b[B"), input)
    }

    @Test fun localHistoryKeepsMomentumIncludingShiftOverride() = withView { session, view, input, height ->
        repeat(100) { session.write("line-$it\r\n") }
        wheel(view, height, phase = kCGScrollPhaseBegan.toLong())
        wheel(view, height, momentum = 1)
        wheel(view, height, momentum = 2)
        wheel(view, 0, momentum = 3)
        assertEquals(3, view.scrollOffset)
        wheel(view, -height, phase = kCGScrollPhaseBegan.toLong())
        assertEquals(2, view.scrollOffset)
        view.scrollTo(0)
        session.write("\u001b[?1000h")
        wheel(view, height, momentum = 1, shift = true)
        assertEquals(1, view.scrollOffset)
        assertTrue(input.isEmpty())
    }

    @Test fun touchingAndCancellingTheTrackpadClearsPartialMovement() = withView { _, view, _, height ->
        repeat(100) { view.session.write("line-$it\r\n") }
        wheel(view, height - 1, phase = kCGScrollPhaseBegan.toLong())
        wheel(view, 0, phase = kCGScrollPhaseMayBegin.toLong())
        wheel(view, 1, phase = kCGScrollPhaseChanged.toLong())
        assertEquals(0, view.scrollOffset)
        wheel(view, height - 2, phase = kCGScrollPhaseChanged.toLong())
        wheel(view, 0, phase = kCGScrollPhaseCancelled.toLong())
        wheel(view, 1, phase = kCGScrollPhaseChanged.toLong())
        assertEquals(0, view.scrollOffset)
    }

    private fun button(report: String) = report.substringAfter('<').substringBefore(';').toInt()

    private fun wheel(view: AppKitTerminalView, pixels: Int, phase: Long = 0, momentum: Long = 0,
        precise: Boolean = true, shift: Boolean = false) {
        val cg = checkNotNull(CGEventCreateScrollWheelEvent2(null,
            if (precise) kCGScrollEventUnitPixel else kCGScrollEventUnitLine, 1u, pixels, 0, 0))
        try {
            CGEventSetIntegerValueField(cg, kCGScrollWheelEventScrollPhase, phase)
            CGEventSetIntegerValueField(cg, kCGScrollWheelEventMomentumPhase, momentum)
            if (shift) CGEventSetFlags(cg, kCGEventFlagMaskShift)
            // Unposted CG events have no window association. Put the converted
            // location inside this isolated view, independent of the real pointer.
            val intended = view.convertPoint(NSMakePoint(5.0, 5.0), toView = null).useContents { x to y }
            val actual = checkNotNull(NSEvent.eventWithCGEvent(cg)).locationInWindow.useContents { x to y }
            CGEventSetLocation(cg, CGEventGetLocation(cg).useContents {
                CGPointMake(x + intended.first - actual.first, y + actual.second - intended.second)
            })
            val event = checkNotNull(NSEvent.eventWithCGEvent(cg))
            assertEquals(precise, event.hasPreciseScrollingDeltas)
            assertEquals(when (momentum) {
                1L -> NSEventPhaseBegan
                2L -> NSEventPhaseChanged
                3L -> NSEventPhaseEnded
                else -> NSEventPhaseNone
            }, event.momentumPhase)
            view.scrollWheel(event)
        } finally { CFRelease(cg) }
    }

    private fun withView(test: (TerminalSession, AppKitTerminalView, MutableList<String>, Int) -> Unit) {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        window.contentView = view
        val input = mutableListOf<String>()
        val subscription = session.onInput { input += it.toByteArray().decodeToString() }
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val height = ceil(font.ascender - font.descender + font.leading + 2.0).toInt()
        try { test(session, view, input, height) }
        finally { subscription.dispose(); view.dispose(); window.close() }
    }
}
