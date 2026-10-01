@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.CATextLayer
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsTerminalTest {
    @Test fun appKitHostReleasesAbandonedRenderHoldOnItsRunLoop() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val surface = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        try {
            session.write("OLD\u001b[?2026h\rNEW")
            assertTrue(session.synchronizedOutput)
            assertContains(surface.accessibilityValue().toString(), "OLD")
            assertFalse(surface.accessibilityValue().toString().contains("NEW"))
            val originalSize = session.columns to session.rows
            surface.setFrameSize(NSMakeSize(1000.0, 600.0))
            assertEquals(originalSize, session.columns to session.rows)
            assertTrue(session.synchronizedOutput)
            await { !session.synchronizedOutput }
            assertContains(surface.accessibilityValue().toString(), "NEW")
            assertTrue(session.columns > originalSize.first)
            assertTrue(session.rows > originalSize.second)
        } finally { surface.dispose() }
    }

    @Test fun realPtyRunsCommandAndReceivesInputAndResize() {
        val session = TerminalSession(80, 24)
        var exited = false
        var exitCode = -1
        var rejected = 0
        val pty = MacOsPty(session, "/bin/sh", listOf("-c", "printf 'READY\\n'; read answer; stty size; printf 'answer=%s\\n' \"\$answer\""),
            onInputRejected = { rejected++ }, onExit = { exitCode = it; exited = true })
        try {
            await { screen(session).contains("READY") }
            session.resize(100, 32)
            session.sendInput("x".repeat(2 * 1024 * 1024))
            assertEquals(1, rejected)
            assertEquals(1L, pty.rejectedInputCount)
            assertEquals(0, pty.pendingInputBytes)
            session.sendInput("hello界😀\r")
            await { exited }
            assertContains(screen(session), "32 100")
            assertContains(screen(session), "answer=hello界😀")
            assertEquals(0, exitCode)
            assertEquals(0, pty.pendingInputBytes)
            assertEquals(0, pty.bufferedOutputBytes)
        } finally { pty.dispose(); pty.dispose() }
    }

    @Test fun realPtyReplyFloodPausesThenDeliversEveryByteWhileRunLoopKeepsRunning() {
        val session = TerminalSession()
        var exited = false
        var exitCode = -1
        var ticks = 0
        val heartbeat = NSTimer.timerWithTimeInterval(0.001, repeats = true) { ticks++ }
        NSRunLoop.mainRunLoop.addTimer(heartbeat, NSRunLoopCommonModes)
        // macOS ships Perl. A separate writer floods queries while the parent deliberately
        // leaves stdin unread, then verifies the complete reply stream byte for byte.
        val program = $$"""
            use strict; use warnings;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            $| = 1;
            my $query = "\e]4;" . join(';', ('0;?') x 1023) . "\e\\";
            my $expected = ("\e]4;0;rgb:0000/0000/0000\e\\" x 1023) x 64;
            my $pid = fork(); defined($pid) or die 'fork';
            if ($pid == 0) { print $query x 64; exit 0; }
            select(undef, undef, undef, 2);
            my $received = '';
            while (length($received) < length($expected)) {
                my $n = sysread(STDIN, my $part, length($expected) - length($received));
                defined($n) && $n > 0 or die 'read';
                $received .= $part;
            }
            die 'reply mismatch' unless $received eq $expected;
            waitpid($pid, 0);
            print "FLOW_OK\xe7\x95\x8c\xf0\x9f\x98\x80";
        """.trimIndent()
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("PTY protocol reply was rejected") }, onExit = { exitCode = it; exited = true })
        try {
            await { pty.outputPausedForInput }
            assertTrue(pty.pendingInputBytes in (1024 * 1024 - TERMINAL_REPLY_RESERVE + 1)..(1024 * 1024))
            assertTrue(pty.bufferedOutputBytes in 0..16 * 1024)
            val ticksAtPause = ticks
            assertTrue(ticksAtPause > 0)
            await(seconds = 15) { exited }
            assertEquals(0, exitCode)
            assertContains(screen(session), "FLOW_OK界😀")
            assertTrue(ticks > ticksAtPause)
            assertEquals(0L, pty.rejectedInputCount)
            assertEquals(0, pty.pendingInputBytes)
            assertEquals(0, pty.bufferedOutputBytes)
        } finally { heartbeat.invalidate(); pty.dispose() }
    }

    @Test fun failedExecDoesNotCreateAUsableTransport() {
        assertFailsWith<IllegalStateException> { MacOsPty(TerminalSession(), "/no/such/kinetica-shell") }
    }

    @Test fun disposingTransportFromOutputObserverDoesNotResumeThePump() {
        val session = TerminalSession()
        var disposed = false
        lateinit var pty: MacOsPty
        val observation = session.observe {
            if (!disposed && screen(session).contains("STOP")) { disposed = true; pty.dispose() }
        }
        pty = MacOsPty(session, "/bin/sh", listOf("-c", "printf 'STOP%100000s' x"))
        try {
            await { disposed }
            assertEquals(0, pty.bufferedOutputBytes)
            assertEquals(0, pty.pendingInputBytes)
            assertFalse(pty.outputPausedForInput)
        } finally { observation.dispose(); pty.dispose() }
    }

    @Test fun appKitSurfaceSurvivesRenderAndAcceptsComposedText() {
        NSApplication.sharedApplication()
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        val session = TerminalSession()
        val inputs = mutableListOf<String>()
        val subscription = session.onInput { inputs.add(it.toByteArray().decodeToString()) }
        val surface = AppKitTerminalView(session, "shell", rendering = TerminalRendering.GPU)
        surface.setFrame(window.contentView!!.bounds)
        surface.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
        window.contentView = surface
        try {
            window.layoutIfNeeded()
            assertEquals("metal", surface.renderingBackend)
            surface.configureFont(null, 14.0)
            assertSame(surface, window.contentView)
            surface.setMarkedText("に", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
            assertTrue(surface.hasMarkedText())
            assertTrue(inputs.isEmpty())
            surface.insertText("日本", NSMakeRange(NSNotFound.toULong(), 0u))
            assertEquals(listOf("日本"), inputs)
            assertFalse(surface.hasMarkedText())
            session.write("\u001b[32mNative surface\u001b[0m")
            window.orderFront(null)
            surface.displayIfNeeded()
            await { surface.submittedGpuFrames > 0 }
            assertContains(surface.accessibilityValue().toString(), "Native surface")
            surface.setMarkedText("に", NSMakeRange(1u, 0u), NSMakeRange(NSNotFound.toULong(), 0u))
            surface.displayIfNeeded()
            val metal = surface.layer as CAMetalLayer
            val overlay = metal.sublayers!!.single() as CATextLayer
            assertTrue(metal.geometryFlipped)
            assertEquals("に", (overlay.string as NSAttributedString).string)
            assertFalse(overlay.hidden)
        } finally { subscription.dispose(); surface.dispose(); window.close() }
    }

    @Test fun softwareRendererCanBeSelectedExplicitly() {
        NSApplication.sharedApplication()
        val surface = AppKitTerminalView(TerminalSession(), rendering = TerminalRendering.SOFTWARE)
        try { assertEquals("appkit", surface.renderingBackend); assertNull(surface.renderingFailure) }
        finally { surface.dispose() }
    }

    private fun screen(session: TerminalSession): String = (0 until session.lineCount).joinToString("\n") { session.line(it).text() }
    private fun await(seconds: Int = 5, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition() && start.elapsedNow().inWholeSeconds < seconds) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        assertTrue(condition(), "Timed out waiting for PTY")
    }
}
