package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalKittyClipboardResponseTest {
    private fun cases(): Sequence<JsonObject> = terminalKittyClipboardCoreFixtures().asSequence().map {
        Json.parseToJsonElement(Base64.decode(it).decodeToString()).jsonObject
    }

    @Test fun statusReadAndPasteBytesMatchOriginalGhostty() {
        for (case in cases()) {
            val response = case["response"]?.jsonObject ?: continue
            fun string(key: String, default: String = "") = response[key]?.jsonPrimitive?.contentOrNull ?: default
            fun optional(key: String) = response[key]?.jsonPrimitive?.contentOrNull
            val primary = response["primary"]?.jsonPrimitive?.boolean ?: false
            val terminator = if (string("terminator") == "bel") 7 else 0
            val kind = string("kind")
            val actual = if (kind == "packet") {
                KittyClipboardResponse(KittyClipboardOperation.valueOf(string("operation", "write").uppercase()),
                    KittyClipboardStatus.valueOf(string("status", "DONE")), primary, string("id"), optional("mime"),
                    optional("password"), string("payload").hexToByteArray(), terminator).encode()
            } else {
                val contents = response["contents"]?.jsonArray?.map { value ->
                    val content = value.jsonObject
                    KittyClipboardContent(content.getValue("mime").jsonPrimitive.content, content.getValue("hex").jsonPrimitive.content.hexToByteArray())
                }.orEmpty()
                KittyClipboardReadSuccess(primary, if (kind == "paste") "" else string("id"), optional("password"),
                    list = kind == "paste" || response["list"]?.jsonPrimitive?.boolean == true,
                    available = response["available"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    contents = contents, terminator = terminator).packets().joinToString("")
            }
            assertEquals(case.getValue("result").jsonPrimitive.content, actual, case.getValue("name").jsonPrimitive.content)
        }
    }

    @Test fun grantDirectionLifetimeCapacityAndEvictionMatchOriginalGhostty() {
        for (case in cases()) {
            val events = case["grants"]?.jsonArray ?: continue
            val grants = KittyClipboardGrants()
            for ((index, value) in events.withIndex()) {
                val event = value.jsonObject
                val password = event["password"]?.jsonPrimitive?.content.orEmpty()
                val direction = KittyClipboardDirection.valueOf((event["direction"]?.jsonPrimitive?.content ?: "read").uppercase())
                var allowed: Boolean? = null
                when (event.getValue("action").jsonPrimitive.content) {
                    "grant" -> grants.grant(password, direction, event["one_time"]?.jsonPrimitive?.boolean ?: false)
                    "use" -> allowed = grants.use(password, direction)
                    "clear" -> grants.clear()
                    else -> error("Unknown grant action")
                }
                val actual = buildJsonObject { put("allowed", allowed); put("count", grants.size) }
                assertEquals(case.getValue("snapshots").jsonArray[index], actual, "${case.getValue("name")} event $index")
            }
        }
    }

    @Test fun dataIsEncodedLazilyInBoundedPacketsAndEmptyRepresentationsAreOmitted() {
        val payload = ByteArray(100_000) { it.toByte() }
        val iterator = KittyClipboardReadSuccess(contents = listOf(
            KittyClipboardContent("application/octet-stream", payload), KittyClipboardContent("empty", byteArrayOf()),
        )).packets().iterator()
        assertEquals("\u001b]5522;type=read:status=OK\u001b\\", iterator.next())
        var received = 0
        while (iterator.hasNext()) {
            val packet = iterator.next()
            assertTrue(packet.length < 5600)
            if (packet.contains("status=DATA")) {
                assertFalse(packet.contains("ZW1wdHk="))
                val bytes = Base64.decode(packet.substringAfterLast(';').removeSuffix("\u001b\\"))
                assertTrue(bytes.size in 1..4096)
                assertContentEquals(payload.copyOfRange(received, received + bytes.size), bytes)
                received += bytes.size
            } else assertEquals("\u001b]5522;type=read:status=DONE\u001b\\", packet)
        }
        assertEquals(payload.size, received)
    }
}
