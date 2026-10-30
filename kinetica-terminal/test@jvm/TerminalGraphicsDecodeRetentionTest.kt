package io.heapy.kinetica.terminal

import java.io.ByteArrayOutputStream
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.zip.DeflaterOutputStream
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsDecodeRetentionTest {
    private class Decoded(val pixels: TerminalGraphicsPixels?, val error: TerminalGraphicsDecodeException?,
        val temporary: List<WeakReference<ByteArray>>, val expected: ByteArray)

    private fun png(corrupt: Boolean): Decoded {
        val fixture = terminalGraphicsPngFixtures().first()
        val encoded = Base64.decode(fixture.png)
        if (corrupt) encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(encoded) }
        val compressed = output.toByteArray()
        val temporary = mutableListOf(WeakReference(encoded), WeakReference(compressed))
        var error: TerminalGraphicsDecodeException? = null
        val pixels = try {
            decodeTerminalGraphicsImage(100, 0, 0, true, compressed, allocateInflated = { size ->
                ByteArray(size).also { temporary += WeakReference(it) }
            })
        } catch (failure: TerminalGraphicsDecodeException) { error = failure; null }
        assertTrue(temporary.size > 2, "Exercise the outer inflater's owned allocation")
        return Decoded(pixels, error, temporary, Base64.decode(fixture.rgba))
    }

    private fun assertCollected(buffers: List<WeakReference<ByteArray>>) {
        repeat(40) {
            if (buffers.all { it.get() == null }) return
            System.gc(); Thread.sleep(10)
        }
        buffers.forEach { assertNull(it.get(), "Decoded output or a retained error must not own retired encoded/inflated input") }
    }

    @Test fun outerZlibPngReleasesEncodedAndInflatedInputWhileDecodedPixelsRemainLive() {
        val decoded = png(corrupt = false)
        try {
            assertNull(decoded.error)
            assertCollected(decoded.temporary)
            assertContentEquals(decoded.expected, assertNotNull(decoded.pixels).data)
        } finally { Reference.reachabilityFence(decoded) }
    }

    @Test fun pngDecodeFailureDoesNotRetainItsOuterInflationBuffers() {
        val decoded = png(corrupt = true)
        try {
            assertNull(decoded.pixels)
            assertEquals(TerminalGraphicsDecodeError.INVALID_DATA, assertNotNull(decoded.error).error)
            assertCollected(decoded.temporary)
        } finally { Reference.reachabilityFence(decoded) }
    }

    private class RejectedFrame(val storage: TerminalGraphicsStorage, val result: TerminalGraphicsTransferResult,
        val oldRoot: WeakReference<TerminalGraphicsPixels>, val transfers: List<WeakReference<ByteArray>>)

    private fun rejectedFrame(): RejectedFrame {
        val transfers = mutableListOf<WeakReference<ByteArray>>()
        val storage = TerminalGraphicsStorage(3, allocateTransfer = { size ->
            ByteArray(size).also { transfers += WeakReference(it) }
        })
        fun command(wire: String): TerminalGraphicsCommand {
            val parser = TerminalGraphicsCommandParser(4096)
            parser.feed(wire.encodeToByteArray())
            return assertNotNull(parser.complete())
        }
        assertEquals("OK", storage.transmit(command("a=t,i=1,f=24,s=1,v=1;AQID")).response?.message)
        val oldRoot = WeakReference(assertNotNull(storage.image(1)).pixels)
        val result = storage.transmit(command("a=f,i=1,f=32,s=1,v=1;BAUG/w=="))
        return RejectedFrame(storage, result, oldRoot, transfers)
    }

    @Test fun rejectedAnimationAppendReleasesOldRgbRootAndDecodedFrameAfterRequiredConversion() {
        val rejected = rejectedFrame()
        try {
            assertEquals("ENOSPC: animation frame storage full", rejected.result.response?.message)
            assertEquals(4L, rejected.storage.totalBytes, "Ghostty converts the RGB root before rejecting frame admission")
            assertCollected(rejected.transfers)
            assertNull(rejected.oldRoot.get(), "The current render-content wrapper must follow the converted root even after rejection")
            val image = assertNotNull(rejected.storage.image(1))
            assertEquals(1, image.animation.frameCount)
            assertContentEquals(byteArrayOf(1, 2, 3, -1), image.renderContent.pixels.data)
        } finally { Reference.reachabilityFence(rejected); rejected.storage.clear() }
    }
}
