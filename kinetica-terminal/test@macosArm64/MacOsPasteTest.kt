@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.NSApplication
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsPasteTest {
    @Test fun pasteControlBytesAndNewlinesReachAnIsolatedRawPtyExactly() {
        NSApplication.sharedApplication()
        val session = TerminalSession(80, 8)
        val plain = "one two\r\rthree [201~界"
        val bracketed = "\u001b[200~one two\r\nthree [201~界\u001b[201~"
        val hex = listOf(plain, bracketed).joinToString(",") { "'" + it.encodeToByteArray().joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') } + "'" }
        val program = """
            use strict; use warnings; alarm 20;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            ${'$'}| = 1; print 'READY';
            my ${'$'}i = 0;
            for my ${'$'}hex ($hex) {
                my ${'$'}expected = pack('H*', ${'$'}hex);
                my ${'$'}received = '';
                while (length(${'$'}received) < length(${'$'}expected)) {
                    my ${'$'}n = sysread(STDIN, my ${'$'}part, length(${'$'}expected) - length(${'$'}received));
                    defined(${'$'}n) && ${'$'}n > 0 or die 'read';
                    ${'$'}received .= ${'$'}part;
                }
                die 'paste bytes mismatch' unless ${'$'}received eq ${'$'}expected;
                print "PASTE_OK_${'$'}i"; ${'$'}i++;
            }
        """.trimIndent()
        var exit: Int? = null
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program), onExit = { exit = it })
        fun screen() = (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertTrue(condition(), "Isolated paste PTY timed out")
        }
        try {
            await { "READY" in screen() || exit != null }; assertContains(screen(), "READY")
            val input = "one\u0003two\r\nthree\u001b[201~界"
            session.paste(input)
            await { "PASTE_OK_0" in screen() || exit != null }; assertContains(screen(), "PASTE_OK_0")
            session.write("\u001b[?2004h"); session.paste(input)
            await { exit != null }
            assertEquals(0, exit); assertContains(screen(), "PASTE_OK_1")
            assertEquals(0L, pty.rejectedInputCount)
        } finally { pty.dispose() }
    }
}
