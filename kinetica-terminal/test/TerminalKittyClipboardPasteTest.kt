package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalKittyClipboardPasteTest {
    private class Host : KittyClipboardHost {
        val accesses = mutableListOf<KittyClipboardAccess>()
        val reads = mutableListOf<Triple<TerminalClipboardLocation, List<String>, Boolean>>()
        var available = listOf("text/plain", "image/png")
        var allowed = true
        var readHook: (((KittyClipboardReadResult) -> Unit) -> Unit)? = null
        override fun authorize(access: KittyClipboardAccess, complete: (KittyClipboardDecision) -> Unit) {
            accesses += access; complete(KittyClipboardDecision(allowed))
        }
        override fun read(location: TerminalClipboardLocation, mimes: List<String>, list: Boolean, complete: (KittyClipboardReadResult) -> Unit) {
            reads += Triple(location, mimes, list)
            val hook = readHook
            if (hook != null) hook(complete)
            else complete(KittyClipboardReadResult(KittyClipboardStatus.OK, available,
                mimes.map { KittyClipboardContent(it, "data".encodeToByteArray()) }))
        }
        override fun write(location: TerminalClipboardLocation, contents: List<KittyClipboardContent>, complete: (KittyClipboardStatus) -> Unit) {
            complete(KittyClipboardStatus.DONE)
        }
    }
    private class Harness(val host: Host = Host(), val autoAck: Boolean = true, queueLimit: Int = 16, replyLimit: Int = 16 * 1024 * 1024) {
        val output = mutableListOf<String>()
        val acknowledgements = ArrayDeque<() -> Unit>()
        val results = mutableListOf<KittyClipboardStatus>()
        var entropy = true
        var entropyCalls = 0
        var failDelivery = false
        val binding = TerminalKittyClipboardBinding(host, { packets, done ->
            if (failDelivery) error("Transport write failed")
            output += packets.joinToString("")
            if (autoAck) done() else acknowledgements.addLast(done)
        }, queueLimit = queueLimit, replyLimit = replyLimit, random = { bytes ->
            entropyCalls++; bytes.fill(0); entropy
        })
        fun paste(location: TerminalClipboardLocation = TerminalClipboardLocation.STANDARD) = binding.paste(location) { results += it }
        fun accept(body: String) = assertTrue(binding.accept(body, 0))
    }
    private val password = "2".repeat(22)
    private fun b64(value: String) = Base64.encode(value.encodeToByteArray())
    private fun osc(value: String) = "\u001b]5522;$value\u001b\\"
    private fun read(types: String = "text/plain", name: Boolean = true): String =
        "type=read:pw=${b64(password)}" + (if (name) ":name=${b64("Paste event")}" else "") + ";${b64(types)}"
    private fun event(listing: String?, primary: Boolean = false): String {
        val pw = ":pw=${b64(password)}"
        return osc("type=read:status=OK" + (if (primary) ":loc=primary" else "") + pw) +
            osc("type=read:status=DATA:mime=Lg==$pw" + (listing?.let { ";${b64(it)}" } ?: "")) +
            osc("type=read:status=DONE$pw")
    }

    @Test fun pasteEventsAndOneTimeGrantsMatchOriginalGhosttyHandler() {
        var checked = 0
        for (encoded in terminalKittyClipboardCoreFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val input = case["paste"]?.jsonObject ?: continue
            val h = Harness(Host().apply { available = input.getValue("available").jsonArray.map { it.jsonPrimitive.content } })
            h.entropy = input["entropy"]?.jsonPrimitive?.boolean ?: true
            assertTrue(h.paste(TerminalClipboardLocation.valueOf(input.getValue("location").jsonPrimitive.content.uppercase())))
            val output = h.output.joinToString("")
            h.accept(read()); val first = h.host.accesses.last().granted
            h.accept(read()); val second = h.host.accesses.last().granted
            val actual = buildJsonObject {
                put("status", h.results.single().name); put("replies", output)
                put("first", first); put("second", second); put("calls", h.entropyCalls)
            }
            assertEquals(case.getValue("result"), actual, case.getValue("name").jsonPrimitive.content)
            checked++
        }
        assertEquals(27, checked)
    }

    @Test fun pasteListsOnlyMetadataAndGrantIsUsableOnceAfterDelivery() {
        val h = Harness(autoAck = false)
        assertTrue(h.paste())
        assertEquals(listOf(Triple(TerminalClipboardLocation.STANDARD, emptyList<String>(), true)), h.host.reads)
        assertTrue(h.host.accesses.isEmpty(), "A trusted user gesture does not request application authorization")
        assertEquals(listOf(event("text/plain image/png\n")), h.output)
        assertTrue(h.results.isEmpty())
        h.accept(read())
        assertTrue(h.host.accesses.isEmpty(), "Follow-up I/O waits for the complete event's receipt")
        val firstReceipt = h.acknowledgements.removeFirst()
        firstReceipt(); firstReceipt()
        assertEquals(listOf(KittyClipboardStatus.DONE), h.results)
        assertTrue(h.host.accesses.single().granted)
        h.acknowledgements.removeFirst()()
        h.accept(read())
        assertFalse(h.host.accesses.last().granted)
        h.acknowledgements.removeFirst()()
        assertEquals(0, h.binding.pendingRequests)
    }

    @Test fun targetsAndMissingNameDoNotConsumeGrantButWrongDirectionDoes() {
        val h = Harness()
        assertTrue(h.paste())
        h.accept(read(".")); assertFalse(h.host.accesses.last().granted)
        h.accept(read(name = false)); assertFalse(h.host.accesses.last().granted)
        h.accept(read()); assertTrue(h.host.accesses.last().granted)
        h.accept(read()); assertFalse(h.host.accesses.last().granted)
        assertTrue(h.paste())
        h.accept("type=write:pw=${b64(password)}:name=${b64("Paste event")}")
        h.accept("type=wdata")
        assertFalse(h.host.accesses.last().granted)
        h.accept(read()); assertFalse(h.host.accesses.last().granted)
    }

    @Test fun oneTimeGrantDoesNotOverrideAnExplicitHostDenial() {
        val h = Harness(Host().apply { allowed = false })
        assertTrue(h.paste())
        h.accept(read())
        assertTrue(h.host.accesses.single().granted)
        assertEquals(osc("type=read:status=EPERM"), h.output.last())
        assertEquals(1, h.host.reads.size, "Denied follow-up cannot read clipboard data")
        h.accept(read()); assertFalse(h.host.accesses.last().granted)
    }

    @Test fun metadataAndEntropyFailuresSendNoEventAndIssueNoGrant() {
        val h = Harness()
        h.entropy = false
        assertTrue(h.paste())
        assertEquals(listOf(KittyClipboardStatus.EIO), h.results)
        assertTrue(h.host.reads.isEmpty()); assertTrue(h.output.isEmpty())
        h.entropy = true
        for (status in listOf(KittyClipboardStatus.EPERM, KittyClipboardStatus.ENOSYS, KittyClipboardStatus.EBUSY, KittyClipboardStatus.EIO, KittyClipboardStatus.EINVAL)) {
            h.host.readHook = { done -> done(KittyClipboardReadResult(status)); done(KittyClipboardReadResult(KittyClipboardStatus.OK)) }
            assertTrue(h.paste())
            assertEquals(if (status == KittyClipboardStatus.EINVAL) KittyClipboardStatus.EIO else status, h.results.last())
        }
        h.host.readHook = { error("Metadata provider failed") }
        assertTrue(h.paste()); assertEquals(KittyClipboardStatus.EIO, h.results.last())
        assertEquals(7, h.results.size); assertTrue(h.output.isEmpty())
        h.host.readHook = null
        h.accept(read()); assertFalse(h.host.accesses.single().granted)
    }

    @Test fun canceledMetadataAndDeliveryCannotRestoreOrConsumeNewGrants() {
        val h = Harness(autoAck = false)
        var metadata: ((KittyClipboardReadResult) -> Unit)? = null
        h.host.readHook = { metadata = it }
        assertTrue(h.paste()); h.binding.reset()
        assertEquals(listOf(KittyClipboardStatus.EBUSY), h.results)
        assertNotNull(metadata)(KittyClipboardReadResult(KittyClipboardStatus.OK, listOf("late")))
        assertTrue(h.output.isEmpty())
        h.host.readHook = null
        assertTrue(h.paste())
        val staleReceipt = h.acknowledgements.removeFirst()
        h.binding.reset()
        h.accept(read()); assertFalse(h.host.accesses.last().granted)
        staleReceipt()
        assertEquals(1, h.binding.pendingRequests, "Old receipt cannot release a newer operation")
        h.acknowledgements.removeFirst()()
        assertEquals(listOf(KittyClipboardStatus.EBUSY, KittyClipboardStatus.EBUSY), h.results)
    }

    @Test fun queuedGesturesAreBoundedAndOnlyReadWhenTheirTurnStarts() {
        val h = Harness(autoAck = false, queueLimit = 2)
        assertTrue(h.paste()); assertTrue(h.paste())
        assertFalse(h.paste())
        assertEquals(1, h.entropyCalls); assertEquals(1, h.host.reads.size)
        assertEquals(2, h.binding.pendingRequests); assertEquals(0, h.binding.pendingBytes)
        h.acknowledgements.removeFirst()()
        assertEquals(2, h.entropyCalls); assertEquals(2, h.host.reads.size)
        h.acknowledgements.removeFirst()()
        assertEquals(listOf(KittyClipboardStatus.DONE, KittyClipboardStatus.DONE), h.results)
        h.binding.dispose()
        assertFalse(h.binding.hasHost); assertFalse(h.paste())
        assertFalse(TerminalKittyClipboardBinding(null, { _, _ -> fail("No host") }).paste(TerminalClipboardLocation.STANDARD) { fail("Not admitted") })
    }

    @Test fun listingIsBoundedAndPrimaryEmptyAndOversizedEventsHaveExactFraming() {
        val h = Harness(Host().apply { available = (0..20).map { "application/x-$it" } })
        assertTrue(h.paste(TerminalClipboardLocation.SELECTION))
        assertEquals(event((0..15).joinToString(" ") { "application/x-$it" } + "\n", primary = true), h.output.single())
        h.host.available = emptyList()
        assertTrue(h.paste(TerminalClipboardLocation.PRIMARY))
        assertEquals(event(null, primary = true), h.output.last())
        val limited = Harness(Host().apply { available = listOf("x".repeat(4095)) }, replyLimit = 1024)
        assertTrue(limited.paste())
        assertEquals(listOf(KittyClipboardStatus.EIO), limited.results)
        assertTrue(limited.output.isEmpty())
        limited.host.available = emptyList()
        limited.accept(read()); assertFalse(limited.host.accesses.single().granted)
    }

    @Test fun throwingReplySinkDisposesBindingAndCancelsAllAcceptedGestures() {
        val h = Harness(autoAck = false)
        assertTrue(h.paste()); assertTrue(h.paste())
        h.failDelivery = true
        h.acknowledgements.removeFirst()()
        assertEquals(listOf(KittyClipboardStatus.DONE, KittyClipboardStatus.EBUSY), h.results)
        assertFalse(h.binding.hasHost)
        assertEquals(0, h.binding.pendingRequests)
        h.accept(read())
        assertTrue(h.host.accesses.isEmpty(), "Disposed binding cannot grant a read after failed delivery")
    }

    @Test fun pasteDoesNotCommitOrDiscardAnUnfinishedClipboardWrite() {
        val h = Harness()
        h.accept("type=write:id=transaction")
        h.accept("type=wdata:mime=dGV4dC9wbGFpbg==;aGVsbG8=")
        assertTrue(h.paste())
        assertTrue(h.host.accesses.isEmpty())
        h.accept("type=wdata")
        assertEquals(KittyClipboardDirection.WRITE, h.host.accesses.single().direction)
        assertEquals(osc("type=write:status=DONE:id=transaction"), h.output.last())
    }
}
