package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalBlockDrawingTest {
    @Test fun solidBlockElementsMatchTheirUnicodeShapesThroughTheViewport() {
        // Each byte is an eight-pixel row, with its most significant bit at the left.
        val shapes = mapOf(
            '▀' to "ffffffff00000000", '▁' to "00000000000000ff", '▂' to "000000000000ffff",
            '▃' to "0000000000ffffff", '▄' to "00000000ffffffff", '▅' to "000000ffffffffff",
            '▆' to "0000ffffffffffff", '▇' to "00ffffffffffffff", '█' to "ffffffffffffffff",
            '▉' to "fefefefefefefefe", '▊' to "fcfcfcfcfcfcfcfc", '▋' to "f8f8f8f8f8f8f8f8",
            '▌' to "f0f0f0f0f0f0f0f0", '▍' to "e0e0e0e0e0e0e0e0", '▎' to "c0c0c0c0c0c0c0c0",
            '▏' to "8080808080808080", '▐' to "0f0f0f0f0f0f0f0f", '▔' to "ff00000000000000",
            '▕' to "0101010101010101", '▖' to "00000000f0f0f0f0", '▗' to "000000000f0f0f0f",
            '▘' to "f0f0f0f000000000", '▙' to "f0f0f0f0ffffffff", '▚' to "f0f0f0f00f0f0f0f",
            '▛' to "fffffffff0f0f0f0", '▜' to "ffffffff0f0f0f0f", '▝' to "0f0f0f0f00000000",
            '▞' to "0f0f0f0ff0f0f0f0", '▟' to "0f0f0f0fffffffff",
        )
        val session = TerminalSession(shapes.size, 1)
        session.write("\u001b[?25l\u001b[37;40m" + shapes.keys.joinToString(""))
        val viewport = TerminalViewport(session, {})
        val width = shapes.size * 8
        val pixels = IntArray(width * 8)
        try {
            viewport.paint(object : TerminalPainter {
                override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
                    for (py in y.toInt() until (y + height).toInt()) for (px in x.toInt() until (x + width).toInt()) {
                        pixels[py * shapes.size * 8 + px] = color
                    }
                }
                override fun text(value: String, x: Double, y: Double, color: Int, style: Int) = fail("Solid blocks must not use a font: $value")
            }, 8.0, 8.0)
            shapes.entries.forEachIndexed { column, (character, pattern) ->
                val rows = pattern.chunked(2).map { it.toInt(16) }
                for (y in 0..7) for (x in 0..7) assertEquals(
                    if (rows[y] and (128 shr x) != 0) 0xe5e5e5 else 0x000000,
                    pixels[y * width + column * 8 + x], "Block $character at $x,$y")
            }
        } finally { viewport.dispose() }
    }

    @Test fun blockColorsAndDecorationsFollowCellAttributes() {
        val session = TerminalSession(4, 1)
        session.write("\u001b[?25l\u001b[38;2;200;100;50;48;2;10;20;30m█\u001b[7m█\u001b[27;2m█\u001b[22;8m█")
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            frame.update(viewport, 8.0, 16.0)
            assertTrue(frame.keys.isEmpty(), "Blocks must bypass the font atlas")
            fun colorAt(column: Int) = frame.rows.single().backgrounds.last { column * 8.0 >= it.x && column * 8.0 < it.x + it.width }.color
            assertEquals(0xc86432, colorAt(0))
            assertEquals(0x0a141e, colorAt(1)) // Inverse.
            assertEquals(0x643219, colorAt(2)) // Faint.
            assertEquals(0x0a141e, colorAt(3)) // Concealed.
            session.write("\u001b[H\u001b[0;4m▀")
            viewport.beginSelection(0, 0); viewport.endSelection(1, 0)
            frame.update(viewport, 8.0, 16.0)
            assertEquals(viewport.theme.selection, frame.rows[0].backgrounds.first().color)
            assertTrue(frame.rows[0].decorations.any { it.x == 0.0 && it.width == 8.0 })
            assertEquals("▀", viewport.selectedText(), "Geometric rendering must preserve the original text")
        } finally { viewport.dispose() }
    }

    @Test fun textRunsStopAtBlocksAndCombiningMarksStillReachTheFont() {
        val session = TerminalSession(12, 1)
        session.write("\u001b[?25lab█cd█\u0301░")
        val viewport = TerminalViewport(session, {})
        val text = mutableListOf<Pair<String, Double>>()
        try {
            viewport.paint(object : TerminalPainter {
                override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) = Unit
                override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {
                    if (value.isNotBlank()) text += value.trimEnd() to x
                }
            }, 8.0, 16.0)
            assertEquals(listOf("ab" to 0.0, "cd" to 24.0, "█\u0301" to 40.0, "░" to 48.0), text)
        } finally { viewport.dispose() }
    }
}
