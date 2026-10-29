@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlin.time.TimeSource
import kotlin.test.*
import kotlin.io.encoding.Base64

class MacOsClipboardProtocolTest {
    @Test fun largeClipboardReadReachesRealPtyWithoutTruncationOrInterleaving() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val contents = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        session.attachClipboard(object : TerminalClipboard {
            override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) = complete(contents)
            override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) = complete()
        }, TerminalClipboardPolicy.ALLOW)
        var exit: Int? = null
        val program = """
            use strict; use warnings; use MIME::Base64;
            system('/bin/stty', 'raw', '-echo') == 0 or die 'stty';
            ${'$'}| = 1;
            print "\e]52;c;?\e\\";
            select(undef, undef, undef, 0.25);
            my ${'$'}expected = "\e]52;c;" . encode_base64('x' x (2 * 1024 * 1024), '') . "\e\\";
            my ${'$'}received = '';
            while (length(${'$'}received) < length(${'$'}expected)) {
                my ${'$'}n = sysread(STDIN, my ${'$'}part, length(${'$'}expected) - length(${'$'}received));
                defined(${'$'}n) && ${'$'}n > 0 or die 'read';
                ${'$'}received .= ${'$'}part;
            }
            die 'clipboard mismatch' unless ${'$'}received eq ${'$'}expected;
            print 'CLIPBOARD_OK';
        """.trimIndent()
        val pty = MacOsPty(session, "/usr/bin/perl", listOf("-e", program),
            onInputRejected = { fail("Clipboard reply rejected") }, onExit = { exit = it })
        try {
            val start = TimeSource.Monotonic.markNow()
            while (exit == null && start.elapsedNow().inWholeSeconds < 15) {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertEquals(0, exit)
            assertEquals(0L, pty.rejectedInputCount)
            assertEquals(0, pty.pendingInputBytes)
            assertContains((0 until session.lineCount).joinToString("\n") { session.line(it).text() }, "CLIPBOARD_OK")
        } finally { pty.dispose() }
    }

    @Test fun osc52UsesAuthorizedIsolatedPasteboardAndDetachesOnDisposal() {
        NSApplication.sharedApplication()
        val pasteboard = NSPasteboard.pasteboardWithUniqueName()
        pasteboard.setString("original", NSPasteboardTypeString)
        val session = TerminalSession()
        var allowed = false
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE,
            clipboardPolicy = TerminalClipboardPolicy { _, done -> done(allowed) },
            clipboard = TerminalNativeClipboard(pasteboard))
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        fun set(value: String) = "\u001b]52;c;${Base64.Default.encode(value.encodeToByteArray())}\u0007"
        try {
            session.write(set("denied") + "\u001b]52;c;?\u0007")
            assertEquals("original", pasteboard.stringForType(NSPasteboardTypeString))
            assertEquals("\u001b]52;c;\u0007", replies.toString())
            allowed = true; replies.clear()
            val value = "native clipboard 世界\nsecond line"
            session.write("\u001b[?2026h" + set(value) + "\u001b]52;c;?\u001b\\")
            assertEquals(value, pasteboard.stringForType(NSPasteboardTypeString))
            assertEquals(set(value).dropLast(1) + "\u001b\\", replies.toString())
            session.write("\u001b]52;c;/w==\u0007") // Invalid UTF-8 cannot clear valid text.
            assertEquals(value, pasteboard.stringForType(NSPasteboardTypeString))
            session.write("\u001b]52;p;YQ==\u0007\u001b]52;p;?\u0007")
            assertEquals(value, pasteboard.stringForType(NSPasteboardTypeString))
            assertTrue(replies.endsWith("\u001b]52;p;\u0007"))
            session.write("\u001b]52;c;\u0007")
            assertNull(pasteboard.stringForType(NSPasteboardTypeString))
            session.write(set("retained")); view.dispose()
            replies.clear(); session.write(set("detached") + "\u001b]52;c;?\u0007")
            assertEquals("retained", pasteboard.stringForType(NSPasteboardTypeString))
            assertEquals("", replies.toString())
        } finally { view.dispose(); pasteboard.releaseGlobally() }
    }
}
