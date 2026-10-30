package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalFaintTest {
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

    @Test fun colorGlyphsAndMasksReceivePremultipliedOpacityWithoutTintingColorArtwork() {
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
                val alpha = 128f / 255f
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
