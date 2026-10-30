package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalGpuTest {
    @Test fun colorLayersUsingForegroundHaveDistinctAtlasEntries() {
        val session = TerminalSession(8, 1)
        session.write("\u001b[?25l\u001b[31mA\u001b[32mA")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) { run ->
            listOf(TerminalShapedGlyph(0, 42, 0, 0, 0, usesForeground = true))
        }
        frame.update(viewport, 8.0, 16.0)
        val (red, green) = frame.rows[0].glyphs
        assertNotEquals(red.key, green.key)
        assertEquals(red.color, red.key.foreground)
        assertEquals(green.color, green.key.foreground)
        assertEquals(2, frame.keys.size)
        session.write("\u001b[1;1H\u001b[32mA")
        frame.update(viewport, 8.0, 16.0)
        assertEquals(1, frame.keys.size)
        assertEquals(green.key, frame.keys.single())
        viewport.dispose()
    }

    @Test fun symbolFittingUsesAvailableBlankAndChangesAtlasIdentity() {
        val session = TerminalSession(8, 2)
        session.write("\u001b[?25l\uea61\r\n\uea61X")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(if (text == "X") 1 else 0, text) }) { run ->
            listOf(TerminalShapedGlyph(run.font, 42, 0, 0, 0))
        }
        frame.update(viewport, 8.0, 16.0)
        val wide = frame.rows[0].glyphs.single()
        val narrow = frame.rows[1].glyphs.first()
        assertEquals(2, wide.key.columns)
        assertEquals(1, narrow.key.columns)
        assertEquals(16.0, wide.clipRight)
        assertEquals(8.0, narrow.clipRight)
        assertNotEquals(wide.key, narrow.key)
        assertEquals(TerminalGlyphSizing.FIT_COVER1, wide.key.constraint.size)
        session.write("\u001b[1;2HX")
        frame.update(viewport, 8.0, 16.0)
        assertEquals(narrow.key, frame.rows[0].glyphs.first().key)
        viewport.dispose()
    }

    @Test fun colorFaceOverridesTheCodepointConstraint() {
        val session = TerminalSession(4, 1)
        session.write("\u001b[?25l\u263a")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) { run ->
            listOf(TerminalShapedGlyph(run.font, 42, 0, 0, 0, emoji = true))
        }
        frame.update(viewport, 8.0, 16.0)
        assertEquals(TerminalGlyphConstraint.EMOJI, frame.rows[0].glyphs.single().key.constraint)
        viewport.dispose()
    }

    @Test fun shapedRunsReplaceCellGlyphsAndRetainCleanRows() {
        val session = TerminalSession(8, 2)
        session.write("\u001b[?25l==\r\nkeep\u001b[1;1H")
        val viewport = TerminalViewport(session, {})
        val inputs = mutableListOf<String>()
        val frame = TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(2, text) }) { run ->
            inputs += run.input.text
            listOf(TerminalShapedGlyph(run.font, 42, 0, -1, 2))
        }
        frame.update(viewport, 7.25, 12.25)
        assertEquals(listOf("==", "keep"), inputs)
        assertEquals(1, frame.keys.size)
        val first = frame.rows[0].glyphs.single()
        assertEquals(GlyphKey("", 0, 0, 2, 42), first.key)
        assertEquals(-1, first.xOffset)
        assertEquals(2, first.yOffset)
        assertEquals(0.0, first.clipLeft)
        assertEquals(14.5, first.clipRight)
        val retained = frame.rows[1].glyphs.single()
        session.write("\u001b[1;1H>")
        frame.update(viewport, 7.25, 12.25)
        assertSame(retained, frame.rows[1].glyphs.single())
        assertEquals(listOf("==", "keep", ">="), inputs)
        viewport.dispose()
        assertFailsWith<IllegalArgumentException> { TerminalGpuFrame(resolveFont = { text, _ -> TerminalResolvedFontCell(0, text) }) }
    }

    @Test fun shapedGlyphBearingsAndOffsetsCropTexelsWithoutRescaling() {
        val session = TerminalSession(8, 1)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        frame.update(viewport, 7.25, 12.25)
        val key = GlyphKey("", 0, 0, 2, 42)
        val outside = key.copy(glyph = 43)
        frame.rows[0].glyphs.clear()
        frame.rows[0].glyphs += TerminalGlyph(key, 7.25, 0.0, 0x123456,
            xOffset = -2, yOffset = 2, clipLeft = 7.25, clipRight = 14.5)
        frame.rows[0].glyphs += TerminalGlyph(outside, 7.25, 0.0, 0x123456,
            xOffset = 100, clipLeft = 7.25, clipRight = 14.5)
        val atlas = TerminalGlyphAtlas(64, 1)
        atlas.prepare(setOf(key, outside), {
            GlyphBitmap(20, 30, ByteArray(20 * 30 * 4), colored = true, left = -1, top = -2)
        }, {}, { _, _ -> })
        val batches = TerminalGpuBatches()
        batches.build(frame, atlas, pixelRatio = 2.0)
        val page = batches.pages.single()
        assertEquals(1, page.count)
        // Device bounds before clipping: (11, -4)..(31, 26).
        // Run/row bounds: (14, 0)..(29, 24), with matching UV crops.
        assertContentEquals(floatArrayOf(7f, 0f, 7.5f, 12f,
            4f / 64, 5f / 64, 19f / 64, 29f / 64, 1f, 1f, 1f, 1f), page.data.copyOf(12))
        viewport.dispose()
    }

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

    @Test fun foregroundChangesReuseGlyphAndDecorationsKeepTheirTextLayer() {
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
        assertEquals(1, batches.underlays.count)
        assertEquals(1, batches.decorations.count)
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
