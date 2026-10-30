package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalOverlineTest {
    @Test fun overlineUsesForegroundIndependentlyOfUnderlineAndSurvivesRenderHolds() {
        val session = TerminalSession(8, 2)
        val viewport = TerminalViewport(session, {})
        val software = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        val rectangles = mutableListOf<TerminalRect>()
        val painter = object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {}
            override fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
                rectangles += TerminalRect(x, y, width, height, color)
            }
        }
        try {
            session.write("\u001b[?25l\u001b[31;53;4;9;58;2;1;2;3m  \u001b[55m ")
            fun check(color: Int) {
                frame.update(viewport, 8.0, 16.0)
                rectangles.clear(); software.paint(painter, 8.0, 16.0, force = true)
                // GPU shaping may split runs differently; compare covered pixels, not batching.
                fun topPixels(values: List<TerminalRect>) = values.filter { it.y == 0.0 }.flatMap { rect ->
                    assertEquals(1.0, rect.height)
                    List(rect.width.toInt()) { rect.x.toInt() + it to rect.color }
                }
                val expected = List(16) { it to color }
                assertEquals(expected, topPixels(frame.rows[0].decorations))
                assertEquals(expected, topPixels(rectangles))
                assertTrue(rectangles.any { it.y == 14.0 && it.color == 0x010203 }, "Underline keeps its own color")
                assertTrue(rectangles.any { it.y == 8.0 && it.color == color }, "Strike coexists with overline")
            }
            check(0xcd3131)
            session.write("\u001b]4;1;#123456\u0007")
            check(0x123456)
            session.write("\u001b[?2026h\u001b[H\u001b[0m   ")
            check(0x123456)
            session.write("\u001b[?2026l")
            frame.update(viewport, 8.0, 16.0)
            assertTrue(frame.rows[0].decorations.isEmpty())
        } finally { viewport.dispose(); software.dispose() }
    }

    @Test fun hiddenInverseAndFaintApplyToOverlineAndCompressedRowsRetainIt() {
        val session = TerminalSession(128, 2)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.write("\u001b[?25l\u001b[53;8m \u001b[28;7;48;2;20;40;60m \u001b[27;2;38;2;100;120;140m ")
            frame.update(viewport, 8.0, 16.0)
            val top = frame.rows[0].decorations.filter { it.y == 0.0 }
            assertEquals(listOf(TerminalRect(8.0, 0.0, 8.0, 1.0, 0x14283c),
                TerminalRect(16.0, 0.0, 8.0, 1.0, 0x323c46)), top)
            val line = session.screenLine(0)
            line.compress()
            assertTrue(line.compressed)
            assertEquals(TerminalStyle.OVERLINE or TerminalStyle.HIDDEN, line.style(0))
            assertEquals(TerminalStyle.OVERLINE or TerminalStyle.INVERSE, line.style(1))
            assertEquals(TerminalStyle.OVERLINE or TerminalStyle.FAINT, line.style(2))
        } finally { viewport.dispose() }
    }
}
