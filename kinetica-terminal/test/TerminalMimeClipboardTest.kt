package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalMimeClipboardTest {
    private class Host : TerminalMimeClipboard {
        var contents = listOf(TerminalMimeClipboardContent("text/plain", "hello".encodeToByteArray()),
            TerminalMimeClipboardContent("text/html", "<b>hello</b>".encodeToByteArray()),
            TerminalMimeClipboardContent("image/png", byteArrayOf(0, -1)))
        var readResult = TerminalClipboardResult.SUCCESS
        var writeResult = TerminalClipboardResult.SUCCESS
        var twice = false
        var reads = 0
        val writes = mutableListOf<List<TerminalMimeClipboardContent>>()
        var readHook: (((TerminalMimeClipboardRead) -> Unit) -> Unit)? = null
        override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
            reads++
            val hook = readHook
            if (hook != null) hook(complete)
            else complete(TerminalMimeClipboardRead(readResult, contents.map { it.mime }, contents))
            if (twice) complete(TerminalMimeClipboardRead(TerminalClipboardResult.DENIED))
        }
        override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
            if (writeResult == TerminalClipboardResult.SUCCESS) { this.contents = contents; writes.add(contents) }
            complete(writeResult)
            if (twice) complete(TerminalClipboardResult.DENIED)
        }
    }
    private class Harness(val session: TerminalSession = TerminalSession()) : TerminalByteTransport, TerminalScheduler {
        val jobs = ArrayDeque<() -> Unit>()
        val output = mutableListOf<Byte>()
        var writable = true
        var writeLimit = Int.MAX_VALUE
        var rejected = 0
        val connection = TerminalStreamConnection(session, this, this, { rejected++ }, { throw it })
        override fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable {
            jobs.addLast(action)
            return TerminalDisposable { jobs.remove(action) }
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            val size = if (writable) minOf(length, writeLimit) else 0
            output.addAll(bytes.copyOfRange(offset, offset + size).toList())
            return size
        }
        override fun resize(columns: Int, rows: Int) {}
        override fun consumed(bytes: Int) {}
        fun feed(text: String, chunk: Int = Int.MAX_VALUE) {
            val bytes = text.encodeToByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(chunk, bytes.size - offset)
                assertTrue(connection.receive(bytes, offset, size))
                offset += size
                drain()
            }
        }
        fun drain() {
            var turns = 0
            while (jobs.isNotEmpty()) {
                check(turns++ < 20_000) { "Transport failed to yield or finish" }
                jobs.removeFirst()()
                if (!writable && connection.pendingInputBytes > 0) return
            }
        }
        fun wire(): String = output.toByteArray().decodeToString()
    }
    private fun osc(body: String, bel: Boolean = false) = "\u001b]5522;$body" + if (bel) "\u0007" else "\u001b\\"
    private fun read(types: String = "text/plain", metadata: String = "") = osc("type=read$metadata;${Base64.encode(types.encodeToByteArray())}")
    private fun data(value: ByteArray, mime: String = "text/plain") = osc("type=wdata:mime=${Base64.encode(mime.encodeToByteArray())};${Base64.encode(value)}")

    @Test fun sessionDispatchMatchesOriginalGhosttyThroughBoundedTransport() {
        fun result(status: String) = when (status) {
            "OK", "DONE" -> TerminalClipboardResult.SUCCESS
            "EPERM" -> TerminalClipboardResult.DENIED
            "ENOSYS" -> TerminalClipboardResult.UNSUPPORTED
            "EBUSY" -> TerminalClipboardResult.BUSY
            "EINVAL" -> TerminalClipboardResult.INVALID_DATA
            else -> TerminalClipboardResult.IO_ERROR
        }
        for (encoded in terminalKittyClipboardCoreFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val events = case["dispatch"]?.jsonArray ?: continue
            for (chunk in listOf(1, Int.MAX_VALUE)) {
                val h = Harness()
                val host = Host().apply {
                    readResult = result(case["read_status"]?.jsonPrimitive?.content ?: "OK")
                    writeResult = result(case["write_status"]?.jsonPrimitive?.content ?: "DONE")
                    twice = case["twice"]?.jsonPrimitive?.boolean ?: false
                }
                // The custom four-byte budget remains binding-only; sessions use production limits.
                if (case["limit"] != null) { h.connection.dispose(); continue }
                val accesses = mutableListOf<TerminalMimeClipboardRequest>()
                if (case["host"]?.jsonPrimitive?.boolean != false)
                    h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy { request, complete ->
                        accesses.add(request)
                        complete(TerminalClipboardDecision(case["allowed"]?.jsonPrimitive?.boolean ?: true,
                            case["remember"]?.jsonPrimitive?.boolean ?: false))
                    })
                try {
                    for ((index, raw) in events.withIndex()) {
                        h.output.clear(); accesses.clear(); host.writes.clear()
                        val event = raw.jsonObject
                        if (event["reset"]?.jsonPrimitive?.boolean == true) h.feed("\u001bc", chunk)
                        event["body"]?.jsonPrimitive?.content?.let {
                            h.feed(osc(it, event["terminator"]?.jsonPrimitive?.content == "bel"), chunk)
                        }
                        val actual = buildJsonObject {
                            put("replies", h.wire())
                            put("accesses", buildJsonArray { for (a in accesses) add(buildJsonObject {
                                put("direction", a.operation.name.lowercase()); put("location", a.location.name.lowercase()); put("name", a.name)
                                put("mimes", JsonArray(a.mimeTypes.map(::JsonPrimitive))); put("list", a.targets)
                                put("granted", a.granted); put("can_remember", a.canRemember)
                            }) })
                            put("writes", buildJsonArray { for (contents in host.writes) add(buildJsonArray {
                                for (content in contents) add(buildJsonObject { put("mime", content.mime); put("hex", content.data.toHexString()) })
                            }) })
                        }
                        assertEquals(case.getValue("snapshots").jsonArray[index], actual, "${case.getValue("name")}, event=$index, chunk=$chunk")
                    }
                } finally { h.connection.dispose() }
            }
        }
    }

    @Test fun defaultPolicyDeniesReadsAndWritesAndLongBinaryPacketsReachHost() {
        val h = Harness(); val host = Host()
        try {
            val denied = h.session.attachMimeClipboard(host)
            h.feed(read() + osc("type=write") + data(byteArrayOf(1)) + osc("type=wdata"))
            assertEquals(0, host.reads); assertTrue(host.writes.isEmpty())
            assertEquals(2, "status=EPERM".toRegex().findAll(h.wire()).count())
            denied.dispose(); h.output.clear()
            h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.ALLOW)
            val bytes = ByteArray(8192) { it.toByte() }
            h.feed(osc("type=write:id=binary:loc=primary") + data(bytes, "image/png") + osc("type=wdata", bel = true), 73)
            assertContentEquals(bytes, host.contents.single().data)
            assertEquals("image/png", host.contents.single().mime)
            assertEquals("\u001b]5522;type=write:status=DONE:id=binary\u0007", h.wire())
        } finally { h.connection.dispose() }
    }

    @Test fun pendingAuthorizationPausesTheNextClipboardCommandWithoutBusyPolling() {
        val h = Harness(); val host = Host()
        val decisions = ArrayDeque<(TerminalClipboardDecision) -> Unit>()
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy { _, done -> decisions.addLast(done) })
        try {
            h.feed(read().repeat(40) + "AFTER")
            assertTrue(h.session.outputPaused)
            assertTrue(h.jobs.isEmpty(), "Permission wait must not spin the scheduler")
            assertEquals("", h.session.screenLine(0).text())
            assertEquals(1, decisions.size)
            repeat(40) {
                decisions.removeFirst()(TerminalClipboardDecision(true))
                h.drain()
            }
            assertEquals(40, host.reads)
            assertEquals(40, "status=DONE".toRegex().findAll(h.wire()).count())
            assertEquals("AFTER", h.session.screenLine(0).text())
            assertFalse(h.session.outputPaused); assertEquals(0, h.rejected)
        } finally { h.connection.dispose() }
    }

    @Test fun largeRepliesAreAtomicAndSharedWithLegacyClipboardDelivery() {
        val h = Harness(); val host = Host()
        var mimeRead: ((TerminalMimeClipboardRead) -> Unit)? = null
        var textRead: ((ByteArray?) -> Unit)? = null
        host.readHook = { mimeRead = it }
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.ALLOW)
        h.session.attachClipboard(object : TerminalClipboard {
            override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) { textRead = complete }
            override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) = complete()
        }, TerminalClipboardPolicy.ALLOW)
        try {
            h.feed(read() + "\u001b]52;c;?\u0007")
            assertNull(textRead, "Legacy read must wait for the preceding MIME operation")
            h.writable = false
            val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
            mimeRead!!(TerminalMimeClipboardRead(contents = listOf(TerminalMimeClipboardContent("text/plain", bytes))))
            h.session.sendInput("KEY")
            h.drain()
            assertTrue(h.session.outputPaused)
            assertEquals(0, h.output.size)
            val expected = KittyClipboardReadSuccess(contents = listOf(KittyClipboardContent("text/plain", bytes))).packets().joinToString("")
            h.writable = true; h.writeLimit = 127; h.drain()
            textRead!!("legacy".encodeToByteArray()); h.drain()
            assertEquals(expected + "KEY\u001b]52;c;bGVnYWN5\u0007", h.wire())
            assertEquals(0, h.rejected); assertFalse(h.session.outputPaused)
        } finally { h.connection.dispose() }
    }

    @Test fun transportClosureAndHostReplacementCancelDelayedEffects() {
        val h = Harness(); val host = Host()
        var decide: ((TerminalClipboardDecision) -> Unit)? = null
        val binding = h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy { _, done -> decide = done })
        h.feed(osc("type=write") + data("stale".encodeToByteArray()) + osc("type=wdata"))
        val old = decide!!
        binding.dispose()
        old(TerminalClipboardDecision(true)); assertTrue(host.writes.isEmpty())
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy { _, done -> decide = done })
        h.feed(osc("type=write") + data("closed".encodeToByteArray()) + osc("type=wdata"))
        h.connection.dispose()
        decide!!(TerminalClipboardDecision(true)); assertTrue(host.writes.isEmpty())
        assertFalse(h.session.outputPaused)
    }

    @Test fun canceledOscDoesNotCommitAndRisPreservesOnlyUncommittedWriteAssembly() {
        val h = Harness(); val host = Host()
        h.session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.ALLOW)
        try {
            h.feed(osc("type=write:id=before") + data("valid".encodeToByteArray()))
            h.feed("\u001b]5522;type=wdata:mime=dGV4dC9wbGFpbg==;aW52YWxpZA==\u0018")
            h.feed("\u001bc" + osc("type=wdata"))
            assertEquals("valid", host.contents.single().data.decodeToString())
            assertEquals(1, host.writes.size)
            assertContains(h.wire(), "status=DONE:id=before")
        } finally { h.connection.dispose() }
    }

    @Test fun publicPrefixFeedResumesExactlyAfterDelivery() {
        val session = TerminalSession(); val host = Host()
        session.attachMimeClipboard(host, TerminalMimeClipboardPolicy.ALLOW)
        var delivered: ((Boolean) -> Unit)? = null
        var response = byteArrayOf()
        session.onInput {
            response = it.toByteArray()
            delivered = (it as TerminalInputData.ProtocolReply).trackDelivery()
        }
        var readiness = 0
        session.onOutputReady { readiness++ }
        val input = (read() + "TAIL").encodeToByteArray()
        val count = session.writeAvailable(input)
        assertTrue(count in 1 until input.size)
        assertTrue(session.outputPaused)
        assertEquals(0, session.writeAvailable(input, count))
        assertFailsWith<IllegalStateException> { session.write("must not be lost") }
        assertContains(response.decodeToString(), "status=DONE")
        delivered!!(true); delivered!!(true)
        assertTrue(readiness > 0)
        assertFalse(session.outputPaused)
        assertEquals(input.size - count, session.writeAvailable(input, count))
        assertEquals("TAIL", session.screenLine(0).text())
    }

    @Test fun replacingLegacyHostDropsQueuedRepliesWithoutOvertakingAnActiveResponse() {
        val h = Harness()
        h.writable = false
        try {
            repeat(10) { index ->
                h.session.attachClipboard(object : TerminalClipboard {
                    override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) = complete("host$index".encodeToByteArray())
                    override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) = complete()
                }, TerminalClipboardPolicy.ALLOW)
                h.feed("\u001b]52;c;?\u0007")
            }
            h.writable = true; h.drain()
            fun reply(value: String) = "\u001b]52;c;${Base64.encode(value.encodeToByteArray())}\u0007"
            assertEquals(reply("host0") + reply("host9"), h.wire())
            assertEquals(0, h.rejected)
        } finally { h.connection.dispose() }
    }

    @Test fun asynchronousWritesOrderReadsAcrossBothClipboardProtocols() {
        for (mimeWrite in listOf(false, true)) {
            val h = Harness()
            var stored = "old".encodeToByteArray()
            var commit: (() -> Unit)? = null
            var reads = 0
            h.session.attachClipboard(object : TerminalClipboard {
                override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) { reads++; complete(stored) }
                override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) {
                    commit = { stored = data; complete() }
                }
            }, TerminalClipboardPolicy.ALLOW)
            h.session.attachMimeClipboard(object : TerminalMimeClipboard {
                override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
                    reads++; complete(TerminalMimeClipboardRead(contents = listOf(TerminalMimeClipboardContent("text/plain", stored))))
                }
                override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
                    commit = { stored = contents.single().data; complete(TerminalClipboardResult.SUCCESS) }
                }
            }, TerminalMimeClipboardPolicy.ALLOW)
            try {
                if (mimeWrite) h.feed(osc("type=write") + data("new".encodeToByteArray()) + osc("type=wdata") + "\u001b]52;c;?\u0007TAIL")
                else h.feed("\u001b]52;c;bmV3\u0007" + read() + "TAIL")
                assertEquals(0, reads)
                assertTrue(h.session.outputPaused)
                assertTrue(h.jobs.isEmpty())
                commit!!(); h.drain()
                assertEquals(1, reads)
                assertContains(h.wire(), "bmV3")
                assertEquals("TAIL", h.session.screenLine(0).text())
                assertEquals(0, h.rejected)
            } finally { h.connection.dispose() }
        }
    }

    @Test fun replacingLegacyHostCancelsItsPacketHeldBehindMimeAuthorization() {
        val h = Harness()
        var oldWrites = 0
        var newWrites = 0
        fun clipboard(write: () -> Unit) = object : TerminalClipboard {
            override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) = complete(null)
            override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) { write(); complete() }
        }
        var decide: ((TerminalClipboardDecision) -> Unit)? = null
        h.session.attachMimeClipboard(Host(), TerminalMimeClipboardPolicy { _, done -> decide = done })
        h.session.attachClipboard(clipboard { oldWrites++ }, TerminalClipboardPolicy.ALLOW)
        try {
            h.feed(read() + "\u001b]52;c;c3RhbGU=\u0007")
            assertTrue(h.session.outputPaused)
            h.session.attachClipboard(clipboard { newWrites++ }, TerminalClipboardPolicy.ALLOW)
            decide!!(TerminalClipboardDecision(true)); h.drain()
            assertEquals(0, oldWrites); assertEquals(0, newWrites)
            h.feed("\u001b]52;c;ZnJlc2g=\u0007")
            assertEquals(1, newWrites)
        } finally { h.connection.dispose() }
    }
}
