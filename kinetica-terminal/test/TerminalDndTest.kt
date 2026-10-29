package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalDndTest {
    @Test fun originalCommandStateAndResponsesMatchEveryCheckpoint() {
        for (encoded in terminalDndFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val wire = case["wire"]?.jsonPrimitive?.boolean == true
            for (feed in 0..if (wire) 1 else 0) {
            val session = if (wire) TerminalSession(columns = 12, rows = 6) else null
            val core = session?.kittyDnd ?: TerminalKittyDnd()
            val replies = mutableListOf<ByteArray>()
            val events = mutableListOf<TerminalDndEvent>()
            session?.onInput { replies += it.toByteArray() }
            session?.onDnd { events += it }
            val expected = case.getValue("snapshots").jsonArray
            case.getValue("events").jsonArray.forEachIndexed { index, value ->
                val step = value.jsonObject
                replies.clear(); events.clear()
                fun int(key: String, default: Int) = step[key]?.jsonPrimitive?.int ?: default
                fun bytes(obj: JsonObject, key: String, fill: Int): ByteArray {
                    val count = obj["count"]?.jsonPrimitive?.int ?: 0
                    return if (count > 0) ByteArray(count) { (obj["fill"]?.jsonPrimitive?.int ?: fill).toByte() }
                    else (obj[key]?.jsonPrimitive?.content ?: "").hexToByteArray()
                }
                val metadata = step["metadata"]?.jsonPrimitive?.content
                var result = if (step["wire"] != null) {
                    fun write(part: ByteArray) {
                        val size = if (feed == 0) part.size.coerceAtLeast(1) else if (part.size < 65536) 1 else 4093
                        var offset = 0
                        while (offset < part.size) {
                            val length = minOf(size, part.size - offset)
                            session!!.write(part, offset, length); offset += length
                        }
                    }
                    write(step.getValue("wire").jsonPrimitive.content.hexToByteArray())
                    write(ByteArray(int("count", 0)) { int("fill", 97).toByte() })
                    write((step["tail"]?.jsonPrimitive?.content ?: "").hexToByteArray())
                    TerminalDndResult()
                } else if (metadata != null) core.command(metadata, bytes(step, "payload", 97),
                    if (step["bel"]?.jsonPrimitive?.boolean == true) 7 else 27)
                else {
                    val position = TerminalDndPosition(step["x"]?.jsonPrimitive?.long ?: 2,
                        step["y"]?.jsonPrimitive?.long ?: 1, int("X", 20), int("Y", 18), int("operations", 1))
                    when (step.getValue("action").jsonPrimitive.content) {
                        "reset" -> { core.reset(); TerminalDndResult() }
                        "dispose" -> { core.dispose(); TerminalDndResult() }
                        "leave" -> core.leave()
                        "move" -> core.move(position, step.getValue("mimes").jsonArray.map { it.jsonPrimitive.content.encodeToByteArray() })
                        "drop" -> core.drop(position, step.getValue("items").jsonArray.map {
                            val item = it.jsonObject
                            TerminalDndItem(item.getValue("mime").jsonPrimitive.content.encodeToByteArray(), bytes(item, "hex", 0))
                        }) ?: TerminalDndResult()
                        else -> error("Unknown event")
                    }
                }
                if (session != null) {
                    if (step["wire"] == null) session.acceptDndResult(result)
                    assertTrue(events.size <= 1)
                    result = TerminalDndResult(events.singleOrNull(), replies.asSequence())
                }
                val actual = buildJsonObject {
                    put("parsed", metadata?.let { meta(TerminalDndMetadata.parse(it)) } ?: JsonNull)
                    put("event", result.event?.name?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
                    put("replies", result.packets.joinToString("") { it.toHexString() })
                    put("state", snapshot(core.state))
                }
                assertEquals(expected[index], actual, "${case["name"]} step $index feed $feed")
            }
            }
        }
    }

    @Test fun dropOwnsItsDataAndLazyRepliesSurviveReplacementWithoutUnboundedPackets() {
        val core = TerminalKittyDnd(maxDropBytes = 7000)
        core.command("t=a:i=4")
        val position = TerminalDndPosition(0, 0, 0, 0, 1)
        val data = ByteArray(6145) { 65 }; val mime = "text/plain".encodeToByteArray()
        assertNotNull(core.drop(position, listOf(TerminalDndItem(mime, data))))
        data.fill(66); mime.fill(67)
        assertEquals("text/plain", core.state!!.items!!.single().mime.decodeToString())
        val packets = core.command("t=r:x=1").packets
        assertNull(core.drop(position, listOf(TerminalDndItem(mime, ByteArray(7001)))))
        assertEquals(6145, core.state!!.items!!.single().data.size)
        core.dispose()
        val frames = packets.toList()
        assertEquals(4, frames.size)
        assertTrue(frames.all { it.size < 4200 })
        val decoded = frames.dropLast(1).flatMap { frame ->
            val text = frame.decodeToString()
            Base64.decode(text.substringAfter(';').substringAfter(';').dropLast(2)).toList()
        }.toByteArray()
        assertContentEquals(ByteArray(6145) { 65 }, decoded)
        assertEquals("\u001b]72;t=r:x=1:i=4\u001b\\", frames.last().decodeToString())
    }

    @Test fun moveOwnsMimeBytesAndRetainsPayloadUntilTheOfferChanges() {
        val core = TerminalKittyDnd()
        core.command("t=a:i=4")
        val position = TerminalDndPosition(0, 0, 0, 0, 1)
        val mime = "text/plain".encodeToByteArray()
        val first = core.move(position, listOf(mime)).packets
        val payload = core.state!!.offeredPayload
        core.move(position.copy(x = 1), listOf(mime.copyOf()))
        assertSame(payload, core.state!!.offeredPayload)
        mime.fill(65)
        core.move(position, listOf(mime))
        assertNotSame(payload, core.state!!.offeredPayload)
        core.leave()
        assertTrue(core.state!!.offeredPayload.isEmpty())
        assertEquals("\u001b]72;t=m:x=0:y=0:X=0:Y=0:o=1:i=4:m=0;text/plain \u001b\\",
            first.single().decodeToString())
    }

    private fun meta(value: TerminalDndMetadata?): JsonElement = value?.let { buildJsonObject {
        put("kind", it.type?.code?.let(::JsonPrimitive) ?: JsonNull); put("more", it.more)
        put("id", it.clientId); put("op", it.operation); put("x", it.x); put("y", it.y); put("X", it.pixelX); put("Y", it.pixelY)
    } } ?: JsonNull
    private fun runs(bytes: ByteArray): JsonArray = buildJsonArray {
        var i = 0
        while (i < bytes.size) {
            val byte = bytes[i]; val start = i++
            while (i < bytes.size && bytes[i] == byte) i++
            add(buildJsonObject { put("byte", byte.toInt() and 255); put("count", i - start) })
        }
    }
    private fun snapshot(state: TerminalKittyDnd.State?): JsonElement = state?.let { s -> buildJsonObject {
        put("id", s.clientId); put("chunk", meta(s.chunk))
        put("registered", runs(s.registered.snapshot())); put("accepted_mimes", runs(s.acceptedMimes.snapshot()))
        put("hovered", s.hovered); put("dropped", s.dropped); put("accepting", s.accepting)
        put("accepted", s.accepted?.let(::JsonPrimitive) ?: JsonNull)
        put("client_accepted", s.clientAccepted?.let(::JsonPrimitive) ?: JsonNull)
        put("offered", s.offered?.let { values -> buildJsonArray { values.forEach { add(it.toHexString()) } } } ?: JsonNull)
        put("items", s.items?.let { values -> buildJsonArray { values.forEach { item ->
            add(buildJsonObject { put("mime", item.mime.toHexString()); put("hex", item.data.toHexString()) })
        } } } ?: JsonNull)
    } } ?: JsonNull
}
