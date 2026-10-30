package io.heapy.kinetica.terminal

import java.lang.ref.Reference
import java.lang.ref.WeakReference
import kotlin.test.*

class TerminalImageCacheRetentionTest {
    private class CachedCrop(
        val cache: TerminalImageCache<ByteArray>,
        val tile: TerminalImageTile,
        val pixels: WeakReference<TerminalGraphicsPixels>,
        val source: WeakReference<ByteArray>,
    )

    // Keep the large source out of the test method's stack while checking collectability.
    private fun cacheTinyCrop(): CachedCrop {
        val pixels = TerminalGraphicsPixels(512, 512, 32, ByteArray(512 * 512 * 4) {
            when (it % 4) { 0 -> 10; 1 -> 20; 2 -> 30; else -> -1 }
        })
        val quad = TerminalImageQuad(TerminalGraphicsImageContent(1, pixels), TerminalGraphicsLayer.ABOVE_TEXT,
            0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0, cropWidth = 1, cropHeight = 1)
        val tile = quad.tiles().single().tile
        val cache = TerminalImageCache<ByteArray>(4) {}
        cache.beginFrame()
        cache.get(tile) { pixels.tileBytes(tile, premultiplied = false) }
        cache.endFrame()
        return CachedCrop(cache, tile, WeakReference(pixels), WeakReference(pixels.data))
    }

    @Test fun cachedTinyCropDoesNotKeepItsFullDecodedSourceAlive() {
        val cached = cacheTinyCrop()
        try {
            repeat(40) {
                if (cached.pixels.get() == null && cached.source.get() == null) return@repeat
                System.gc()
                Thread.sleep(10)
            }
            assertNull(cached.pixels.get(), "A resident tile key must not own the decoded source image")
            assertNull(cached.source.get(), "A four-byte crop must not retain the one-MiB source buffer")
            assertEquals(1, cached.cache.size)
            assertEquals(4L, cached.cache.bytes)
            cached.cache.beginFrame()
            assertContentEquals(byteArrayOf(10, 20, 30, -1), cached.cache.get(cached.tile) {
                fail("The uploaded tile must remain usable after its source is collected")
            })
            cached.cache.endFrame()
        } finally {
            // The cache/key must stay live throughout GC, including under JIT optimization.
            Reference.reachabilityFence(cached)
            cached.cache.clear()
        }
    }
}
