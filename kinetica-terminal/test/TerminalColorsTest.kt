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

    @Test fun reverseVideoSwapsOnlyDefaultsAndRepaintsBothSurfaces() {
        val theme = TerminalTheme(foreground = 0x123456, background = 0x345678)
        val session = TerminalSession(4, 2, theme = theme)
        session.write("\u001b[?25lA\u001b[31mB\u001b[0;44mC\u001b[31mD\r\n\u001b[0;7;4mE")
        val gpu = TerminalViewport(session, {})
        val software = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        val painter = ColorsPainter()
        fun background(column: Int, row: Int = 0) = frame.rows[row].backgrounds.first {
            column * 8.0 >= it.x && column * 8.0 < it.x + it.width
        }.color
        try {
            frame.update(gpu, 8.0, 16.0); software.paint(painter, 8.0, 16.0)
            val keys = frame.keys.toSet()
            painter.text.clear(); painter.fills.clear()
            session.write("\u001b[?5h")
            assertEquals(0, gpu.firstDirtyRow); assertEquals(1, gpu.lastDirtyRow)
            frame.update(gpu, 8.0, 16.0); software.paint(painter, 8.0, 16.0)
            assertEquals(theme, session.theme, "OSC/configuration colors retain identity")
            assertEquals(theme.copy(foreground = theme.background, background = theme.foreground), gpu.theme)
            assertEquals(keys, frame.keys, "Changing defaults does not rerasterize glyph shapes")
            assertEquals(listOf(theme.background, terminalPalette(1), theme.background, terminalPalette(1)), frame.rows[0].glyphs.map { it.color })
            assertEquals(listOf(theme.foreground, theme.foreground, terminalPalette(4), terminalPalette(4)), (0..3).map { background(it) })
            assertEquals(theme.foreground, frame.rows[1].glyphs.single().color, "SGR inverse acts after default resolution")
            assertEquals(theme.background, background(0, 1))
            assertEquals(theme.foreground, frame.rows[1].decorations.first().color)
            assertEquals(listOf(theme.background, terminalPalette(1), theme.background, terminalPalette(1), theme.foreground),
                painter.text.filter { it.first.trim().isNotEmpty() }.map { it.second })
            session.write("\u001b[?5l")
            frame.update(gpu, 8.0, 16.0)
            assertEquals(theme.foreground, frame.rows[0].glyphs.first().color)
        } finally { gpu.dispose(); software.dispose() }
    }

    @Test fun reverseVideoPreservesSelectionAndAppliesCursorOverrideToExplicitUnderlines() {
        val session = TerminalSession(4, 2)
        val viewport = TerminalViewport(session, {})
        val frame = TerminalGpuFrame()
        try {
            session.write("\u001b[4;58;2;1;2;3mAB\u001b[0m\u001b[2 q\u001b[H\u001b[?5h")
            viewport.beginSelection(1, 0); viewport.endSelection(2, 0)
            frame.update(viewport, 8.0, 16.0)
            assertEquals(session.theme.cursor, frame.rows[0].backgrounds.first().color)
            assertEquals(session.theme.foreground, frame.rows[0].glyphs.first().color, "Block text uses the effective background")
            assertTrue(frame.rows[0].backgrounds.any { it.color == session.theme.selection })
            assertEquals(session.theme.foreground, frame.rows[0].decorations.first { it.x == 0.0 }.color)
            assertEquals(0x010203, frame.rows[0].decorations.first { it.x == 8.0 }.color)
        } finally { viewport.dispose() }
    }

    @Test fun reverseVideoIsFrozenWithTheDisplayedThemeAcrossHolds() {
        val session = TerminalSession(4, 2)
        val viewport = TerminalViewport(session, {})
        try {
            val initial = session.theme
            session.write("old\u001b[?5h\u001b[?2026h\u001b[?5l\u001b]10;#123456;#234567\u0007")
            assertFalse(session.reverseColors)
            assertEquals(initial.copy(foreground = initial.background, background = initial.foreground), viewport.theme)
            viewport.markAll()
            val painter = ColorsPainter()
            viewport.paint(painter, 8.0, 16.0, force = true)
            assertTrue(painter.text.any { it.first.contains("old") && it.second == initial.background })
            session.write("\u001b[?2026l")
            assertEquals(session.theme, viewport.theme)
            session.write("\u001b[?2026h\u001b[?5h")
            assertEquals(session.theme, viewport.theme)
            session.flushSynchronizedOutput()
            assertEquals(0x123456, viewport.theme.background)
            assertEquals(0x234567, viewport.theme.foreground)
        } finally { viewport.dispose() }
    }

    private class ColorsPainter : TerminalPainter {
        val fills = mutableListOf<Int>()
        val text = mutableListOf<Pair<String, Int>>()
        override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) { fills += color }
        override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { text += value to color }
    }
}
