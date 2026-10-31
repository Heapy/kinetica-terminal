package io.heapy.kinetica.terminal

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*

class TerminalGlyphOpacityTest {
    @Test fun tintAndCoverageMatchOriginalGhosttyFragmentShader() {
        for (case in terminalGlyphOpacityFixtures()) {
            val (colored, alpha, coverage) = case
            val session = TerminalSession(4, 1, theme = TerminalTheme(foreground = 0xffffff, background = 0))
            val view = TerminalViewport(session, {})
            try {
                session.write("\u001b[?25lA")
                val frame = TerminalGpuFrame(); frame.update(view, 8.0, 16.0)
                frame.rows[0].glyphs[0] = frame.rows[0].glyphs[0].copy(opacity = alpha / 255.0)
                val atlas = TerminalGlyphAtlas(32)
                atlas.prepare(frame.keys, { GlyphBitmap(1, 1, ByteArray(4) { coverage.toByte() }, colored != 0) }, {}, { _, _ -> })
                val batches = TerminalGpuBatches(); batches.build(frame, atlas)
                for (channel in 0..3) {
                    val actual = (batches.pages[0].data[8 + channel] * coverage).roundToInt()
                    assertTrue(abs(actual - case[3 + channel]) <= 1,
                        "Original fragment differs: colored=$colored alpha=$alpha coverage=$coverage channel=$channel actual=$actual")
                }
            } finally { view.dispose() }
        }
    }

    @Test fun blockCursorOverridesFaintAndExplicitDecorationColorsEvenWithoutMinimumContrast() {
        for (text in listOf("A", "界", "█")) {
            val session = TerminalSession(4, 2, theme = TerminalTheme(foreground = 0x101010, background = 0x123456))
            val view = TerminalViewport(session, {})
            val frame = TerminalGpuFrame()
            try {
                session.faintOpacity = 0.0
                session.write("\u001b[2;4;53;9;58;2;255;0;0m$text\u001b[H\u001b[2 q")
                frame.update(view, 8.0, 16.0)
                assertEquals(TerminalCellPosition(0, 0), frame.contrastCursor)
                assertTrue(frame.rows[0].decorations.isNotEmpty())
                assertTrue(frame.rows[0].decorations.all { it.opacity == 1.0 && it.color == 0x123456 })
                val atlas = TerminalGlyphAtlas(32)
                atlas.prepare(frame.keys, { GlyphBitmap(8, 16, ByteArray(8 * 16 * 4), false) }, {}, { _, _ -> })
                val batches = TerminalGpuBatches(); batches.build(frame, atlas)
                for (page in batches.pages) for (i in 0 until page.count) assertEquals(1f, page.data[i * 12 + 11])
                session.write("\u001b[6 q"); frame.update(view, 8.0, 16.0)
                assertNull(frame.contrastCursor)
                assertTrue(frame.rows[0].decorations.dropLast(1).all { it.opacity == 0.0 })
                batches.build(frame, atlas)
                for (page in batches.pages) for (i in 0 until page.count) assertEquals(0f, page.data[i * 12 + 11])
            } finally { view.dispose() }
        }
    }

    @Test fun decorationsUseContrastButGraphicsKeepOriginalFaintColor() {
        val session = TerminalSession(4, 1, theme = TerminalTheme(foreground = 0x101010, background = 0))
        val view = TerminalViewport(session, {})
        try {
            session.minimumContrast = 7.0
            session.write("\u001b[?25l\u001b[2;4;53;9;58;2;16;16;16m█")
            val frame = TerminalGpuFrame(); frame.update(view, 8.0, 16.0)
            val rects = frame.rows[0].decorations
            assertTrue(rects.any { it.width == 8.0 && it.height == 16.0 && it.color == 0x101010 && it.opacity == 128.0 / 255 })
            assertEquals(3, rects.count { it.height == 1.0 && it.color == 0xffffff && it.opacity == 1.0 })
        } finally { view.dispose() }
    }

    @Test fun bitmapDrawRangeSkipsRetainedCleanRows() {
        val session = TerminalSession(4, 3)
        val view = TerminalViewport(session, {})
        try {
            session.write("\u001b[?25lA\r\nB\r\nC")
            val frame = TerminalGpuFrame(); frame.update(view, 8.0, 16.0)
            val atlas = TerminalGlyphAtlas(64)
            atlas.prepare(frame.keys, { GlyphBitmap(8, 16, ByteArray(8 * 16 * 4), false) }, {}, { _, _ -> })
            val ys = mutableListOf<Double>()
            drawTerminalGlyphs(frame, atlas, 1.0, firstRow = 1, lastRow = 1) { _, _, y, _, _, _, _, _, _, _, _ -> ys += y }
            assertEquals(listOf(16.0), ys)
        } finally { view.dispose() }
    }
}
