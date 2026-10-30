@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.NSApplication
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsTerminfoTest {
    @Test fun actualChildTermAndLargeCapabilityRepliesReachRealPty() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        var exit: Int? = null
        val program = """
            use strict; use warnings;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            ${'$'}| = 1;
            my ${'$'}term = uc(unpack('H*', ${'$'}ENV{'TERM'}));
            my ${'$'}reply = "\eP1+r544E=" . ${'$'}term . "\e\\";
            my ${'$'}request = "\eP+q" . join(';', ('544e') x 20000) . "\e\\";
            print ${'$'}request;
            select(undef, undef, undef, 0.15);
            my ${'$'}expected = ${'$'}reply x 20000;
            my ${'$'}received = '';
            while (length(${'$'}received) < length(${'$'}expected)) {
                my ${'$'}n = sysread(STDIN, my ${'$'}part, length(${'$'}expected) - length(${'$'}received));
                defined(${'$'}n) && ${'$'}n > 0 or die 'read';
                ${'$'}received .= ${'$'}part;
            }
            die 'terminfo mismatch' unless ${'$'}received eq ${'$'}expected;
            print 'TERMINFO_OK';
        """.trimIndent()
        val name = "kinetica-test-" + "x".repeat(100)
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program), environment = mapOf("TERM" to name),
            onInputRejected = { fail("Capability reply rejected") }, onExit = { exit = it })
        try {
            assertEquals(name, session.terminfoName)
            val start = TimeSource.Monotonic.markNow()
            // This exchanges about 4.7 MiB through the real, paced PTY writer. An isolated
            // correct run takes 19 seconds on a loaded desktop; 20 seconds is not a useful
            // deadlock watchdog for the full suite. Keep the exact payload/exit assertions.
            while (exit == null && start.elapsedNow().inWholeSeconds < 60) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertEquals(0, exit, "XTGETTCAP exchange after ${start.elapsedNow()}: " +
                "received=${pty.receivedBytes}, pendingInput=${pty.pendingInputBytes}, " +
                "bufferedOutput=${pty.bufferedOutputBytes}, paused=${session.outputPaused}")
            assertEquals(0L, pty.rejectedInputCount)
            assertFalse(session.outputPaused)
            assertContains((0 until session.lineCount).joinToString("\n") { session.line(it).text() }, "TERMINFO_OK")
        } finally { pty.dispose() }
    }
}
