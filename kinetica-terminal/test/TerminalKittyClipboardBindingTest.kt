package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalKittyClipboardBindingTest {
    private class Host : KittyClipboardHost {
        var contents = listOf(KittyClipboardContent("text/plain", "hello".encodeToByteArray()),
            KittyClipboardContent("text/html", "<b>hello</b>".encodeToByteArray()), KittyClipboardContent("image/png", byteArrayOf(0, -1)))
        var allowed = true
        var remember = false
        var twice = false
        var readStatus = KittyClipboardStatus.OK
        var writeStatus = KittyClipboardStatus.DONE
        val accesses = mutableListOf<KittyClipboardAccess>()
        val writes = mutableListOf<List<KittyClipboardContent>>()
        var authorizeHook: ((KittyClipboardAccess, (KittyClipboardDecision) -> Unit) -> Unit)? = null
        var readHook: (((KittyClipboardReadResult) -> Unit) -> Unit)? = null
        override fun authorize(access: KittyClipboardAccess, complete: (KittyClipboardDecision) -> Unit) {
            accesses.add(access)
            val hook = authorizeHook
            if (hook != null) hook(access, complete) else complete(KittyClipboardDecision(allowed, remember))
        }
        override fun read(location: TerminalClipboardLocation, mimes: List<String>, list: Boolean, complete: (KittyClipboardReadResult) -> Unit) {
            val hook = readHook
            if (hook != null) hook(complete) else complete(KittyClipboardReadResult(readStatus, contents.map { it.mime }, contents))
            if (twice) complete(KittyClipboardReadResult(KittyClipboardStatus.EPERM))
        }
        override fun write(location: TerminalClipboardLocation, contents: List<KittyClipboardContent>, complete: (KittyClipboardStatus) -> Unit) {
            if (writeStatus == KittyClipboardStatus.DONE) { this.contents = contents; writes.add(contents) }
            complete(writeStatus)
            if (twice) complete(KittyClipboardStatus.EPERM)
        }
    }
    private fun read(types: String = "text/plain", metadata: String = "") = "type=read$metadata;${Base64.encode(types.encodeToByteArray())}"
    private fun data(text: String) = "type=wdata:mime=dGV4dC9wbGFpbg==;${Base64.encode(text.encodeToByteArray())}"
    private fun TerminalKittyClipboardBinding.accept(body: String) = assertTrue(accept(body, 0))

    @Test fun dispatchEffectsAndWireRepliesMatchOriginalGhostty() {
        for (encoded in terminalKittyClipboardCoreFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val events = case["dispatch"]?.jsonArray ?: continue
            val host = Host().apply {
                allowed = case["allowed"]?.jsonPrimitive?.boolean ?: true
                remember = case["remember"]?.jsonPrimitive?.boolean ?: false
                twice = case["twice"]?.jsonPrimitive?.boolean ?: false
                readStatus = KittyClipboardStatus.valueOf(case["read_status"]?.jsonPrimitive?.content ?: "OK")
                writeStatus = KittyClipboardStatus.valueOf(case["write_status"]?.jsonPrimitive?.content ?: "DONE")
            }
            val replies = StringBuilder()
            val binding = TerminalKittyClipboardBinding(if (case["host"]?.jsonPrimitive?.boolean == false) null else host,
                reply = { packets, complete -> packets.forEach(replies::append); complete() },
                writeLimit = case["limit"]?.jsonPrimitive?.int ?: 64 * 1024 * 1024)
            for ((index, value) in events.withIndex()) {
                replies.clear(); host.accesses.clear(); host.writes.clear()
                val event = value.jsonObject
                if (event["reset"]?.jsonPrimitive?.boolean == true) binding.reset()
                event["body"]?.jsonPrimitive?.content?.let { body ->
                    assertTrue(binding.accept(body, if (event["terminator"]?.jsonPrimitive?.content == "bel") 7 else 0))
                }
                val actual = buildJsonObject {
                    put("replies", replies.toString())
                    put("accesses", buildJsonArray { for (a in host.accesses) add(buildJsonObject {
                        put("direction", a.direction.name.lowercase()); put("location", a.location.name.lowercase()); put("name", a.name)
                        put("mimes", JsonArray(a.mimeTypes.map(::JsonPrimitive))); put("list", a.targets)
                        put("granted", a.granted); put("can_remember", a.canRemember)
                    }) })
                    put("writes", buildJsonArray { for (contents in host.writes) add(buildJsonArray {
                        for (content in contents) add(buildJsonObject { put("mime", content.mime); put("hex", content.data.toHexString()) })
                    }) })
                }
                assertEquals(case.getValue("snapshots").jsonArray[index], actual, "${case.getValue("name")} event $index")
            }
            binding.dispose()
        }
    }

    @Test fun permissionAndReplyAcknowledgementsSerializeHostEffects() {
        val host = Host()
        val decisions = ArrayDeque<(KittyClipboardDecision) -> Unit>()
        host.authorizeHook = { _, done -> decisions.addLast(done) }
        val sent = mutableListOf<Sequence<String>>()
        val delivered = ArrayDeque<() -> Unit>()
        val binding = TerminalKittyClipboardBinding(host, { packets, done -> sent.add(packets); delivered.addLast(done) })
        binding.accept("type=write:id=one"); binding.accept(data("new")); binding.accept("type=wdata")
        binding.accept(read())
        assertEquals(1, decisions.size); assertEquals(0, host.writes.size)
        val approve = decisions.removeFirst()
        approve(KittyClipboardDecision(true)); approve(KittyClipboardDecision(true))
        assertEquals(1, host.writes.size); assertEquals(0, decisions.size)
        assertContains(sent[0].joinToString(""), "status=DONE:id=one")
        val ack = delivered.removeFirst(); ack(); ack()
        assertEquals(1, decisions.size)
        decisions.removeFirst()(KittyClipboardDecision(true))
        assertContains(sent[1].joinToString(""), Base64.encode("new".encodeToByteArray()))
        delivered.removeFirst()()
        assertEquals(0, binding.pendingRequests); assertEquals(0, binding.pendingBytes)
    }

    @Test fun queueCountAndByteLimitsRejectBeforeEffectsAndPermitRetry() {
        val host = Host()
        val deliveries = ArrayDeque<() -> Unit>()
        var ready = 0
        val binding = TerminalKittyClipboardBinding(host, { packets, done -> packets.toList(); deliveries.addLast(done) },
            ready = { ready++ }, writeLimit = 4, queuedBytesLimit = 4, queueLimit = 2)
        binding.accept("type=write"); binding.accept(data("1234")); binding.accept("type=wdata")
        binding.accept("type=write"); binding.accept(data("5"))
        assertFalse(binding.accept("type=wdata", 0)); assertEquals(1, host.writes.size)
        deliveries.removeFirst()()
        binding.accept("type=wdata")
        assertEquals("5", host.contents.single().data.decodeToString())
        binding.accept(read(""))
        assertFalse(binding.accept(read(""), 0)); assertEquals(2, binding.pendingRequests)
        deliveries.removeFirst()(); deliveries.removeFirst()()
        assertEquals(3, ready); assertEquals(0, binding.pendingBytes)
    }

    @Test fun resetAndDisposalInvalidateAuthorizationsReadsAndLazyReplies() {
        val host = Host()
        var approve: ((KittyClipboardDecision) -> Unit)? = null
        host.authorizeHook = { _, done -> approve = done }
        var response: Sequence<String>? = null
        var ack: (() -> Unit)? = null
        val binding = TerminalKittyClipboardBinding(host, { packets, done -> response = packets; ack = done })
        binding.accept("type=write"); binding.accept(data("stale")); binding.accept("type=wdata")
        binding.reset(); approve!!(KittyClipboardDecision(true)); assertTrue(host.writes.isEmpty())
        host.authorizeHook = null
        var complete: ((KittyClipboardReadResult) -> Unit)? = null
        host.readHook = { complete = it }
        binding.accept(read())
        val old = complete!!
        binding.reset(); old(KittyClipboardReadResult(KittyClipboardStatus.OK, contents = host.contents))
        assertNull(response)
        host.readHook = null
        binding.accept(read())
        val iterator = response!!.iterator()
        assertTrue(iterator.hasNext())
        binding.dispose()
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
        ack!!(); assertEquals(0, binding.pendingRequests)
    }

    @Test fun oversizedReadsFailBeforeOkAndHostArraysAreSnapshotted() {
        val host = Host()
        val output = mutableListOf<Sequence<String>>()
        val done = ArrayDeque<() -> Unit>()
        val binding = TerminalKittyClipboardBinding(host, { packets, ack -> output.add(packets); done.addLast(ack) }, readLimit = 5)
        binding.accept(read("text/plain text/plain"))
        assertEquals("\u001b]5522;type=read:status=EIO\u001b\\", output.removeFirst().joinToString("")); done.removeFirst()()
        binding.accept(read())
        host.contents.first().data.fill(0)
        assertContains(output.removeFirst().joinToString(""), "aGVsbG8="); done.removeFirst()()
    }

    @Test fun hostFailuresAndReplyFailuresDoNotRepeatEffects() {
        val host = Host().apply { authorizeHook = { _, _ -> error("permission service failed") } }
        val output = StringBuilder()
        val binding = TerminalKittyClipboardBinding(host, { packets, done -> packets.forEach(output::append); done() })
        binding.accept(read()); assertContains(output.toString(), "status=EIO")
        host.authorizeHook = null
        host.readHook = { done -> done(KittyClipboardReadResult(KittyClipboardStatus.EBUSY)); error("late failure") }
        output.clear(); binding.accept(read()); assertEquals(1, "status=".toRegex().findAll(output).count())
        val broken = TerminalKittyClipboardBinding(host, { _, _ -> error("transport failed") })
        broken.accept(read()); assertEquals(0, broken.pendingRequests)
        val accesses = host.accesses.size
        broken.accept(read()); assertEquals(accesses, host.accesses.size)
    }

    @Test fun encodedReplyBudgetIncludesChunkPaddingMetadataAndListings() {
        for (size in listOf(0, 1, 4095, 4096, 4097, 8192)) {
            for (terminator in listOf(0, 7)) {
                for (available in listOf(emptyList(), listOf("text/plain", "画像/png"), listOf("x".repeat(4096)))) {
                    val host = Host()
                    val content = KittyClipboardContent("text/plain", ByteArray(size) { it.toByte() })
                    host.readHook = { done -> done(KittyClipboardReadResult(KittyClipboardStatus.OK, available, listOf(content))) }
                    val id = "r".repeat(512)
                    val expected = KittyClipboardReadSuccess(primary = true, id = id, list = true,
                        available = available, contents = listOf(content), terminator = terminator).packets().joinToString("")
                    assertTrue(expected.length > 1024)
                    for (limit in listOf(expected.length, expected.length - 1)) {
                        val output = StringBuilder()
                        val binding = TerminalKittyClipboardBinding(host,
                            { packets, done -> packets.forEach(output::append); done() }, replyLimit = limit)
                        assertTrue(binding.accept(read(". text/plain", ":loc=primary:id=$id"), terminator))
                        if (limit == expected.length) assertEquals(expected, output.toString(), "size=$size, terminator=$terminator")
                        else {
                            assertContains(output.toString(), "status=EIO")
                            assertFalse(output.contains("status=OK"))
                        }
                        assertEquals(0, binding.pendingBytes)
                    }
                }
            }
        }
    }
}
