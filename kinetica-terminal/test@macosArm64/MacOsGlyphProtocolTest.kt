@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsGlyphProtocolTest {
    @Test fun realPtyReceivesGlyphRepliesAndResetClearsCoverage() {
        val session = TerminalSession(80, 6)
        var exited = false
        var exitCode: Int? = null
        val program = $$"""
            use strict; use warnings;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            $| = 1;
            print "\e_25a1;s\e\\\e_25a1;r;cp=e0a0;AAAAAAAAAAAAAA==\e\\\e_25a1;q;cp=e0a0\e\\";
            my $expected = "\e_25a1;s;fmt=glyf\e\\\e_25a1;r;cp=e0a0;status=0\e\\\e_25a1;q;cp=e0a0;status=glossary\e\\";
            my $received = '';
            while (length($received) < length($expected)) {
                my $n = sysread(STDIN, my $part, length($expected)-length($received));
                defined($n) && $n > 0 or die 'read'; $received .= $part;
            }
            die 'glyph mismatch' unless $received eq $expected;
            print "\ec\e_25a1;q;cp=e0a0\e\\";
            $expected = "\e_25a1;q;cp=e0a0;status=\e\\"; $received = '';
            while (length($received) < length($expected)) {
                my $n = sysread(STDIN, my $part, length($expected)-length($received));
                defined($n) && $n > 0 or die 'read'; $received .= $part;
            }
            die 'reset mismatch' unless $received eq $expected;
            print 'GLYPH_OK';
        """.trimIndent()
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("Glyph reply rejected") }, onExit = { exitCode = it; exited = true })
        try {
            val start = TimeSource.Monotonic.markNow()
            while (!exited && start.elapsedNow().inWholeSeconds < 10)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertTrue(exited, "Glyph PTY timed out"); assertEquals(0, exitCode)
            assertContains(session.screenLine(0).text(), "GLYPH_OK")
            assertEquals(0, pty.pendingInputBytes); assertEquals(0, session.glyphProtocol.count)
        } finally { pty.dispose() }
    }
}
