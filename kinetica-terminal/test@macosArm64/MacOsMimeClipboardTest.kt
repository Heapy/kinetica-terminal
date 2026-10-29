@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.NSApplication
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsMimeClipboardTest {
    @Test fun binaryMimeWriteAliasAndChunkedReadRoundTripThroughRealPty() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        var stored = emptyList<TerminalMimeClipboardContent>()
        var reads = 0
        var writes = 0
        session.attachMimeClipboard(object : TerminalMimeClipboard {
            override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
                assertEquals(TerminalClipboardLocation.STANDARD, location)
                reads++
                complete(TerminalMimeClipboardRead(available = stored.map { it.mime }, contents = stored))
            }
            override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
                writes++
                // The host stores its own representations after the atomic write commits.
                stored = contents.map { TerminalMimeClipboardContent(it.mime, it.data.copyOf()) }
                complete(TerminalClipboardResult.SUCCESS)
            }
        }, TerminalMimeClipboardPolicy.ALLOW)
        val program = """
            use strict; use warnings; use MIME::Base64;
            alarm 30;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            ${'$'}| = 1;
            sub b64 { encode_base64(${'$'}_[0], '') }
            sub osc { "\e]5522;" . ${'$'}_[0] . "\a" }
            sub expect {
                my (${'$'}expected) = @_;
                my ${'$'}received = '';
                while (length(${'$'}received) < length(${'$'}expected)) {
                    my ${'$'}n = sysread(STDIN, my ${'$'}part, length(${'$'}expected) - length(${'$'}received));
                    defined(${'$'}n) && ${'$'}n > 0 or die 'read';
                    ${'$'}received .= ${'$'}part;
                }
                die 'clipboard wire mismatch' unless ${'$'}received eq ${'$'}expected;
            }
            my ${'$'}raw = join('', map { chr(${'$'}_ % 251) } 0 .. (1024 * 1024 - 1));
            print osc('type=write:id=native');
            print osc('type=wdata:mime=' . b64('text/plain') . ';' . b64('hello'));
            for (my ${'$'}i = 0; ${'$'}i < length(${'$'}raw); ${'$'}i += 4096) {
                print osc('type=wdata:mime=' . b64('image/png') . ';' . b64(substr(${'$'}raw, ${'$'}i, 4096)));
            }
            print osc('type=walias:mime=' . b64('image/png') . ';' . b64('application/x-binary'));
            print osc('type=wdata');
            expect(osc('type=write:status=DONE:id=native'));
            print osc('type=read:id=native;' . b64('. application/x-binary text/plain'));
            my ${'$'}expected = osc('type=read:status=OK:id=native');
            ${'$'}expected .= osc('type=read:status=DATA:id=native:mime=' . b64('.') . ';' . b64("text/plain image/png application/x-binary\n"));
            for (my ${'$'}i = 0; ${'$'}i < length(${'$'}raw); ${'$'}i += 4096) {
                ${'$'}expected .= osc('type=read:status=DATA:id=native:mime=' . b64('application/x-binary') . ';' . b64(substr(${'$'}raw, ${'$'}i, 4096)));
            }
            ${'$'}expected .= osc('type=read:status=DATA:id=native:mime=' . b64('text/plain') . ';' . b64('hello'));
            ${'$'}expected .= osc('type=read:status=DONE:id=native');
            expect(${'$'}expected);
            print 'MIME_ROUNDTRIP_OK';
        """.trimIndent()
        var exit: Int? = null
        var closed = false
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("MIME reply rejected") }, onExit = { exit = it })
        try {
            val start = TimeSource.Monotonic.markNow()
            while (exit == null && start.elapsedNow().inWholeSeconds < 35)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            val screen = (0 until session.lineCount).joinToString("\n") { session.line(it).text() }
            assertEquals(0, exit, "pending=${pty.pendingInputBytes}, reads=$reads, writes=$writes, screen=$screen")
            assertEquals(1, reads); assertEquals(1, writes)
            assertEquals(listOf("text/plain", "image/png", "application/x-binary"), stored.map { it.mime })
            assertContentEquals(ByteArray(1024 * 1024) { (it % 251).toByte() }, stored[2].data)
            assertContains(screen, "MIME_ROUNDTRIP_OK")
            assertEquals(0L, pty.rejectedInputCount)
        } finally {
            pty.close { closed = true }
            val start = TimeSource.Monotonic.markNow()
            while (!closed && start.elapsedNow().inWholeSeconds < 10)
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            assertTrue(closed, "Isolated MIME test child was not reaped")
        }
    }
}
