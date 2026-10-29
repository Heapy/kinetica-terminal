@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.NSApplication
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsProtocolDeliveryTest {
    @Test fun realPtyDrainsChainedRepliesAndCancelsAnUndeliveredTail() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val program = """
            use strict; use warnings;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            ${'$'}| = 1; print 'READY';
            my ${'$'}expected = ('x' x 2000) . ('z' x (2 * 1024 * 1024));
            my ${'$'}received = '';
            while (length(${'$'}received) < length(${'$'}expected)) {
                my ${'$'}n = sysread(STDIN, my ${'$'}part, length(${'$'}expected) - length(${'$'}received));
                defined(${'$'}n) && ${'$'}n > 0 or die 'read';
                ${'$'}received .= ${'$'}part;
            }
            die 'reply mismatch' unless ${'$'}received eq ${'$'}expected;
            print 'DELIVERED';
            sleep 15;
        """.trimIndent()
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("Tracked reply rejected") })
        fun screen() = (0 until session.lineCount).joinToString("\n") { session.line(it).text() }
        var smallReplies = 0
        var largeReply: Boolean? = null
        var canceled: Boolean? = null
        var closed = false
        fun next() {
            session.sendProtocolReply(byteArrayOf('x'.code.toByte())) {
                assertTrue(it)
                if (++smallReplies < 2000) next()
                else session.sendProtocolReply(ByteArray(2 * 1024 * 1024) { 'z'.code.toByte() }) { largeReply = it }
            }
        }
        fun diagnostics() = "small=$smallReplies, large=$largeReply, canceled=$canceled, pending=${pty.pendingInputBytes}, screen=${screen()}"
        try {
            await("ready", ::diagnostics) { "READY" in screen() }
            next()
            await("delivery", ::diagnostics) { largeReply == true && "DELIVERED" in screen() }
            assertEquals(2000, smallReplies)
            assertEquals(0, pty.pendingInputBytes)
            session.sendProtocolReply(ByteArray(2 * 1024 * 1024)) { canceled = it }
            assertNull(canceled)
            assertTrue(pty.pendingInputBytes > 0)
            pty.close { closed = true }
            assertEquals(false, canceled)
            await("close", ::diagnostics) { closed }
            assertEquals(0L, pty.rejectedInputCount)
        } finally {
            if (!closed) { pty.close { closed = true }; await("cleanup", ::diagnostics) { closed } }
        }
    }

    private fun await(phase: String, diagnostics: () -> String, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition() && start.elapsedNow().inWholeSeconds < 10)
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        assertTrue(condition(), "Timed out waiting for isolated PTY $phase: ${diagnostics()}")
    }
}
