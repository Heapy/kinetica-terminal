package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsStorageTest {
    @Test fun baseTransfersAndStorageOperationsMatchOriginalLibghostty() {
        val cases = Json.parseToJsonElement(Base64.decode(terminalGraphicsStorageFixtures()).decodeToString()).jsonArray
        for (case in cases.map { it.jsonObject }) for (bytewise in listOf(false, true)) {
            val name = case.getValue("name").jsonPrimitive.content
            val storage = TerminalGraphicsStorage(case.getValue("limit").jsonPrimitive.int)
            val replies = StringBuilder()
            val ids = case.getValue("ids").jsonArray.map { it.jsonPrimitive.long }
            for ((index, eventValue) in case.getValue("events").jsonArray.withIndex()) {
                val event = eventValue.jsonObject
                when {
                    "wire" in event -> {
                        val command = parse(event.getValue("wire").jsonPrimitive.content, bytewise)
                        val result = if (command.action == 'q') storage.query(command) else storage.transmit(command)
                        result.reply()?.let(replies::append)
                    }
                    "limit" in event -> storage.setLimit(event.getValue("limit").jsonPrimitive.int)
                    "reset" in event -> storage.clear()
                    else -> {
                        // These are storage operations, not a claim that placement-aware delete dispatch exists yet.
                        storage.abortTransfer()
                        when {
                            "remove" in event -> storage.remove(event.getValue("remove").jsonPrimitive.long)
                            "removeNumber" in event -> storage.imageByNumber(event.getValue("removeNumber").jsonPrimitive.long)?.let { storage.remove(it.id) }
                            else -> event.getValue("removeRange").jsonArray.let { storage.removeRange(it[0].jsonPrimitive.long, it[1].jsonPrimitive.long) }
                        }
                    }
                }
                val expected = case.getValue("snapshots").jsonArray[index].jsonObject
                val context = "$name step=$index bytewise=$bytewise"
                assertEquals(expected.getValue("replies").jsonPrimitive.content, replies.toString().encodeToByteArray().toHexString(), context)
                val expectedImages = expected.getValue("images").jsonArray
                assertEquals(expectedImages.size, storage.imageCount, context)
                assertEquals(expectedImages.map { it.jsonArray[0].jsonPrimitive.long }, ids.filter { storage.image(it) != null }, context)
                var totalBytes = 0L
                for (imageValue in expectedImages) {
                    val image = imageValue.jsonArray
                    val stored = assertNotNull(storage.image(image[0].jsonPrimitive.long), context)
                    assertEquals(image[1].jsonPrimitive.long, stored.number, context)
                    assertEquals(image[2].jsonPrimitive.int, stored.pixels.width, context)
                    assertEquals(image[3].jsonPrimitive.int, stored.pixels.height, context)
                    assertEquals(image[4].jsonPrimitive.int, stored.pixels.format, context)
                    assertEquals(image[5].jsonPrimitive.content, stored.pixels.data.toHexString(), context)
                    totalBytes += stored.pixels.data.size
                }
                assertEquals(totalBytes, storage.totalBytes, context)
            }
        }
    }

    @Test fun completionRetainsDisplayMetadataButReleasesChunkBuffers() {
        val storage = TerminalGraphicsStorage()
        assertTrue(storage.transmit(parse("a=T,i=31,f=24,s=1,v=1,m=1,p=7,c=2,r=3,z=-8,C=1;AQ==")).pending)
        assertEquals(1, storage.loadingBytes)
        val completed = storage.transmit(parse("a=f,i=99,f=42;AgM="))
        val display = assertNotNull(completed.display)
        assertEquals(31, display.imageId); assertEquals(7, display.placementId)
        assertEquals(2, display.unsigned('c')); assertEquals(3, display.unsigned('r')); assertEquals(-8, display.signed('z'))
        assertFalse(display.moveCursor); assertTrue(display.data.isEmpty())
        assertContentEquals(byteArrayOf(1,2,3), assertNotNull(completed.image).pixels.data)
        assertEquals("\u001b_Gi=31,p=7;OK\u001b\\", completed.reply())
        assertEquals(0, storage.retainedLoadingBytes); assertFalse(storage.hasPendingTransfer)
    }

    @Test fun failedAppendKeepsPriorChunksButAbortAndCompletionReleaseMemory() {
        val storage = TerminalGraphicsStorage(32, TerminalGraphicsDecodeLimits(3, 32))
        storage.transmit(parse("a=t,i=31,f=24,s=1,v=1,m=1;AQ=="))
        val tooMuch = storage.transmit(parse("m=1;AgME"))
        assertEquals("\u001b_Gi=31;EINVAL: invalid data\u001b\\", tooMuch.reply())
        assertEquals(1, storage.loadingBytes); assertTrue(storage.retainedLoadingBytes <= 3)
        assertEquals("\u001b_Gi=31;OK\u001b\\", storage.transmit(parse(";AgM=")).reply())
        assertEquals(0, storage.retainedLoadingBytes)
        storage.transmit(parse("a=t,i=31,f=24,s=1,v=1,m=1;AQ=="))
        assertNull(storage.image(31))
        storage.abortTransfer()
        assertEquals(0, storage.retainedLoadingBytes); assertFalse(storage.hasPendingTransfer)
        assertEquals(0, storage.totalBytes)
    }

    @Test fun generationsNeverAliasAcrossReplacementClearOrOtherStores() {
        val first = TerminalGraphicsStorage()
        val second = TerminalGraphicsStorage()
        val command = parse("a=t,i=31,f=24,s=1,v=1;AQID")
        val old = assertNotNull(first.transmit(command).image)
        val generation = first.generation
        first.query(parse("a=q,i=31,f=24,s=1,v=1;BAUG"))
        assertSame(generation, first.generation)
        assertFalse(first.remove(99)); assertSame(generation, first.generation)
        val replacement = assertNotNull(first.transmit(command).image)
        assertNotSame(old.generation, replacement.generation); assertNotSame(generation, first.generation)
        first.clear()
        val afterClear = assertNotNull(first.transmit(command).image)
        val other = assertNotNull(second.transmit(command).image)
        assertNotSame(replacement.generation, afterClear.generation); assertNotSame(afterClear.generation, other.generation)
        // Retained render snapshots can still own the old immutable pixels after removal.
        assertContentEquals(byteArrayOf(1,2,3), old.pixels.data)
    }

    @Test fun evictionRanksUsageBeforeAgeAndNotifiesOwners() {
        val removed = mutableListOf<Long>()
        val storage = TerminalGraphicsStorage(12, removed = { removed += it.id })
        for (id in 1..4) {
            val image = assertNotNull(storage.transmit(parse("a=t,i=$id,f=24,s=1,v=1,N=${id % 2};AQID")).image)
            image.placementCount = if (id < 3) 1 else 0
        }
        storage.setLimit(0)
        assertEquals(setOf(1L,2L,3L,4L), removed.toSet())
        assertEquals(0, storage.totalBytes); assertEquals(0, storage.imageCount)
        storage.setLimit(12); removed.clear()
        for (id in 1..4) {
            val image = assertNotNull(storage.transmit(parse("a=t,i=$id,f=24,s=1,v=1,N=${id % 2};AQID")).image)
            image.placementCount = if (id < 3) 1 else 0
        }
        storage.setLimit(3)
        assertEquals(listOf(3L,4L,1L), removed)
        assertNotNull(storage.image(2))
    }

    @Test fun pngTransmissionUsesSharedDecoderAndQueryLeavesPixelsUntouched() {
        val fixture = terminalGraphicsPngFixtures().first()
        val storage = TerminalGraphicsStorage()
        val result = storage.transmit(parse("a=t,i=31,f=100;${fixture.png}"))
        assertContentEquals(Base64.decode(fixture.rgba), assertNotNull(result.image).pixels.data)
        assertEquals(fixture.width, result.image.pixels.width)
        val query = storage.query(parse("a=q,i=31,f=100;${fixture.png}"))
        assertEquals("\u001b_Gi=31;OK\u001b\\", query.reply())
        assertSame(result.image, storage.image(31))
    }

    private fun parse(wire: String, bytewise: Boolean = false): TerminalGraphicsCommand {
        val parser = TerminalGraphicsCommandParser(1024 * 1024)
        val bytes = wire.encodeToByteArray()
        if (bytewise) for (byte in bytes) parser.feed(byte.toInt() and 255) else parser.feed(bytes)
        return assertNotNull(parser.complete(), wire)
    }
}
