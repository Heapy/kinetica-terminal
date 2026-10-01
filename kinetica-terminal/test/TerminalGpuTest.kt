package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalGpuTest {
    @Test fun cleanRowsRetainGlyphsAndWideCombiningCellsStayWhole() {
        val session = TerminalSession(8, 2)
        session.write("界e\u0301\r\nkeep\u001b[1;1H")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        frame.update(viewport, 8.0, 16.0)
        assertEquals(listOf("界", "e\u0301"), frame.rows[0].glyphs.map { it.key.text })
        assertEquals(listOf(2, 1), frame.rows[0].glyphs.map { it.key.columns })
        assertEquals(listOf(0.0, 16.0), frame.rows[0].glyphs.map { it.x })
        val retained = frame.rows[1].glyphs.first()
        session.write("\u001b[1;1HX")
        frame.update(viewport, 8.0, 16.0)
        assertSame(retained, frame.rows[1].glyphs.first())
        frame.update(viewport, 10.0, 20.0)
        assertNotSame(retained, frame.rows[1].glyphs.first())
        assertEquals(20.0, frame.rows[1].glyphs.first().y)
        viewport.dispose()
    }

    @Test fun foregroundChangesReuseGlyphAndDecorationsAreDrawnLast() {
        val session = TerminalSession(4, 1)
        session.write("\u001b[31mX\u001b[32;4mX")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame(); frame.update(viewport, 8.0, 16.0)
        assertEquals(1, frame.keys.size)
        assertEquals(1, frame.rows[0].backgrounds.size)
        assertEquals(2, frame.rows[0].decorations.size) // underline and cursor
        val atlas = TerminalGlyphAtlas(16, 1)
        atlas.prepare(frame.keys, { bitmap(4, 8) }, {}, { _, _ -> })
        val batches = TerminalGpuBatches(); batches.build(frame, atlas)
        assertEquals(2, batches.pages.single().count)
        assertNotEquals(batches.pages[0].data[8], batches.pages[0].data[20])
        assertEquals(2, batches.decorations.count)
        assertEquals(1f / 16f, batches.pages[0].data[4])
        viewport.dispose()
    }

    @Test fun atlasRolloverRebuildsEntireVisibleSetBeforeReturning() {
        val atlas = TerminalGlyphAtlas(8, 1) // four 2x2 slots, including gutters
        fun key(text: String) = GlyphKey(text, 1, 0)
        var resets = 0; var rasterizations = 0
        val uploads = mutableListOf<AtlasGlyph>()
        fun prepare(text: String) = atlas.prepare(text.map { key(it.toString()) }.toSet(),
            { rasterizations++; bitmap(2, 2) }, { resets++; uploads.clear() }, { entry, _ -> uploads += entry })
        prepare("abcd")
        assertEquals(4, rasterizations)
        prepare("abcd")
        assertEquals(4, rasterizations)
        prepare("de")
        assertEquals(1, resets)
        assertEquals(2, atlas.entryCount)
        assertEquals(2, uploads.size)
        assertNotEquals(atlas[key("d")], atlas[key("e")])
        assertTrue(uploads.all { it.x > 0 && it.y > 0 && it.x + it.width < 8 && it.y + it.height < 8 })
        assertFailsWith<IllegalStateException> { prepare("abcde") }
        assertEquals(1, atlas.pageCount)
    }

    @Test fun coloredGlyphsKeepTheirOwnColorAndAlphaIsPremultipliedOnce() {
        val bitmap = glyphBitmap(1, 1, byteArrayOf(200.toByte(), 100, 50, 128.toByte()))
        assertTrue(bitmap.colored)
        assertContentEquals(byteArrayOf(100, 50, 25, 128.toByte()), bitmap.rgba)
        assertContentEquals(bitmap.rgba, glyphBitmap(1, 1, bitmap.rgba.copyOf(), premultiplied = true).rgba)
        assertFalse(glyphBitmap(1, 1, byteArrayOf(-1, -1, -1, -1)).colored)
    }

    @Test fun oversizedGlyphCannotGrowAtlasWithoutBound() {
        val atlas = TerminalGlyphAtlas(8, 1)
        assertFailsWith<IllegalStateException> {
            atlas.prepare(setOf(GlyphKey("x", 1, 0)), { bitmap(8, 8) }, {}, { _, _ -> fail("Cannot upload out of bounds") })
        }
        assertEquals(0, atlas.entryCount)
    }

    private fun bitmap(width: Int, height: Int) = GlyphBitmap(width, height, ByteArray(width * height * 4), false)
}
