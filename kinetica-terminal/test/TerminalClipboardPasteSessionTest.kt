package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalClipboardPasteSessionTest {
    private class Harness(val session: TerminalSession = TerminalSession()) : TerminalByteTransport, TerminalScheduler {
        val jobs = ArrayDeque<() -> Unit>()
        val output = mutableListOf<Byte>()
        var writable = true
        val connection = TerminalStreamConnection(session, this, this, { fail("Input rejected") }, { throw it })
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            jobs.addLast(action); return TerminalDisposable { jobs.remove(action) }
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            val size = if (writable) minOf(7, length) else 0
            repeat(size) { output += bytes[offset + it] }; return size
        }
        override fun resize(columns: Int, rows: Int) {}
        override fun consumed(bytes: Int) {}
        fun feed(text: String) { assertTrue(connection.receive(text.encodeToByteArray())); drain() }
        fun drain() {
            var turns = 0
            while (jobs.isNotEmpty()) {
                check(turns++ < 10000)
                jobs.removeFirst()()
                if (!writable && connection.pendingInputBytes > 0) return
            }
        }
        fun wire() = output.toByteArray().decodeToString()
        fun clear() { output.clear() }
    }
    private class Host : TerminalMimeClipboard {
        val operations = mutableListOf<String>()
        var metadata: (((TerminalMimeClipboardRead) -> Unit) -> Unit)? = null
        override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
            operations += if (mimeTypes.isEmpty()) "list" else "read"
            val hook = metadata
            if (mimeTypes.isEmpty() && hook != null) hook(complete)
            else complete(TerminalMimeClipboardRead(available = listOf("text/plain", "image/png"),
                contents = mimeTypes.map { TerminalMimeClipboardContent(it, "secret".encodeToByteArray()) }))
        }
        override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) = complete(TerminalClipboardResult.SUCCESS)
    }
    private fun osc(body: String) = "\u001b]5522;$body\u001b\\"
    private fun password(wire: String): String = Base64.decode(wire.substringAfter(":pw=").substringBefore("\u001b\\")).decodeToString()
    private fun read(password: String = "") = osc("type=read:name=UGFzdGUgZXZlbnQ=:pw=${Base64.encode(password.encodeToByteArray())};dGV4dC9wbGFpbg==")

    @Test fun capabilityModeSaveRestoreSoftResetAndRisMatchGhostty() {
        val h = Harness()
        h.feed("\u001b[?5522h\u001b[?5522${'$'}p")
        assertEquals("\u001b[?5522;0${'$'}y", h.wire()); h.clear()
        assertFalse(h.session.pasteClipboard())
        val binding = h.session.attachMimeClipboard(Host())
        h.feed("\u001b[?5522${'$'}p\u001b[?5522s\u001b[?5522l\u001b[?5522${'$'}p\u001b[?5522r\u001b[!p\u001b[?5522${'$'}p")
        assertEquals("\u001b[?5522;1${'$'}y\u001b[?5522;2${'$'}y\u001b[?5522;1${'$'}y", h.wire())
        h.clear(); h.feed("\u001bc\u001b[?5522r\u001b[?5522${'$'}p")
        assertFalse(h.session.kittyPasteEvents)
        assertEquals("\u001b[?5522;2${'$'}y", h.wire())
        binding.dispose(); h.clear(); h.feed("\u001b[?5522${'$'}p")
        assertEquals("\u001b[?5522;0${'$'}y", h.wire())
        h.connection.dispose()
    }

    @Test fun trustedPasteGrantsOneReadAndScriptedTextRemainsText() {
        val h = Harness(); val host = Host()
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.WRITE_AND_PASTE)
        h.feed("\u001b[?5522h\u001b[?2004h")
        val completed = mutableListOf<TerminalClipboardResult>()
        assertTrue(h.session.pasteClipboard { completed += it }); h.drain()
        assertEquals(listOf(TerminalClipboardResult.SUCCESS), completed)
        assertEquals(listOf("list"), host.operations)
        val pw = password(h.wire()); assertEquals(22, pw.length)
        assertFalse(h.wire().contains("secret")); assertFalse(h.wire().contains("\u001b[200~"))
        h.clear(); h.feed(read(pw))
        assertContains(h.wire(), ";c2VjcmV0\u001b\\")
        h.clear(); h.feed(read(pw)); assertEquals(osc("type=read:status=EPERM"), h.wire())
        h.clear(); h.session.paste("scripted"); h.drain()
        assertEquals("\u001b[200~scripted\u001b[201~", h.wire())
        assertEquals(listOf("list", "read"), host.operations)
        h.connection.dispose()
    }

    @Test fun userGesturesKeepTheirOrderRelativeToCommandsHeldBehindOsc52() {
        for (gestureFirst in listOf(false, true)) {
            val h = Harness(); val host = Host()
            var finishLegacy: ((ByteArray?) -> Unit)? = null
            h.session.attachClipboard(object : TerminalClipboard {
                override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) { finishLegacy = complete }
                override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) = complete()
            }, TerminalClipboardPolicy.ALLOW)
            h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.ALLOW)
            h.feed("\u001b[?5522h\u001b]52;c;?\u0007")
            if (gestureFirst) assertTrue(h.session.pasteClipboard())
            h.feed(read())
            if (!gestureFirst) assertTrue(h.session.pasteClipboard())
            assertTrue(host.operations.isEmpty())
            assertNotNull(finishLegacy)("legacy".encodeToByteArray()); h.drain()
            assertEquals(if (gestureFirst) listOf("list", "read") else listOf("read", "list"), host.operations)
            assertTrue(h.wire().startsWith("\u001b]52;c;bGVnYWN5\u0007"))
            assertFalse(h.session.outputPaused)
            h.connection.dispose()
        }
    }

    @Test fun modeChangeInvalidatesPendingMetadataEvenWhenReenabled() {
        val h = Harness(); val host = Host()
        var finish: ((TerminalMimeClipboardRead) -> Unit)? = null
        host.metadata = { finish = it }
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.WRITE_AND_PASTE)
        h.feed("\u001b[?5522h")
        val completed = mutableListOf<TerminalClipboardResult>()
        h.session.pasteClipboard { completed += it }
        h.feed("\u001b[?5522l\u001b[?5522h")
        assertNotNull(finish)(TerminalMimeClipboardRead(available = listOf("text/plain"))); h.drain()
        assertEquals(listOf(TerminalClipboardResult.BUSY), completed)
        assertEquals("", h.wire())
        host.metadata = null
        assertTrue(h.session.pasteClipboard()); h.drain()
        val pw = password(h.wire())
        h.clear(); h.feed("\u001bc" + read(pw))
        assertEquals(osc("type=read:status=EPERM"), h.wire())
        h.connection.dispose()
    }

    @Test fun pendingGesturesAreBoundedAndHostReplacementCancelsThemOnce() {
        val h = Harness(); val host = Host()
        var finish: ((TerminalMimeClipboardRead) -> Unit)? = null
        host.metadata = { finish = it }
        h.session.attachMimeClipboard(host)
        h.feed("\u001b[?5522h")
        val completed = mutableListOf<TerminalClipboardResult>()
        repeat(18) { assertTrue(h.session.pasteClipboard { completed += it }) }
        assertEquals(listOf(TerminalClipboardResult.BUSY), completed)
        assertEquals(listOf("list"), host.operations)
        val replacement = Host()
        h.session.attachMimeClipboard(replacement)
        assertEquals(18, completed.size)
        assertTrue(completed.all { it == TerminalClipboardResult.BUSY })
        assertNotNull(finish)(TerminalMimeClipboardRead(available = listOf("stale"))); h.drain()
        assertTrue(replacement.operations.isEmpty()); assertEquals("", h.wire())
        h.connection.dispose()
    }

    @Test fun closingTransportRevokesAnUndeliveredPastePassword() {
        val h = Harness(); val host = Host()
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.WRITE_AND_PASTE)
        h.feed("\u001b[?5522h")
        var submitted = ""
        val observe = h.session.onInput { submitted = it.toByteArray().decodeToString() }
        val completed = mutableListOf<TerminalClipboardResult>()
        h.writable = false
        h.session.pasteClipboard { completed += it }; h.drain()
        val pw = password(submitted)
        assertTrue(completed.isEmpty())
        h.connection.dispose(); observe.dispose()
        assertEquals(listOf(TerminalClipboardResult.BUSY), completed)
        val resumed = Harness(h.session)
        resumed.feed(read(pw))
        assertEquals(osc("type=read:status=EPERM"), resumed.wire())
        resumed.connection.dispose()
    }
}
