package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalGraphicsFrameTest {
    private fun session() = TerminalSession(10, 4, 20).also {
        it.resize(10, 4, TerminalCellSize(8, 16))
        it.setGraphicsStorageLimit(1024)
    }
    private fun TerminalSession.image(id: Int = 1, z: Int = 0, data: String = "AQID") {
        write("\u001b_Ga=T,q=2,i=$id,f=24,s=1,v=1,c=2,r=2,C=1,z=$z;$data\u001b\\")
    }

    @Test fun frameOrdersLayersAndUnsignedImageIdsAndClipsScrolledSources() {
        val session = session()
        val viewport = TerminalViewport(session, {})
        try {
            session.image(3, 0); session.image(2, -1); session.image(1, 0)
            assertEquals(listOf(2L, 1L, 3L), viewport.graphicsFrame().map { it.image.id })
            session.write("\u001b[4;1H\n")
            val entry = viewport.graphicsFrame().first()
            assertEquals(-1L, entry.row)
            val quad = assertNotNull(entry.quad(4.0, 8.0, 2.0, 10, 4))
            assertEquals(0.0, quad.y); assertEquals(8.0, quad.height)
            assertEquals(0.5, quad.sourceY); assertEquals(0.5, quad.sourceHeight)
            viewport.scroll(1)
            assertEquals(0L, viewport.graphicsFrame().first().row)
        } finally { viewport.dispose() }
    }

    @Test fun synchronizedFrameOwnsPixelsAcrossReplacementDeletionAndReset() {
        val session = session()
        val viewport = TerminalViewport(session, {})
        try {
            session.image()
            val original = viewport.graphicsFrame().single().image
            session.write("\u001b[?2026h")
            session.image(data = "BAUG")
            assertSame(original, viewport.graphicsFrame().single().image)
            assertContentEquals(byteArrayOf(1, 2, 3), original.pixels.data)
            session.write("\u001b_Ga=d,d=A,q=2\u001b\\")
            assertSame(original, viewport.graphicsFrame().single().image)
            session.write("\u001b[?2026l")
            assertTrue(viewport.graphicsFrame().isEmpty())
            session.image(); session.reset()
            assertTrue(viewport.graphicsFrame().isEmpty())
        } finally { viewport.dispose() }
        assertTrue(viewport.graphicsFrame().isEmpty())
    }

    @Test fun tilesKeepSamplingGuttersAndConvertOnlyTheirPixels() {
        val pixels = TerminalGraphicsPixels(6, 1, 32, ByteArray(24) { if (it % 4 == 3) 128.toByte() else 100 })
        val image = TerminalGraphicsStoredImage(1, 0, pixels, false, false, 0)
        val quad = TerminalImageQuad(image, TerminalGraphicsLayer.ABOVE_TEXT, 0.0, 0.0, 9.0, 1.0, 0.0, 0.0, 6.0, 1.0)
        val tiles = quad.tiles(4)
        assertEquals(listOf(3, 4, 3), tiles.map { it.tile.width })
        assertEquals(listOf(0.0, 3.0, 6.0), tiles.map { it.quad.x })
        assertEquals(listOf(0.0, 1.0, 1.0), tiles.map { it.quad.sourceX })
        assertContentEquals(byteArrayOf(50, 50, 50, 128.toByte()).let { it + it + it + it }, pixels.tileBytes(tiles[1].tile, true))
        assertEquals(100, pixels.tileBytes(tiles[1].tile, false)[0].toInt())
        val cropped = quad.copy(sourceX = 1.0, sourceWidth = 1.0, cropX = 1, cropWidth = 1).tiles(4).single()
        assertEquals(1, cropped.tile.left); assertEquals(1, cropped.tile.width)
        assertEquals(0.0, cropped.quad.sourceX)
        assertNotEquals(tiles.first().tile.key, cropped.tile.key, "Different source crops cannot share an edge tile")
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndReleasesInvisibleAndDisposedResources() {
        val released = mutableListOf<Int>()
        val cache = TerminalImageCache<Int>(8) { released += it }
        val generation = Any()
        fun tile(x: Int) = TerminalImageTile(TerminalImageTileKey(generation, x, 0), x, 0, 1, 1)
        cache.beginFrame()
        assertEquals(1, cache.get(tile(1)) { 1 }); cache.get(tile(2)) { 2 }
        cache.get(tile(1)) { fail("Cached tile must be reused") }
        cache.get(tile(3)) { 3 }
        assertEquals(listOf(2), released); assertEquals(8L, cache.bytes)
        cache.endFrame(); cache.beginFrame(); cache.get(tile(3)) { fail("Cached tile must survive") }; cache.endFrame()
        assertEquals(listOf(2, 1), released)
        cache.clear(); cache.clear()
        assertEquals(listOf(2, 1, 3), released); assertEquals(0L, cache.bytes)
    }

    @Test fun reapplyingHostLimitDoesNotDiscardAnIncompleteCommand() {
        val session = session()
        session.write("\u001b_Ga=T,q=2,i=1,f=24,s=1,v=1;")
        session.setGraphicsStorageLimit(1024)
        session.write("AQID\u001b\\")
        assertEquals(1, session.graphicsState.storage.imageCount)
    }
}
