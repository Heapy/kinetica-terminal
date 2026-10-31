package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSelectionColorsTest {
    private fun shapedFrame() = TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) { run ->
        run.input.clusters.distinct().map { TerminalShapedGlyph(0, 42, it, 0, 0, usesForeground = true) }
    }
    private fun background(row: TerminalDrawRow, column: Int): Int =
        row.backgrounds.last { column * 8.0 >= it.x && column * 8.0 < it.x + it.width }.color

    @Test fun everySelectionColorCombinationRepaintsSoftwareAndShapedViewsAfterBoldAndInverse() {
        val session = TerminalSession(8, 2)
        session.boldColor = TerminalBoldColor.BRIGHT
        val views = List(3) { TerminalViewport(session, {}) }
        val frames = listOf(TerminalGpuFrame(), shapedFrame())
        val painted = mutableListOf<Pair<String, Int>>()
        val painter = object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { if (y == 0.0) painted += value to color }
        }
        try {
            session.write("\u001b[?25l\u001b[1;31;44;4:3;53;9mA\u001b[7mB\u001b[0;3;4:3;53;9mC" +
                "\u001b[38;2;17;34;51;48;2;68;85;102mD\u001b[0;32mE")
            views.forEach { it.beginSelection(0, 0); it.endSelection(4, 0) }
            val cellForeground = listOf(session.paletteColor(9), session.paletteColor(4), session.theme.foreground, 0x112233)
            val cellBackground = listOf(session.paletteColor(4), session.paletteColor(9), session.theme.background, 0x445566)
            val foregrounds = listOf(null to List(4) { session.theme.background },
                TerminalSelectionColor.CELL_FOREGROUND to cellForeground,
                TerminalSelectionColor.CELL_BACKGROUND to cellBackground,
                TerminalSelectionColor.Rgb(0x123456) to List(4) { 0x123456 })
            val backgrounds = listOf(null to List(4) { session.theme.foreground },
                TerminalSelectionColor.CELL_FOREGROUND to cellForeground,
                TerminalSelectionColor.CELL_BACKGROUND to cellBackground,
                TerminalSelectionColor.Rgb(0x654321) to List(4) { 0x654321 })
            fun check(fg: List<Int>, bg: List<Int>) {
                for ((index, frame) in frames.withIndex()) {
                    frame.update(views[index], 8.0, 16.0)
                    val row = frame.rows[0]
                    assertEquals(fg + session.paletteColor(2), row.glyphs.take(5).map { it.color })
                    assertEquals(bg, (0..3).map { background(row, it) })
                    assertEquals(session.theme.background, background(row, 4), "Unselected background stays unchanged")
                    for (column in 0..3) assertTrue(row.decorations.filter { it.x == column * 8.0 }.all { it.color == fg[column] })
                }
                painted.clear(); views[2].paint(painter, 8.0, 16.0)
                assertEquals(fg + session.paletteColor(2), painted.flatMap { (text, color) -> List(text.length) { color } }.take(5))
            }
            check(cellForeground, List(4) { session.theme.selection })
            for ((foreground, fg) in foregrounds) for ((background, bg) in backgrounds) {
                session.selectionColors = TerminalSelectionColors(foreground, background)
                check(fg, bg)
            }
            session.selectionColors = null
            check(cellForeground, List(4) { session.theme.selection })
        } finally { views.forEach { it.dispose() } }
    }

    @Test fun defaultsFollowTerminalThemeAndReverseColorsWhileHostConfigurationSurvivesResets() {
        assertFailsWith<IllegalArgumentException> { TerminalSelectionColor.Rgb(-1) }
        assertFailsWith<IllegalArgumentException> { TerminalSelectionColor.Rgb(0x1000000) }
        val session = TerminalSession(8, 2)
        val view = TerminalViewport(session, {})
        val frame = shapedFrame()
        session.selectionColors = TerminalSelectionColors()
        try {
            session.write("\u001b[?25l\u001b[31;44;7mA")
            view.selectAll()
            val copied = view.copyData()
            for (reverse in listOf(false, true)) {
                session.configureTheme(TerminalTheme(foreground = 0x123456, background = 0x654321))
                session.write(if (reverse) "\u001b[?5h" else "\u001b[?5l")
                frame.update(view, 8.0, 16.0)
                assertEquals(if (reverse) 0x123456 else 0x654321, frame.rows[0].glyphs.first().color)
                assertEquals(if (reverse) 0x654321 else 0x123456, background(frame.rows[0], 0))
            }
            session.selectionColors = TerminalSelectionColors(TerminalSelectionColor.CELL_BACKGROUND, TerminalSelectionColor.Rgb(0xabcdef))
            assertEquals(copied, view.copyData(), "Selection paint does not rewrite cell colors or clipboard styles")
            val configured = session.selectionColors
            session.write("\u001b[!p\u001bc")
            assertEquals(configured, session.selectionColors)
        } finally { view.dispose() }
    }

    @Test fun selectionPreservesWideHiddenAndFaintSemanticsAndCursorAndExplicitUnderlineOverrides() {
        val session = TerminalSession(8, 2, cursorStyle = TerminalCursorStyle.BLOCK)
        val view = TerminalViewport(session, {})
        val frame = shapedFrame()
        session.selectionColors = TerminalSelectionColors(TerminalSelectionColor.Rgb(0x123456), TerminalSelectionColor.Rgb(0x654321))
        try {
            session.write("\u001b[?25l\u001b[4;53;9;58;2;1;2;3;2m界\u001b[8mH\u001b[28;22mA")
            view.beginSelection(0, 0); view.endSelection(4, 0)
            frame.update(view, 8.0, 16.0)
            val row = frame.rows[0]
            assertEquals(listOf(0.0, 24.0), row.glyphs.map { it.x })
            assertTrue(row.glyphs.all { it.color == 0x123456 })
            assertEquals(listOf(128.0 / 255.0, 1.0), row.glyphs.map { it.opacity })
            assertEquals(List(4) { 0x654321 }, (0..3).map { background(row, it) })
            assertTrue(row.backgrounds.all { it.opacity == 1.0 })
            assertTrue(row.decorations.none { it.x == 16.0 })
            assertTrue(row.decorations.any { it.x == 0.0 && it.y == 14.0 && it.color == 0x010203 })
            session.write("\u001b[?25h\u001b[1;4H")
            frame.update(view, 8.0, 16.0)
            assertEquals(session.theme.background, frame.rows[0].glyphs.last().color)
            assertEquals(session.theme.cursor, background(frame.rows[0], 3))
        } finally { view.dispose() }
    }

    @Test fun heldFramesRetainNullableSelectionConfigurationAndColorsUntilRelease() {
        val configurations = listOf(null, TerminalSelectionColors(),
            TerminalSelectionColors(TerminalSelectionColor.CELL_BACKGROUND, TerminalSelectionColor.CELL_FOREGROUND))
        for ((index, before) in configurations.withIndex()) {
            val session = TerminalSession(8, 2)
            val view = TerminalViewport(session, {})
            val frame = shapedFrame()
            try {
                session.selectionColors = before
                session.write("\u001b[?25l\u001b[31;44mA")
                view.selectAll()
                frame.update(view, 8.0, 16.0)
                val glyph = frame.rows[0].glyphs.first()
                val bg = background(frame.rows[0], 0)
                session.write("\u001b[?2026h")
                val after = configurations[(index + 1) % configurations.size]
                session.selectionColors = after
                session.configureTheme(TerminalTheme(foreground = 0x123456, background = 0x654321, selection = 0x2468ac))
                session.write("\u001b]4;1;#112233;4;#445566\u0007")
                view.markAll(); frame.update(view, 8.0, 16.0)
                assertEquals(glyph, frame.rows[0].glyphs.first())
                assertEquals(bg, background(frame.rows[0], 0))
                session.write("\u001b[?2026l")
                frame.update(view, 8.0, 16.0)
                val expected = when (after) {
                    null -> 0x112233 to 0x2468ac
                    TerminalSelectionColors() -> 0x654321 to 0x123456
                    else -> 0x445566 to 0x112233
                }
                assertEquals(expected.first, frame.rows[0].glyphs.first().color)
                assertEquals(expected.second, background(frame.rows[0], 0))
            } finally { view.dispose() }
        }
    }
}
