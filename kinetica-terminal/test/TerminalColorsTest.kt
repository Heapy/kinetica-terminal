package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalColorsTest {
    @Test fun paletteChangesRepaintCleanRowsInBothPainters() {
        val session = TerminalSession(4, 3)
        session.write("\u001b[?25l\u001b[31;44mX\r\n\u001b[32;4;58;5;3mY\r\n\u001b[0;38;2;205;49;49mZ\u001b[H")
        val gpuViewport = TerminalViewport(session, {})
        val softwareViewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        val painter = ColorsPainter()
        try {
            frame.update(gpuViewport, 8.0, 16.0)
            softwareViewport.paint(painter, 8.0, 16.0)
            val keys = frame.keys.toSet()
            painter.fills.clear(); painter.text.clear()
            session.write("\u001b]4;1;#123456;2;#234567;3;#345678;4;#456789\u0007")
            assertEquals(0, gpuViewport.firstDirtyRow)
            assertEquals(2, gpuViewport.lastDirtyRow)
            frame.update(gpuViewport, 8.0, 16.0)
            softwareViewport.paint(painter, 8.0, 16.0)
            assertEquals(keys, frame.keys, "Palette changes must reuse glyph rasterizations")
            assertEquals(0x123456, frame.rows[0].glyphs.single().color)
            assertEquals(0x234567, frame.rows[1].glyphs.single().color)
            assertEquals(0xcd3131, frame.rows[2].glyphs.single().color, "Direct RGB retains its value")
            assertEquals(0x456789, frame.rows[0].backgrounds.first().color)
            assertEquals(0x345678, frame.rows[1].decorations.first().color)
            assertEquals(listOf(0x123456, 0x234567, 0xcd3131), painter.text.filter { it.first.trim().isNotEmpty() }.map { it.second })
            assertTrue(painter.fills.any { it == 0x345678 })
        } finally { gpuViewport.dispose(); softwareViewport.dispose() }
    }

    @Test fun configuredThemeIsSharedByReportsAndSurfaces() {
        val original = TerminalTheme(foreground = 0x123456, background = 0x234567, cursor = 0x345678)
        val session = TerminalSession(4, 2, theme = original)
        val viewport = TerminalViewport(session, {})
        val replies = mutableListOf<String>()
        session.onInput { replies.add(it.toByteArray().decodeToString()) }
        try {
            session.write("\u001b]10;?;?;?\u0007")
            assertEquals("\u001b]10;rgb:1212/3434/5656\u0007\u001b]11;rgb:2323/4545/6767\u0007\u001b]12;rgb:3434/5656/7878\u0007", replies.single())
            session.write("\u001b]10;#111111;#222222;#333333\u0007\u001bc")
            assertEquals(0x111111, viewport.theme.foreground)
            assertEquals(0x222222, viewport.theme.background)
            assertEquals(0x333333, viewport.theme.cursor)
            session.write("\u001b]110\u0007\u001b]111\u0007\u001b]112\u0007")
            assertEquals(original, viewport.theme)
        } finally { viewport.dispose() }
    }

    @Test fun retainedHistoryReflowsWithoutFreezingIndexedColors() {
        val session = TerminalSession(6, 2, 10)
        session.write("\u001b[31mABCDEF界\r\n\u001b[32mnext\r\nlast")
        session.resize(4, 3)
        session.write("\u001b]4;1;#123456;2;#abcdef\u0007")
        assertTrue(session.historySize > 0)
        val text = mutableListOf<Pair<String, Int>>()
        for (y in 0 until session.lineCount) for (x in 0 until session.columns) {
            val line = session.line(y)
            if (line.width(x) > 0 && line.text(x) != " ") text += line.text(x) to line.foreground(x)
        }
        assertTrue(text.filter { it.first in "ABCDEF界" }.all { it.second == 0x123456 })
        assertTrue(text.filter { it.first in "nextlast" }.all { it.second == 0xabcdef })
    }

    private class ColorsPainter : TerminalPainter {
        val fills = mutableListOf<Int>()
        val text = mutableListOf<Pair<String, Int>>()
        override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) { fills += color }
        override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { text += value to color }
    }
}
