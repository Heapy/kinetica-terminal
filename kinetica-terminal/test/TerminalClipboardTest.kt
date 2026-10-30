package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalClipboardTest {
    @Test fun iterm2CopyUsesTheExistingPolicyQueueAndCancellation() {
        fun copy(text: String) = "\u001b]1337;Copy=:${Base64.encode(text.encodeToByteArray())}\u0007"
        val session = TerminalSession()
        val clipboard = Clipboard()
        session.write(copy("without host"))
        session.attachClipboard(clipboard)
        session.write(copy("denied"))
        assertEquals(0, clipboard.writes)
        val decisions = ArrayDeque<(Boolean) -> Unit>()
        val binding = session.attachClipboard(clipboard, TerminalClipboardPolicy { request, done ->
            assertEquals(TerminalClipboardLocation.STANDARD, request.location)
            decisions.addLast(done)
        })
        session.write(copy("first") + set("second"))
        assertEquals(1, decisions.size); assertEquals(0, clipboard.writes)
        decisions.removeFirst()(true)
        assertEquals("first", clipboard.data.decodeToString())
        decisions.removeFirst()(true)
        assertEquals("second", clipboard.data.decodeToString())
        val large = "x".repeat(1500)
        session.write(copy(large)); decisions.removeFirst()(true)
        assertEquals(large, clipboard.data.decodeToString())
        session.write(copy("x".repeat(150_000)))
        assertTrue(decisions.isEmpty(), "OSC 1337 keeps the original fixed capture limit")
        session.write(copy("reset")); session.reset(); decisions.removeFirst()(true)
        session.write(copy("detached")); binding.dispose(); decisions.removeFirst()(true)
        assertEquals(3, clipboard.writes); assertEquals(0, clipboard.reads)
        assertEquals(large, clipboard.data.decodeToString())
    }

    @Test fun protocolBudgetPreservesWrappedInputAndReleasesItsLargeAllocation() {
        val buffer = TerminalInputBuffer(8)
        buffer.add("abcdef"); buffer.consume(4); buffer.add("ghij")
        assertEquals(6, buffer.size)
        val reply = ByteArray(128) { 'r'.code.toByte() }
        assertTrue(buffer.tryAdd(TerminalInputData.ProtocolReply(reply)))
        assertEquals(134, buffer.size); assertEquals(0, buffer.remaining)
        assertFalse(buffer.tryAdd("rejected"))
        val actual = mutableListOf<Byte>()
        while (buffer.size > 0) {
            val count = minOf(7, buffer.contiguousSize)
            actual.addAll(buffer.buffer!!.copyOfRange(buffer.offset, buffer.offset + count).toList())
            buffer.consume(count)
        }
        assertEquals("efghij" + "r".repeat(128), actual.toByteArray().decodeToString())
        assertNull(buffer.buffer)
        buffer.add("ordinary")
        assertEquals(8, buffer.buffer!!.size)
        val oversized = ByteArray(TERMINAL_CLIPBOARD_REPLY_BYTES + 1)
        assertFalse(buffer.tryAdd(TerminalInputData.ProtocolReply(oversized)))
        assertEquals(8, buffer.size)
    }
    private class Clipboard : TerminalClipboard {
        var data = "initial".encodeToByteArray()
        var reads = 0
        var writes = 0
        var pendingRead: ((ByteArray?) -> Unit)? = null
        var deferRead = false
        override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) {
            reads++
            if (deferRead) pendingRead = complete else complete(data)
        }
        override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) {
            writes++; this.data = data; complete()
        }
    }
    private fun set(text: String) = "\u001b]52;c;${Base64.Default.encode(text.encodeToByteArray())}\u0007"
    private val query = "\u001b]52;c;?\u0007"
    private val emptyReply = "\u001b]52;c;\u0007"

    @Test fun noHostAndDefaultPolicyCannotAccessClipboard() {
        val session = TerminalSession()
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write(set("untrusted") + query)
        assertEquals("", replies.toString())
        val clipboard = Clipboard()
        session.attachClipboard(clipboard)
        session.write(set("untrusted") + query)
        assertEquals(0, clipboard.reads); assertEquals(0, clipboard.writes)
        assertEquals(emptyReply, replies.toString())
        session.attachClipboard(clipboard, TerminalClipboardPolicy.WRITE_ONLY)
        session.write(set("allowed") + query)
        assertEquals("allowed", clipboard.data.decodeToString())
        assertEquals(0, clipboard.reads)
        assertEquals(emptyReply.repeat(2), replies.toString())
    }

    @Test fun delayedPermissionPreservesOrderAndIgnoresDuplicateDecisions() {
        val session = TerminalSession()
        val clipboard = Clipboard()
        val decisions = ArrayDeque<(Boolean) -> Unit>()
        session.attachClipboard(clipboard, TerminalClipboardPolicy { _, complete -> decisions.addLast(complete) })
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write(set("first") + query + set("last"))
        assertEquals(1, decisions.size); assertEquals(0, clipboard.writes)
        val first = decisions.removeFirst()
        first(true); first(true)
        assertEquals(1, clipboard.writes); assertEquals(1, decisions.size)
        decisions.removeFirst()(true)
        assertEquals(set("first"), replies.toString())
        decisions.removeFirst()(true)
        assertEquals("last", clipboard.data.decodeToString()); assertEquals(2, clipboard.writes)
    }

    @Test fun resetReplacementAndDisposalCancelPendingEffectsAndReplies() {
        val session = TerminalSession()
        val clipboard = Clipboard()
        var approve: ((Boolean) -> Unit)? = null
        val old = session.attachClipboard(clipboard, TerminalClipboardPolicy { _, done -> approve = done })
        session.write(set("stale"))
        session.reset(); approve!!(true)
        assertEquals(0, clipboard.writes)
        clipboard.deferRead = true
        val binding = session.attachClipboard(clipboard, TerminalClipboardPolicy.ALLOW)
        old.dispose() // An old view cannot detach a newer host.
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write(query)
        val complete = clipboard.pendingRead!!
        binding.dispose(); complete("secret".encodeToByteArray())
        session.write(set("detached"))
        assertEquals("", replies.toString()); assertEquals(0, clipboard.writes)
    }

    @Test fun asyncReadsCompleteOnceAndFailuresYieldEmptyReplies() {
        val session = TerminalSession()
        val clipboard = Clipboard().apply { deferRead = true }
        session.attachClipboard(clipboard, TerminalClipboardPolicy.ALLOW)
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        session.write(query + set("next"))
        assertEquals(0, clipboard.writes)
        val complete = clipboard.pendingRead!!
        complete(null); complete("duplicate".encodeToByteArray())
        assertEquals(emptyReply, replies.toString())
        assertEquals("next", clipboard.data.decodeToString())
        session.attachClipboard(clipboard, TerminalClipboardPolicy { _, _ -> error("Permission service failed") })
        session.write(query)
        assertEquals(emptyReply.repeat(2), replies.toString())
    }

    @Test fun queuedPermissionsHaveBoundedCountAndResetRecovers() {
        val session = TerminalSession()
        val clipboard = Clipboard()
        var approve: ((Boolean) -> Unit)? = null
        var asks = 0
        session.attachClipboard(clipboard, TerminalClipboardPolicy { _, done -> asks++; approve = done })
        session.write(set("queued").repeat(1000))
        assertEquals(1, asks)
        repeat(16) { approve!!(true) }
        assertEquals(16, clipboard.writes); assertEquals(16, asks)
        session.reset(); session.write(set("after reset")); approve!!(true)
        assertEquals("after reset", clipboard.data.decodeToString())
    }

    @Test fun largeCaptureIsBoundedCanceledAndDoesNotRaiseOtherStringLimits() {
        val session = TerminalSession()
        val clipboard = Clipboard()
        session.attachClipboard(clipboard, TerminalClipboardPolicy.ALLOW)
        val large = "x".repeat(150_000)
        session.write(set(large))
        assertEquals(large, clipboard.data.decodeToString())
        session.write("\u001b]52;c;")
        repeat(128) { session.write("A".repeat(65536)) }
        session.write("\u0007OK")
        assertEquals(1, clipboard.writes); assertTrue(session.screenLine(0).text().startsWith("OK"))
        session.write("\u001b]52;c;" + "A".repeat(10000) + "\u0018")
        session.write("\u001b]2;" + "x".repeat(VtParser.STRING_LIMIT + 1) + "\u0007")
        assertEquals("", session.title); assertEquals(1, clipboard.writes)
        session.write(set("recovered"))
        assertEquals("recovered", clipboard.data.decodeToString())
    }
}
