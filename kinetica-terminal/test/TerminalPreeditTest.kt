package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalPreeditTest {
    @Test fun placementMatchesOriginalGhosttyFor1302WidthAndCursorCases() {
        for (row in terminalPreeditFixtures.lineSequence()) {
            val v = row.split(',').map(String::toInt)
            val text = (0 until v[2]).joinToString("") { if (v[3] and (1 shl it) != 0) "界" else "a" }
            val range = assertNotNull(TerminalPreedit.from(text)).range(v[1], v[0] - 1)
            assertEquals(TerminalPreedit.Range(v[4], v[5], v[6]), range, row)
        }
        assertNull(TerminalPreedit.from("\u0301\u200d"))
        assertEquals(5.0, assertNotNull(TerminalPreedit.from("A\u0301日😀")).width)
    }

    @Test fun preeditReplacesDisplayCellsWithoutChangingCopySelectionOrAnotherView() {
        val session = TerminalSession(6, 2)
        session.write("ABCDEF\u001b[1;6H\u001b[2 q")
        val view = TerminalViewport(session, {})
        val other = TerminalViewport(session, {})
        try {
            view.selectAll()
            val copied = view.copyText()
            val original = view.visibleText()
            view.setPreedit("界")
            val painted = paint(view)
            val glyph = painted.glyphs.single { it.text == "界\ufe0e" }
            assertEquals(4.0, glyph.x); assertEquals(2, glyph.columns)
            assertEquals(session.renderTheme.foreground, glyph.color)
            assertEquals(TerminalStyle.UNDERLINE, glyph.style)
            assertNull(view.contrastCursor(), "Preedit suppresses the normal cursor")
            assertEquals(original, view.visibleText()); assertEquals(copied, view.copyText())
            assertEquals("ABCDEF", session.line(0).text())
            assertTrue(paint(other).glyphs.none { it.text.startsWith("界") })
            view.setPreedit("")
            assertTrue(paint(view).glyphs.none { it.text.startsWith("界") })
            assertNotNull(view.contrastCursor())
        } finally { view.dispose(); other.dispose() }
    }

    @Test fun synchronizedOutputRetainsPreeditAlongsideItsCursorAndTheme() {
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        try {
            session.write("\u001b[1;8H")
            view.setPreedit("界")
            val before = paint(view).glyphs
            session.write("\u001b[?2026h\u001b[2;2H")
            view.setPreedit("new")
            assertEquals(before, paint(view).glyphs)
            assertEquals(3.0, view.preeditWidth, "IME geometry still uses live preedit")
            session.write("\u001b[?2026l")
            val after = paint(view).glyphs
            assertEquals(listOf("n\ufe0e", "e\ufe0e", "w\ufe0e"), after.filter { it.style == TerminalStyle.UNDERLINE }.map { it.text })
            assertTrue(after.none { it.text.startsWith("界") })
        } finally { view.dispose() }
    }

    @Test fun scrollbackHidesPreeditAndScalarShapingDoesNotFormLigatures() {
        val session = TerminalSession(6, 2)
        val view = TerminalViewport(session, {})
        try {
            session.write("one\r\ntwo\r\nthree")
            view.scrollTo(1); view.setPreedit("界")
            assertTrue(paint(view).glyphs.none { it.text.startsWith("界") })
            val original = session.line(session.historySize)
            val preedit = assertNotNull(TerminalPreedit.from("ffi"))
            val range = preedit.range(1, 5)
            val line = preedit.line(original, range)
            val resolved = mutableListOf<String>()
            val runs = terminalFontRuns(line, null, BooleanArray(6), range.start..range.end) { text, _ ->
                resolved += text; TerminalResolvedFontCell(0, text)
            }
            assertEquals(listOf("f", "f", "i"), runs.filter { it.offset in range.start..range.end }.map { it.input.text })
            assertEquals(listOf("f\ufe0e", "f\ufe0e", "i\ufe0e"), resolved.filter { it.endsWith('\ufe0e') })
            assertNotEquals(line.text(), original.text())
            val emoji = assertNotNull(TerminalPreedit.from("😀\ufe0f"))
            val emojiRange = emoji.range(1, 5)
            resolved.clear()
            terminalFontRuns(emoji.line(original, emojiRange), null, BooleanArray(6), emojiRange.start..emojiRange.end) { text, _ ->
                resolved += text; TerminalResolvedFontCell(0, text)
            }
            assertTrue("😀\ufe0e" in resolved, "Preedit requests text presentation independently of an IME's VS16")
        } finally { view.dispose() }
    }

    @Test fun widePreeditInOneColumnClipsBackgroundUnderlineAndRasterWithoutRescaling() {
        val view = TerminalViewport(TerminalSession(1, 1), {})
        try {
            view.setPreedit("界")
            val frame = TerminalGpuFrame()
            frame.update(view, 8.0, 12.0)
            val row = frame.rows.single()
            assertTrue(row.backgrounds.isNotEmpty() && row.decorations.isNotEmpty())
            assertTrue((row.backgrounds + row.decorations).all { it.x + it.width <= 8.0 })
            val glyph = row.glyphs.single()
            assertEquals(2, glyph.key.columns, "Rasterize at the original wide-cell size")
            val atlas = TerminalGlyphAtlas(64, 1)
            atlas.prepare(frame.keys, { GlyphBitmap(16, 12, ByteArray(16 * 12 * 4), colored = false) }, {}, { _, _ -> })
            var draws = 0
            drawTerminalGlyphs(frame, atlas, 1.0) { _, x, _, width, _, _, _, sourceWidth, _, _, _ ->
                draws++
                assertEquals(0.0, x)
                assertEquals(8.0, width)
                assertEquals(8.0, sourceWidth, "Crop texels rather than squeezing the wide glyph")
            }
            assertEquals(1, draws)
        } finally { view.dispose() }
    }

    private data class Glyph(val text: String, val x: Double, val y: Double, val columns: Int, val color: Int, val style: Int)
    private class Painter : TerminalGlyphPainter {
        val glyphs = mutableListOf<Glyph>()
        override fun beginRow(row: Int) {}
        override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
        override fun text(value: String, x: Double, y: Double, color: Int, style: Int) = error("glyph painter")
        override fun glyph(value: String, x: Double, y: Double, columns: Int, color: Int, style: Int) {
            glyphs += Glyph(value, x, y, columns, color, style)
        }
    }
    private fun paint(view: TerminalViewport): Painter = Painter().also { view.paint(it, 1.0, 10.0, force = true) }
}
