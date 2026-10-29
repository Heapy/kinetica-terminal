package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class TerminalKittyClipboardCoreTest {
    private fun metadata(raw: String = "type=write") = (parseKittyClipboardMetadata(raw) as KittyClipboardParse.Valid).metadata

    @Test fun metadataAndWriteTransactionsMatchOriginalGhostty() {
        for (encoded in terminalKittyClipboardCoreFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            if ("response" in case || "grants" in case || "dispatch" in case || "otp" in case) continue
            val name = case.getValue("name").jsonPrimitive.content
            if ("metadata" in case) {
                val result = parseKittyClipboardMetadata(case.getValue("metadata").jsonPrimitive.content)
                val actual = buildJsonObject {
                    when (result) {
                        KittyClipboardParse.Dropped -> put("status", "dropped")
                        is KittyClipboardParse.Invalid -> { put("status", "invalid"); put("operation", result.operation.name.lowercase()) }
                        is KittyClipboardParse.Valid -> {
                            val meta = result.metadata
                            put("status", "valid")
                            put("metadata", buildJsonObject {
                                put("operation", meta.operation.name.lowercase()); put("location", meta.location.name.lowercase())
                                put("id", meta.id); put("mime", meta.mime); put("password", meta.password); put("name", meta.name)
                            })
                        }
                    }
                }
                assertEquals(case.getValue("result"), actual, name)
                continue
            }
            var state: KittyClipboardWrite? = KittyClipboardWrite(metadata(), case.getValue("limit").jsonPrimitive.int)
            for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                val event = value.jsonObject
                var status = "inactive"
                var bytes: Int? = null
                var contents: JsonElement = JsonNull
                state?.let { transaction ->
                    try {
                        status = "ok"
                        when (event.getValue("op").jsonPrimitive.content) {
                            "data" -> transaction.data(event.getValue("mime").jsonPrimitive.content, event.getValue("payload").jsonPrimitive.content)
                            "alias" -> transaction.alias(event.getValue("mime").jsonPrimitive.content, event.getValue("payload").jsonPrimitive.content)
                            "commit" -> {
                                contents = buildJsonArray {
                                    for (content in transaction.commit()) add(buildJsonObject { put("mime", content.mime); put("hex", content.data.toHexString()) })
                                }
                                state = null; status = "committed"
                            }
                            else -> error("Unknown operation")
                        }
                        bytes = state?.accumulatedBytes
                    } catch (error: KittyClipboardException) { status = error.failure.name; state = null }
                }
                val actual = buildJsonObject { put("status", status); put("bytes", bytes); put("contents", contents) }
                assertEquals(case.getValue("snapshots").jsonArray[index], actual, "$name event $index")
            }
        }
    }

    @Test fun failedTransactionsCannotCommitPartialData() {
        for (failure in KittyClipboardFailure.entries) {
            val state = KittyClipboardWrite(metadata(), 4)
            state.data("text/plain", "YWJj")
            val first = assertFailsWith<KittyClipboardException> {
                state.data("text/plain", if (failure == KittyClipboardFailure.EFBIG) "ZGU=" else "!")
            }
            assertEquals(failure, first.failure)
            assertEquals(failure, assertFailsWith<KittyClipboardException> { state.commit() }.failure)
            assertEquals(failure, assertFailsWith<KittyClipboardException> { state.data("text/plain", "") }.failure)
        }
    }

    @Test fun commitOwnsStableBinaryContentsAndIsSingleUse() {
        val state = KittyClipboardWrite(metadata())
        state.data("application/octet-stream", "AP/+AA==")
        state.alias("application/octet-stream", Base64.encode("binary-copy".encodeToByteArray()))
        val committed = state.commit()
        assertEquals(listOf("application/octet-stream", "binary-copy"), committed.map { it.mime })
        assertContentEquals(byteArrayOf(0, -1, -2, 0), committed.first().data)
        assertSame(committed.first().data, committed.last().data, "Aliases share immutable transaction contents")
        assertFailsWith<IllegalStateException> { state.commit() }
        assertFailsWith<IllegalStateException> { state.data("text/plain", "") }
    }
}
