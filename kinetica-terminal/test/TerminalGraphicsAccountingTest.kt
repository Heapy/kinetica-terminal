package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsAccountingTest {
    @Test fun rootConversionAndSubsequentAdmissionMatchOriginalStorage() = replay { it.startsWith("root_conversion_") }
    @Test fun compositionConversionAndEvictionMatchOriginalStorage() = replay { it.startsWith("composition_conversion_") }
    @Test fun hostLimitChangesPreserveOriginalTransferAndScreenSemantics() = replay {
        it.startsWith("limit_during_") || it == "conversion_on_both_screens"
    }

    @Test fun aggregateConversionAndLimitEpochsMatchOriginalStorage() = replay { it.startsWith("aggregate_conversion_") }

    private fun replay(include: (String) -> Boolean) {
        for (encoded in terminalGraphicsAccountingFixtures()) {
            val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            if (!include(name)) continue
            for (bytewise in listOf(false, true)) {
                assertEquals(case.getValue("apcLimit").jsonPrimitive.int, TERMINAL_GRAPHICS_APC_BYTES,
                    "Default graphics capture limit must match the pinned original")
                val session = TerminalSession(12, 6)
                session.setGraphicsStorageLimit(case.getValue("limit").jsonPrimitive.int)
                val replies = StringBuilder()
                session.onInput { replies.append(it.toByteArray().toHexString()) }
                for ((index, value) in case.getValue("events").jsonArray.withIndex()) {
                    val event = value.jsonObject
                    event["limit"]?.jsonPrimitive?.int?.let(session::setGraphicsStorageLimit)
                    event["text"]?.jsonPrimitive?.content?.encodeToByteArray()?.let { bytes ->
                        if (bytewise) bytes.forEach { session.write(byteArrayOf(it)) } else session.write(bytes)
                    }
                    val state = session.graphicsState
                    val storage = state.storage
                    val images = (1L..4L).mapNotNull(storage::image)
                    val context = "$name step=$index bytewise=$bytewise"
                    assertEquals(storage.imageCount, images.size, "$context unprobed image")
                    val actual = buildJsonObject {
                        put("replies", replies.toString()); put("limit", storage.limit)
                        put("bytes", storage.totalBytes); put("pending", storage.hasPendingTransfer)
                        put("imageCount", storage.imageCount); put("placementCount", state.placements.size)
                        put("images", buildJsonArray { for (image in images) add(buildJsonObject {
                            put("id", image.id); put("format", if (image.pixels.format == 32) 1 else 0)
                            put("bytes", image.animation.byteCount)
                            put("frames", buildJsonArray {
                                for (number in 1..image.animation.frameCount)
                                    add(assertNotNull(image.animation.frame(number.toLong())).data.toHexString())
                            })
                        }) })
                    }
                    assertEquals(case.getValue("snapshots").jsonArray[index], actual, context)
                    assertEquals(images.sumOf { it.animation.byteCount }, storage.totalBytes, "$context accounted frames")
                    assertTrue(storage.totalBytes * 3 <= storage.limit.toLong() * 4, "$context RGB conversion ceiling")
                }
            }
        }
    }
}
