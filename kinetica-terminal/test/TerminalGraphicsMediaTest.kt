package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsMediaTest {
    private fun command(header: String, data: ByteArray = "/image".encodeToByteArray()): TerminalGraphicsCommand {
        val parser = TerminalGraphicsCommandParser(4096)
        parser.feed((header + ";" + Base64.encode(data)).encodeToByteArray())
        return assertNotNull(parser.complete())
    }

    private val rgb = byteArrayOf(1,2,3)

    @Test fun externalTransmissionsUseHostBytesAndPreserveUnsignedRangeAndDisplayFields() {
        val requests = mutableListOf<TerminalGraphicsMediaRequest>()
        val storage = TerminalGraphicsStorage(media = TerminalGraphicsMedia {
            requests += it; TerminalGraphicsMediaResult.Data(rgb.copyOf())
        })
        for ((medium, expected) in listOf('f' to TerminalGraphicsMedium.FILE,
            't' to TerminalGraphicsMedium.TEMPORARY_FILE, 's' to TerminalGraphicsMedium.SHARED_MEMORY)) {
            val result = storage.transmit(command("a=T,i=31,t=$medium,f=24,s=1,v=1,O=4294967295,S=3,m=1,p=7,c=2"))
            assertEquals("\u001b_Gi=31,p=7;OK\u001b\\",result.reply())
            assertFalse(result.pending); assertFalse(storage.hasPendingTransfer)
            assertContentEquals(rgb,assertNotNull(result.image).pixels.data)
            assertEquals(2,assertNotNull(result.display).unsigned('c'))
            val request = requests.last()
            assertEquals(expected,request.medium); assertEquals(4294967295L,request.offset)
            assertEquals(3L,request.size); assertEquals(24,request.format)
            assertContentEquals("/image".encodeToByteArray(),request.name)
            assertFalse(request.compressed)
        }
        assertEquals(3,requests.size)
    }

    @Test fun queryAndAnimationReadsRetainExistingStorageAndFrameResponses() {
        var result: TerminalGraphicsMediaResult = TerminalGraphicsMediaResult.Data(rgb)
        val storage = TerminalGraphicsStorage(media = TerminalGraphicsMedia { result })
        val old = assertNotNull(storage.transmit(command("a=t,i=1,f=24,s=1,v=1",rgb)).image)
        assertEquals("\u001b_Gi=1;OK\u001b\\",storage.query(command("a=q,i=1,t=f,f=24,s=1,v=1")).reply())
        assertSame(old,storage.image(1))
        assertEquals("\u001b_Gi=1,r=2;OK\u001b\\",storage.transmit(command("a=f,i=1,t=f,f=24,s=1,v=1")).reply())
        assertEquals(2,old.animation.frameCount)
        result = TerminalGraphicsMediaResult.INVALID_DATA
        assertEquals("\u001b_Gi=1,r=4;EINVAL: invalid data\u001b\\",
            storage.transmit(command("a=f,i=1,r=4,t=f,f=24,s=1,v=1")).reply())
        assertSame(old,storage.image(1))
        assertNull(storage.transmit(command("a=t,i=1,t=f,f=24,s=1,v=1,q=2")).reply())
        assertNull(storage.image(1),"A failed replacement still removes the previous image")
    }

    @Test fun policyDenialFailuresAndOversizedResultsHaveBoundedFixedReplies() {
        val external = command("a=t,i=31,t=f,f=24,s=1,v=1")
        assertEquals("\u001b_Gi=31;EINVAL: unsupported medium\u001b\\",TerminalGraphicsStorage().transmit(external).reply())
        for ((result, message) in listOf(
            TerminalGraphicsMediaResult.UNSUPPORTED to "unsupported medium",
            TerminalGraphicsMediaResult.INVALID_DATA to "invalid data",
            TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_IN_TEMP_DIR to "temporary file not in temp dir",
            TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_NAMED_CORRECTLY to "temporary file not named correctly",
            TerminalGraphicsMediaResult.DIMENSIONS_TOO_LARGE to "dimensions too large",
            TerminalGraphicsMediaResult.Data(ByteArray(4)) to "invalid data")) {
            val storage = TerminalGraphicsStorage(decodeLimits = TerminalGraphicsDecodeLimits(3,32),
                media = TerminalGraphicsMedia { result })
            assertEquals("\u001b_Gi=31;EINVAL: $message\u001b\\",storage.transmit(external).reply())
            assertEquals(0,storage.totalBytes); assertEquals(0,storage.retainedLoadingBytes)
        }
        val throwing = TerminalGraphicsStorage(media = TerminalGraphicsMedia { error("private path and OS details") })
        assertEquals("\u001b_Gi=31;EINVAL: invalid data\u001b\\",throwing.transmit(external).reply())
        val short = TerminalGraphicsStorage(media = TerminalGraphicsMedia { TerminalGraphicsMediaResult.Data(rgb) })
        assertEquals("\u001b_Gi=31;EINVAL: invalid data\u001b\\",
            short.transmit(command("a=t,i=31,t=f,f=24,s=1,v=1,S=4")).reply())
    }

    @Test fun pendingDirectTransfersAndInvalidCommandsNeverInvokeTheHost() {
        var calls = 0
        val storage = TerminalGraphicsStorage(media = TerminalGraphicsMedia { calls++; TerminalGraphicsMediaResult.Data(rgb) })
        storage.transmit(command("a=t,i=1,f=24,s=1,v=1,m=1",byteArrayOf(1)))
        val result = storage.transmit(command("a=f,t=f,m=1",byteArrayOf(2,3)))
        assertContentEquals(rgb,assertNotNull(result.image).pixels.data)
        assertEquals(0,calls,"A continuation uses the original direct header, not a new host read")
        storage.query(command("a=q,t=f"))
        storage.query(command("a=q,i=1,I=1,t=f"))
        storage.transmit(command("a=t,i=1,t=f,f=42"))
        storage.transmit(command("a=f,i=99,t=f"))
        storage.setLimit(0)
        storage.transmit(command("a=t,i=1,t=f"))
        assertEquals(0,calls)
    }

    @Test fun attachmentAppliesToBothScreensAndOldDisposalCannotDetachReplacement() {
        val session = TerminalSession(10,6)
        session.setGraphicsStorageLimit(100)
        val replies = StringBuilder()
        session.onInput { replies.append(it.toByteArray().decodeToString()) }
        var calls = 0
        val media = TerminalGraphicsMedia { calls++; TerminalGraphicsMediaResult.Data(rgb) }
        val old = session.attachGraphicsMedia(media)
        val current = session.attachGraphicsMedia(media)
        old.dispose()
        fun write() = session.write("\u001b_Ga=t,i=1,t=f,f=24,s=1,v=1;L2ltYWdl\u001b\\")
        write(); assertNotNull(session.graphicsState.storage.image(1))
        session.write("\u001b[?47h"); write()
        assertNotNull(session.graphicsState.storage.image(1)); assertEquals(2,calls)
        current.dispose(); replies.clear(); write()
        assertEquals("\u001b_Gi=1;EINVAL: unsupported medium\u001b\\",replies.toString())
        assertEquals(2,calls)
        session.write("\u001b[?47l")
        assertNotNull(session.graphicsState.storage.image(1),"Detaching the host preserves other decoded images")
    }

    @Test fun sharedMemoryLengthExcludesPagePaddingAndValidatesBeforeMultiplication() {
        fun request(medium: TerminalGraphicsMedium = TerminalGraphicsMedium.SHARED_MEMORY, size: Long = 0,
            format: Int = 24, width: Long = 1, compressed: Boolean = false, limit: Int = 4096) =
            TerminalGraphicsMediaRequest(medium,byteArrayOf(),0,size,format,width,1,compressed,limit,10_000)
        assertEquals(3,request().readLength(4096))
        assertEquals(4096,request(format=100).readLength(4096))
        assertEquals(4096,request(compressed=true).readLength(4096))
        assertEquals(4096,request(medium=TerminalGraphicsMedium.FILE).readLength(4096))
        assertEquals(4,request(size=4).readLength(4096))
        for (value in listOf(request(size=4097),request(limit=2)))
            assertEquals(TerminalGraphicsMediaResult.INVALID_DATA,assertFailsWith<TerminalGraphicsMediaException> { value.readLength(4096) }.result)
        assertEquals(TerminalGraphicsMediaResult.DIMENSIONS_TOO_LARGE,
            assertFailsWith<TerminalGraphicsMediaException> { request(width=4294967295L).readLength(4096) }.result)
        assertFailsWith<TerminalGraphicsMediaException> { request().readLength(2) }
    }
}
