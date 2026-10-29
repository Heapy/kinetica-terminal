@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.CoreGraphics.*
import platform.CoreFoundation.CFRelease
import kotlin.math.ceil
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsMouseTest {
    @Test fun appKitMouseEventsTrackButtonsMotionShiftWheelAndDisposal() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output.add(it.toByteArray().decodeToString()) }
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val width = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val height = ceil(font.ascender - font.descender + font.leading + 2.0)
        fun event(type: NSEventType, x: Int, y: Int, flags: ULong = 0u): NSEvent {
            val event = requireNotNull(NSEvent.mouseEventWithType(type,
                view.convertPoint(NSMakePoint((x + 0.5) * width, (y + 0.5) * height), toView = null),
                flags, 0.0, window.windowNumber, null, 0, 1, 1.0f))
            if (type !in listOf(NSEventTypeOtherMouseDown, NSEventTypeOtherMouseUp, NSEventTypeOtherMouseDragged)) return event
            // NSEvent's convenience constructor has no button-number argument and defaults
            // OtherMouse events to zero. Supply the physical middle identity through CGEvent.
            val cg = requireNotNull(event.CGEvent)
            CGEventSetIntegerValueField(cg, kCGMouseEventButtonNumber, 2)
            // Preserve the constructor's window-relative location. CGEventSetLocation
            // invalidates that synthetic event's window association, even for a no-op.
            val intended = event.locationInWindow.useContents { this.x to this.y }
            return requireNotNull(NSEvent.eventWithCGEvent(cg)).also {
                assertEquals(2, it.buttonNumber.toInt())
                assertEquals(intended, it.locationInWindow.useContents { this.x to this.y }, "Synthetic mouse location")
            }
        }
        fun wheel(dy: Int, dx: Int) {
            val cg = requireNotNull(CGEventCreateScrollWheelEvent2(null, kCGScrollEventUnitLine, 2u, dy, dx, 0))
            try {
                // An unposted CG wheel event has no window association. Give its NSEvent
                // an explicit window-space point instead of inheriting the user's cursor.
                val point = view.convertPoint(NSMakePoint(1.0, 1.0), toView = null)
                val screenHeight = (NSScreen.screens.first() as NSScreen).frame.useContents { size.height }
                CGEventSetLocation(cg, point.useContents { CGPointMake(x, screenHeight - y) })
                val wheel = requireNotNull(NSEvent.eventWithCGEvent(cg))
                assertEquals(point.useContents { x to y }, wheel.locationInWindow.useContents { x to y })
                view.scrollWheel(wheel)
            } finally { CFRelease(cg) }
        }
        try {
            view.updateTrackingAreas()
            assertEquals(1, view.trackingAreas.size)
            session.write("\u001b[?1003h\u001b[?1006h")
            view.mouseMoved(event(NSEventTypeMouseMoved, 1, 1))
            view.mouseMoved(event(NSEventTypeMouseMoved, 1, 1))
            view.rightMouseDown(event(NSEventTypeRightMouseDown, 1, 1))
            view.rightMouseDragged(event(NSEventTypeRightMouseDragged, 2, 1))
            view.rightMouseUp(event(NSEventTypeRightMouseUp, 2, 1, NSEventModifierFlagControl))
            view.otherMouseDown(event(NSEventTypeOtherMouseDown, 1, 1))
            view.otherMouseDragged(event(NSEventTypeOtherMouseDragged, 2, 1))
            view.otherMouseUp(event(NSEventTypeOtherMouseUp, 2, 1))
            assertEquals(listOf("\u001b[<35;2;2M", "\u001b[<2;2;2M", "\u001b[<34;3;2M", "\u001b[<18;3;2m",
                "\u001b[<1;2;2M", "\u001b[<33;3;2M", "\u001b[<1;3;2m"), output,
                output.joinToString { it.removePrefix("\u001b") })
            output.clear()
            view.mouseDown(event(NSEventTypeLeftMouseDown, 1, 1, NSEventModifierFlagShift))
            view.mouseDragged(event(NSEventTypeLeftMouseDragged, 2, 1))
            view.mouseUp(event(NSEventTypeLeftMouseUp, 2, 1))
            assertTrue(output.isEmpty())
            wheel(0, 0)
            assertTrue(output.isEmpty())
            wheel(0, 1); wheel(0, -1); wheel(1, 0); wheel(-1, 0)
            assertEquals(listOf(66, 67, 64, 65), output.map { it.substringAfter('<').substringBefore(';').toInt() })
            val count = output.size
            view.dispose()
            assertTrue(view.trackingAreas.isEmpty())
            view.updateTrackingAreas()
            assertTrue(view.trackingAreas.isEmpty())
            view.mouseDown(event(NSEventTypeLeftMouseDown, 1, 1))
            view.mouseMoved(event(NSEventTypeMouseMoved, 1, 1)); wheel(1, 0)
            assertEquals(count, output.size)
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun appKitPixelMouseUsesBackingScaleSubcellMotionAndOutsideRelease() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = mutableListOf<String>()
        val subscription = session.onInput { output.add(it.toByteArray().decodeToString()) }
        val scale = window.backingScaleFactor
        fun event(type: NSEventType, x: Double, y: Double, flags: ULong = 0u) = requireNotNull(
            NSEvent.mouseEventWithType(type, view.convertPoint(NSMakePoint(x, y), toView = null),
                flags, 0.0, window.windowNumber, null, 0, 1, 1.0f))
        fun report(code: Int, x: Double, y: Double, release: Boolean = false): String {
            fun pixel(value: Double): Int = (if (value < 0) ceil(value * scale - 0.5) else kotlin.math.floor(value * scale + 0.5)).toInt()
            return "\u001b[<$code;${pixel(x)};${pixel(y)}${if (release) 'm' else 'M'}"
        }
        try {
            session.write("\u001b[?1003h\u001b[?1016h")
            view.mouseMoved(event(NSEventTypeMouseMoved, 1.0, 1.0))
            view.mouseMoved(event(NSEventTypeMouseMoved, 1.5, 1.5))
            view.mouseMoved(event(NSEventTypeMouseMoved, 1.5, 1.5))
            view.mouseDown(event(NSEventTypeLeftMouseDown, 1.5, 1.5))
            view.mouseDragged(event(NSEventTypeLeftMouseDragged, -0.5, 321.5))
            view.mouseUp(event(NSEventTypeLeftMouseUp, -0.5, 321.5, NSEventModifierFlagShift))
            assertEquals(listOf(report(35, 1.0, 1.0), report(35, 1.5, 1.5), report(35, 1.5, 1.5),
                report(0, 1.5, 1.5), report(32, -0.5, 321.5), report(0, -0.5, 321.5, true)), output)
            output.clear()
            view.mouseDown(event(NSEventTypeLeftMouseDown, 2.0, 2.0, NSEventModifierFlagShift))
            view.mouseUp(event(NSEventTypeLeftMouseUp, 2.0, 2.0))
            assertTrue(output.isEmpty())
            view.rightMouseDown(event(NSEventTypeRightMouseDown, 2.5, 3.5))
            view.resignFirstResponder(); view.resignFirstResponder()
            view.dispose()
            assertEquals(listOf(report(2, 2.5, 3.5), report(2, 2.5, 3.5, true)), output)
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun realPtyReceivesLegacyHighBytesAndUtf8InputInOrder() {
        val session = TerminalSession(240, 24)
        var exited = false; var exitCode = -1
        val program = $$"""
            use strict; use warnings;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            $| = 1; print "\e[?1000hREADY";
            my $expected = pack('H*', 'e7958cf09f98801b5b4d2080211b5b4d33ff21');
            my $received = '';
            while (length($received) < length($expected)) {
                my $n = sysread(STDIN, my $part, length($expected) - length($received));
                defined($n) && $n > 0 or die 'read'; $received .= $part;
            }
            die 'mouse mismatch' unless $received eq $expected;
            print 'MOUSE_OK';
        """.trimIndent()
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("Mouse input rejected") }, onExit = { exitCode = it; exited = true })
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertTrue(condition(), "PTY timed out")
        }
        try {
            await { session.screenLine(0).text().contains("READY") }
            session.sendInput("界😀")
            session.sendMouse(95, 0, 0)
            session.sendMouse(222, 0, 2, release = true, control = true)
            await { exited }
            assertEquals(0, exitCode)
            assertContains(session.screenLine(0).text(), "MOUSE_OK")
            assertEquals(0, pty.pendingInputBytes)
            var closed = false
            pty.close { closed = true }
            assertTrue(closed) // Already reaped: no second asynchronous wait is required.
        } finally { pty.dispose() }
    }

    @Test fun closeWaitsForAChildThatIgnoresHangupWithoutBlockingAppKit() {
        val session = TerminalSession()
        val program = $$"""$SIG{HUP} = 'IGNORE'; $| = 1; print "READY"; sleep 60;"""
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program))
        var callbacks = 0
        val start = TimeSource.Monotonic.markNow()
        try {
            while (!session.screenLine(0).text().contains("READY") && start.elapsedNow().inWholeSeconds < 10)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertContains(session.screenLine(0).text(), "READY")
            pty.close { callbacks++ }; pty.close { callbacks++ }
            assertEquals(0, callbacks)
            var ticks = 0
            while (callbacks != 2 && start.elapsedNow().inWholeSeconds < 10) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01)); ticks++
            }
            assertEquals(2, callbacks)
            assertTrue(ticks > 1)
            pty.close { callbacks++ }
            assertEquals(3, callbacks)
        } finally { pty.dispose() }
    }
}
