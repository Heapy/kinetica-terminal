package io.heapy.kinetica.terminal

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalContrastTest {
    @Test fun correctionMatchesOriginalGhosttyShader() {
        var count = 0
        for (encoded in terminalContrastFixtures()) for (value in Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonArray) {
            val c = value.jsonArray
            val minimum = c[0].jsonPrimitive.double
            val fg = c[1].jsonPrimitive.int; val bg = c[2].jsonPrimitive.int
            val alpha = c[3].jsonPrimitive.int; val expected = c[4].jsonPrimitive.int
            val actual = terminalContrastCorrection(minimum, fg, bg, alpha / 255.0)
            assertEquals(if (expected == -1) fg else expected, actual ?: fg, "color case $count: $c")
            assertEquals(if (expected == -1) alpha else 255, if (actual == null) alpha else 255, "alpha case $count: $c")
            count++
        }
        assertEquals(8132, count)
    }

    @Test fun policyClampsRepaintsAndRetainsSynchronizedFramesWithoutChangingClipboard() {
        val session = TerminalSession(8, 2)
        val views = List(2) { TerminalViewport(session, {}) }
        val frames = List(2) { TerminalGpuFrame() }
        try {
            assertEquals(1.0, session.minimumContrast)
            for ((input, expected) in listOf(Double.NEGATIVE_INFINITY to 1.0, 0.0 to 1.0, 4.5 to 4.5, Double.POSITIVE_INFINITY to 21.0)) {
                session.minimumContrast = input; assertEquals(expected, session.minimumContrast)
            }
            assertFailsWith<IllegalArgumentException> { session.minimumContrast = Double.NaN }
            session.minimumContrast = 3.0
            session.write("\u001b[?25l\u001b[38;2;10;10;10mA")
            views[0].selectAll(); val copied = views[0].copyData()
            fun check(minimum: Double) {
                for ((view, frame) in views.zip(frames)) { frame.update(view, 8.0, 16.0); assertEquals(minimum, frame.minimumContrast) }
            }
            check(3.0)
            session.write("\u001b[?2026h")
            session.minimumContrast = 7.0
            views.forEach { it.markAll() }; check(3.0)
            session.write("\u001b[?2026l"); check(7.0)
            assertEquals(copied, views[0].copyData())
            session.write("\u001b[!p\u001bc")
            session.configureTheme(TerminalTheme())
            assertEquals(7.0, session.minimumContrast)
        } finally { views.forEach { it.dispose() } }
    }

    @Test fun colorArtworkGraphicsAndBlockCursorKeepTheirOriginalTintAndOpacity() {
        for (shaped in listOf(false, true)) {
            val session = TerminalSession(12, 2, theme = TerminalTheme(background = 0, foreground = 0x101010))
            val view = TerminalViewport(session, {})
            val frame = if (!shaped) TerminalGpuFrame() else TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) { run ->
                run.input.clusters.map { TerminalShapedGlyph(0, it + 1, it, 0, 0) }
            }
            try {
                session.minimumContrast = 7.0
                session.write("\u001b[?25l\u001b[2;4;53;9mABC")
                frame.update(view, 8.0, 16.0)
                // Explicitly exercise actual raster color metadata, even for an ordinary Latin codepoint.
                val atlas = TerminalGlyphAtlas(64)
                atlas.prepare(frame.keys, { key -> GlyphBitmap(8, 16, ByteArray(8 * 16 * 4),
                    if (shaped) key.glyph == 2 else key.text == "B") }, {}, { _, _ -> })
                frame.rows[0].glyphs[2] = frame.rows[0].glyphs[2].copy(contrastExcluded = true)
                val batches = TerminalGpuBatches(); batches.build(frame, atlas)
                val pixels = batches.pages.single().data
                assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), pixels.copyOfRange(8, 12))
                val faint = 128f / 255f
                assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), pixels.copyOfRange(20, 24))
                assertEquals(faint.toDouble(), pixels[35].toDouble(), 1e-7)
                assertEquals(0xffffff, frame.rows[0].decorations.first().color)
                assertEquals(1.0, frame.rows[0].decorations.first().opacity)
                session.write("\u001b[H\u001b[2 q\u001b[?25h")
                frame.update(view, 8.0, 16.0)
                assertNull(terminalGlyphContrast(frame, frame.rows[0], frame.rows[0].glyphs[0], false))
                session.write("\u001b[6 q")
                frame.update(view, 8.0, 16.0)
                assertEquals(0xffffff, terminalGlyphContrast(frame, frame.rows[0], frame.rows[0].glyphs[0], false))
            } finally { view.dispose() }
        }
    }

    @Test fun correctionUsesResolvedSelectionInverseAndBoldColorsAgainstEachCellBackground() {
        val session = TerminalSession(8, 2, theme = TerminalTheme(background = 0, foreground = 0x101010))
        val view = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.minimumContrast = 7.0
            session.boldColor = TerminalBoldColor.Rgb(0xffffff)
            session.selectionColors = TerminalSelectionColors(TerminalSelectionColor.Rgb(0xeeeeee), TerminalSelectionColor.Rgb(0xffffff))
            session.write("\u001b[?25lA\u001b[1mB\u001b[22;7mC\u001b[27mD")
            view.beginSelection(3, 0); view.endSelection(4, 0)
            frame.update(view, 8.0, 16.0)
            assertEquals(listOf(0xffffff, null, 0xffffff, 0), frame.rows[0].glyphs.take(4).map {
                terminalGlyphContrast(frame, frame.rows[0], it, false)
            })
        } finally { view.dispose() }
    }

    @Test fun graphicExemptionsIncludeBothSupplementaryRangesAndExcludeAdjacentText() {
        for (range in listOf(0x2500..0x259f, 0xe0b0..0xe0d7, 0x1fb00..0x1fbff, 0x1cc00..0x1cebf)) {
            assertFalse(terminalContrastExcluded(range.first - 1)); assertFalse(terminalContrastExcluded(range.last + 1))
            for (cp in range) assertTrue(terminalContrastExcluded(cp))
        }
        assertEquals(0x1fb00, terminalFirstCodepoint("\ud83e\udf00"))
        assertEquals(0, terminalFirstCodepoint(""))
    }
}
