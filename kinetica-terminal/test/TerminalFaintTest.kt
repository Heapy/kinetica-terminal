package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalFaintTest {
    @Test fun configuredOpacityClampsQuantizesAndSurvivesResets() {
        val session = TerminalSession(4, 1)
        assertEquals(0.5, session.faintOpacity)
        for ((value, expected) in listOf(Double.NEGATIVE_INFINITY to 0.0, -10.0 to 0.0,
            0.0 to 0.0, 0.001 to 1.0 / 255.0, 0.1 to 26.0 / 255.0, 0.5 to 128.0 / 255.0,
            0.9 to 230.0 / 255.0, 1.0 to 1.0, 10.0 to 1.0, Double.POSITIVE_INFINITY to 1.0)) {
            session.faintOpacity = value
            assertEquals(value.coerceIn(0.0, 1.0), session.faintOpacity)
            assertEquals(expected, terminalForegroundOpacity(TerminalStyle.FAINT, session.faintOpacity))
            assertEquals(1.0, terminalForegroundOpacity(TerminalStyle.BOLD, session.faintOpacity))
        }
        session.faintOpacity = 0.1
        assertFailsWith<IllegalArgumentException> { session.faintOpacity = Double.NaN }
        session.write("\u001b[!p\u001bc")
        session.configureTheme(TerminalTheme(foreground = 0xffffff))
        session.write("\u001b]10;#123456\u0007\u001b]110\u0007")
        assertEquals(0.1, session.faintOpacity, "Terminal and theme changes do not replace host configuration")
    }

    @Test fun configuredOpacityInvalidatesAllViewsButRetainsThePresentedFrameDuringAHold() {
        val session = TerminalSession(8, 2)
        val views = List(2) { TerminalViewport(session, {}) }
        val frames = listOf(TerminalGpuFrame(), TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) {
            listOf(TerminalShapedGlyph(0, 42, 0, 0, 0, usesForeground = true))
        })
        try {
            session.write("\u001b[?25l\u001b[4:3;53;9;2mA\u001b[22mA")
            val keys = ArrayList<Set<GlyphKey>>()
            for ((index, view) in views.withIndex()) {
                frames[index].update(view, 8.0, 16.0)
                keys += frames[index].keys.toSet()
            }
            fun assertOpacity(expected: Double) {
                for ((index, view) in views.withIndex()) {
                    val frame = frames[index]
                    frame.update(view, 8.0, 16.0)
                    assertEquals(expected, frame.rows[0].glyphs[0].opacity)
                    assertEquals(1.0, frame.rows[0].glyphs[1].opacity)
                    assertTrue(frame.rows[0].decorations.filter { it.x == 0.0 }.all { it.opacity == expected })
                    assertTrue(frame.rows[0].backgrounds.all { it.opacity == 1.0 })
                    assertEquals(keys[index], frame.keys.toSet(), "Opacity changes must reuse glyph atlas entries")
                }
            }
            session.faintOpacity = 0.1
            assertOpacity(26.0 / 255.0)
            session.write("\u001b[?2026h")
            session.faintOpacity = 0.9
            views.forEach { it.markAll() }
            assertOpacity(26.0 / 255.0)
            session.write("\u001b[?2026l")
            assertOpacity(230.0 / 255.0)
            session.faintOpacity = 0.0
            assertOpacity(0.0)
            session.faintOpacity = 1.0
            assertOpacity(1.0)
        } finally { views.forEach { it.dispose() } }
    }

    @Test fun foregroundOpacitySurvivesShapingSelectionAndFrozenFramesWithoutDimmingBackgrounds() {
        for (shaped in listOf(false, true)) {
            val session = TerminalSession(8, 2)
            val viewport = TerminalViewport(session, {})
            val frame = if (shaped) TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) {
                listOf(TerminalShapedGlyph(0, 42, 0, 0, 0, usesForeground = true))
            } else TerminalGpuFrame()
            try {
                session.write("\u001b[?25l\u001b[38;2;200;100;50;48;2;10;20;30;58;2;40;200;60;53;4;9;2mA\u001b[22mA")
                viewport.beginSelection(0, 0); viewport.endSelection(1, 0)
                frame.update(viewport, 8.0, 16.0)
                val (faint, normal) = frame.rows[0].glyphs.take(2)
                assertEquals(0xc86432, faint.color)
                assertEquals(normal.key, faint.key, "Opacity must not duplicate the atlas, including foreground color layers")
                assertEquals(128.0 / 255.0, faint.opacity)
                assertEquals(1.0, normal.opacity)
                assertTrue(frame.rows[0].backgrounds.all { it.opacity == 1.0 })
                assertEquals(viewport.theme.selection, frame.rows[0].backgrounds.first().color)
                val decorations = frame.rows[0].decorations.filter { it.x == 0.0 }
                assertEquals(setOf(0x28c83c, 0xc86432), decorations.map { it.color }.toSet())
                assertTrue(decorations.all { it.opacity == 128.0 / 255.0 })
                session.write("\u001b[?2026h\u001b[H\u001b[0mAA")
                viewport.markAll(); frame.update(viewport, 8.0, 16.0)
                assertEquals(faint, frame.rows[0].glyphs.first())
                session.write("\u001b[?2026l")
                frame.update(viewport, 8.0, 16.0)
                assertTrue(frame.rows[0].glyphs.all { it.opacity == 1.0 })
            } finally { viewport.dispose() }
        }
    }

    @Test fun colorArtworkIgnoresFaintWhileMonochromeMasksReceivePremultipliedOpacity() {
        for (colored in listOf(false, true)) {
            val session = TerminalSession(4, 1)
            val viewport = TerminalViewport(session, {})
            val frame = TerminalGpuFrame()
            try {
                session.write("\u001b[?25l\u001b[31;2mA\u001b[22mA")
                frame.update(viewport, 8.0, 16.0)
                val atlas = TerminalGlyphAtlas(32)
                atlas.prepare(frame.keys, { GlyphBitmap(8, 16, ByteArray(8 * 16 * 4), colored) }, {}, { _, _ -> })
                val batches = TerminalGpuBatches()
                batches.build(frame, atlas)
                val data = batches.pages.single().data
                val alpha = if (colored) 1f else 128f / 255f
                val color = if (colored) 0xffffff else 0xcd3131
                assertEquals(alpha.toDouble(), data[11].toDouble(), 1e-7)
                assertEquals(1f, data[23])
                for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
                    val expected = (color shr shift and 255) / 255f
                    assertEquals((expected * alpha).toDouble(), data[8 + channel].toDouble(), 1e-7)
                    assertEquals(expected.toDouble(), data[20 + channel].toDouble(), 1e-7)
                }
            } finally { viewport.dispose() }
        }
    }
}
