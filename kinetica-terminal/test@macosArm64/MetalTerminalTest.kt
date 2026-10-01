@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import kotlinx.cinterop.useContents
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.test.*

class MetalTerminalTest {
    @Test fun changingFontIdentityInvalidatesAtlasEvenWhenCellMetricsMatch() {
        NSApplication.sharedApplication()
        assertNotNull(NSFont.fontWithName("Menlo-Regular", 14.0))
        val session = TerminalSession(8, 2)
        session.write("\u001b[?25l0MWabc")
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(112.0, 56.0, 2.0, 14.0, 14.0, 28.0)
            val initial = renderer.snapshot(viewport)
            val cached = renderer.rasterizations
            renderer.resize(112.0, 56.0, 2.0, 14.0, 14.0, 28.0, fontFamily = "Menlo-Regular")
            val changed = renderer.snapshot(viewport)
            assertTrue(renderer.rasterizations > cached)
            assertFalse(initial.contentEquals(changed), "A different font must change the glyph pixels")
            val second = renderer.rasterizations
            viewport.markAll(); renderer.snapshot(viewport)
            assertEquals(second, renderer.rasterizations)
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun blockArtworkFillsCellEdgesWithoutFontSeams() {
        NSApplication.sharedApplication()
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val cellHeight = ceil(font.ascender - font.descender + font.leading + 2.0)
        val artwork = listOf("█".repeat(32), "█".repeat(32), " ▐▛███▛█", "▝▜██████▀", " ▝▝   ▝▝", "▘▝▖▗▚▞▙▟▀▄▌▐")
        // Independent quadrant reference: top-left, top-right, bottom-left, bottom-right.
        val quadrants = mapOf(' ' to 0, '█' to 15, '▐' to 10, '▛' to 7, '▜' to 11, '▝' to 2,
            '▀' to 3, '▄' to 12, '▌' to 5, '▘' to 1, '▖' to 4, '▗' to 8, '▚' to 9, '▞' to 6, '▙' to 13, '▟' to 14)
        val session = TerminalSession(32, artwork.size)
        session.write("\u001b[?25l\u001b[38;2;204;124;94m" + artwork.joinToString("\r\n"))
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            for (scale in listOf(1.0, 1.25, 2.0)) {
                val width = ceil(cellWidth * session.columns) + 0.37
                renderer.resize(width, cellHeight * session.rows + 0.23, scale, 14.0, cellWidth, cellHeight)
                val pixels = renderer.snapshot(viewport)
                val stride = ceil(width * scale).toInt() * 4
                for ((row, line) in artwork.withIndex()) for (column in 0 until session.columns) {
                    val mask = quadrants.getValue(line.getOrElse(column) { ' ' })
                    val left = round(column * cellWidth * scale).toInt()
                    val top = round(row * cellHeight * scale).toInt()
                    val middleX = round((column + 0.5) * cellWidth * scale).toInt()
                    val middleY = round((row + 0.5) * cellHeight * scale).toInt()
                    for (y in top until round((row + 1) * cellHeight * scale).toInt()) {
                        for (x in left until round((column + 1) * cellWidth * scale).toInt()) {
                            val quadrant = (if (x < middleX) 0 else 1) + (if (y < middleY) 0 else 2)
                            val expected = if (mask and (1 shl quadrant) != 0) listOf(94, 124, 204) else listOf(30, 30, 30)
                            for (channel in 0..2) assertEquals(expected[channel], pixels[y * stride + x * 4 + channel].toInt() and 255,
                                "Block seam at row=$row column=$column x=$x y=$y scale=$scale channel=$channel")
                        }
                    }
                }
            }
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun repeatedDigitsKeepIdenticalPixelsAtFractionalCellAndWindowWidths() {
        NSApplication.sharedApplication()
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val cellHeight = ceil(font.ascender - font.descender + font.leading + 2.0)
        val session = TerminalSession(64, 4)
        session.write("\u001b[?25l")
        val styles = listOf("0", "0;1", "0;2", "0;7")
        styles.forEachIndexed { row, style -> session.write("\u001b[${row + 1};1H\u001b[${style}m" + "0".repeat(64)) }
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            for (scale in listOf(2.0, 1.0)) for (extraWidth in listOf(0.0, 0.37)) {
                val width = ceil(cellWidth * session.columns) + extraWidth
                renderer.resize(width, cellHeight * session.rows + 0.23, scale, 14.0, cellWidth, cellHeight)
                val pixels = renderer.snapshot(viewport)
                val stride = ceil(width * scale).toInt() * 4
                val glyphWidth = floor(cellWidth * scale).toInt()
                val glyphHeight = (cellHeight * scale).toInt()
                fun glyph(column: Int, row: Int): ByteArray {
                    val x = round(column * cellWidth * scale).toInt()
                    val y = round(row * cellHeight * scale).toInt()
                    return ByteArray(glyphWidth * glyphHeight * 4) { index ->
                        pixels[(y + index / (glyphWidth * 4)) * stride + x * 4 + index % (glyphWidth * 4)]
                    }
                }
                for (row in styles.indices) {
                    val reference = glyph(0, row)
                    for (column in 1 until session.columns) assertContentEquals(reference, glyph(column, row),
                        "Same zero must not lose strokes at column=$column style=${styles[row]} scale=$scale width=$width")
                }
            }
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun selectionRepaintsWholeWideGlyphAndClearsAfterOverwrite() {
        NSApplication.sharedApplication()
        val session = TerminalSession(8, 3)
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(80.0, 60.0, 2.0, 14.0, 10.0, 20.0)
            session.write("\u001b[?25la界z\r\nnext")
            viewport.beginSelection(2, 0); viewport.endSelection(3, 0)
            assertEquals("界", viewport.selectedText())
            fun check(selected: Boolean) {
                val pixels = renderer.snapshot(viewport)
                fun rgb(x: Int): List<Int> {
                    val i = (2 * 160 + x) * 4
                    return listOf(pixels[i + 2].toInt() and 255, pixels[i + 1].toInt() and 255, pixels[i].toInt() and 255)
                }
                assertEquals(listOf(30, 30, 30), rgb(2))
                assertEquals(if (selected) listOf(38, 79, 120) else listOf(30, 30, 30), rgb(22))
                assertEquals(if (selected) listOf(38, 79, 120) else listOf(30, 30, 30), rgb(42))
                assertEquals(listOf(30, 30, 30), rgb(62))
            }
            check(true)
            session.write("\u001b[3;1Hsafe"); check(true)
            session.write("\u001b[1;2HX"); check(false)
            assertEquals("", viewport.selectedText())
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun synchronizedFramesAndCursorShapesProduceRealMetalPixels() {
        NSApplication.sharedApplication()
        val session = TerminalSession(4, 2)
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(40.0, 40.0, 2.0, 14.0, 10.0, 20.0)
            fun rgb(x: Int, y: Int): List<Int> {
                val pixels = renderer.snapshot(viewport)
                val i = (y * 80 + x) * 4
                return listOf(pixels[i + 2].toInt() and 255, pixels[i + 1].toInt() and 255, pixels[i].toInt() and 255)
            }
            session.write("\u001b[?25l\u001b[41m ")
            assertEquals(listOf(205, 49, 49), rgb(2, 2))
            session.write("\u001b[?2026h\u001b[2J\u001b[H\u001b[44m ")
            viewport.markAll() // A forced redraw cannot expose the incomplete frame.
            assertEquals(listOf(205, 49, 49), rgb(2, 2))
            session.write("\u001b[?2026l")
            assertEquals(listOf(36, 114, 200), rgb(2, 2))
            session.write("\u001b[0m\u001b[2J\u001b[H\u001b[?25h\u001b[2 q")
            assertEquals(listOf(238, 238, 238), rgb(10, 10))
            session.write("\u001b[4 q")
            assertEquals(listOf(30, 30, 30), rgb(10, 10))
            assertEquals(listOf(238, 238, 238), rgb(10, 38))
            session.write("\u001b[6 q")
            assertEquals(listOf(238, 238, 238), rgb(2, 10))
            assertEquals(listOf(30, 30, 30), rgb(10, 38))
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun dynamicPaletteRepaintsRetainedMetalRows() {
        NSApplication.sharedApplication()
        val session = TerminalSession(4, 2)
        session.write("\u001b[?25l\u001b[44m \r\n \u001b[H")
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(40.0, 40.0, 2.0, 14.0, 10.0, 20.0)
            fun rgb(pixels: ByteArray, x: Int, y: Int): List<Int> {
                val i = (y * 80 + x) * 4
                return listOf(pixels[i + 2].toInt() and 255, pixels[i + 1].toInt() and 255, pixels[i].toInt() and 255)
            }
            val initial = renderer.snapshot(viewport)
            assertEquals(listOf(36, 114, 200), rgb(initial, 2, 42))
            session.write("\u001b]4;4;#123456\u0007\u001b]11;#654321\u0007")
            val changed = renderer.snapshot(viewport)
            assertEquals(listOf(18, 52, 86), rgb(changed, 2, 2))
            assertEquals(listOf(18, 52, 86), rgb(changed, 2, 42), "Clean second row must change too")
            assertEquals(listOf(101, 67, 33), rgb(changed, 70, 70))
            session.write("\u001b]104;4\u0007\u001b]111\u0007")
            val restored = renderer.snapshot(viewport)
            assertEquals(listOf(36, 114, 200), rgb(restored, 2, 42))
            assertEquals(listOf(30, 30, 30), rgb(restored, 70, 70))
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun realMetalPixelsRespectCellsColorsAndCache() {
        NSApplication.sharedApplication()
        val session = TerminalSession(8, 3)
        session.write("\u001b[48;2;240;20;30m \u001b[0mX界\r\ne\u0301😀")
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(80.0, 60.0, 2.0, 14.0, 10.0, 20.0)
            val pixels = renderer.snapshot(viewport)
            fun rgb(x: Int, y: Int): List<Int> {
                val i = (y * 160 + x) * 4
                return listOf(pixels[i + 2].toInt() and 255, pixels[i + 1].toInt() and 255, pixels[i].toInt() and 255)
            }
            assertEquals(listOf(240, 20, 30), rgb(2, 2))
            assertEquals(listOf(30, 30, 30), rgb(150, 100))
            assertTrue((0 until 40).any { y -> (20 until 40).any { x -> rgb(x, y).any { it > 100 } } }, "X glyph must produce pixels")
            assertTrue((40 until 80).any { y -> (20 until 60).any { x -> val p = rgb(x, y); p.max() - p.min() > 30 } }, "Emoji must retain color")
            val rasterizations = renderer.rasterizations
            assertTrue(rasterizations >= 4)
            viewport.markAll(); renderer.snapshot(viewport)
            assertEquals(rasterizations, renderer.rasterizations)
            assertTrue(renderer.drawCalls in 2..6)
            renderer.resize(80.0, 60.0, 1.0, 14.0, 10.0, 20.0)
            assertEquals(80 * 60 * 4, renderer.snapshot(viewport).size)
            assertTrue(renderer.rasterizations > rasterizations)
        } finally { renderer.dispose(); viewport.dispose() }
    }
}
