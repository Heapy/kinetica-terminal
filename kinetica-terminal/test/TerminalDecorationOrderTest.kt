package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalDecorationOrderTest {
    @Test fun patternedUnderlineSegmentsDoNotPaintTheSamePhysicalPixelTwice() {
        for (width in listOf(7.5, 8.4, 8.5)) for (ratio in listOf(1.0, 1.25, 2.0)) for (style in 3..5) {
            val session = TerminalSession(8, 1)
            val viewport = TerminalViewport(session, {})
            val hits = mutableMapOf<Pair<Int, Int>, Int>()
            try {
                session.write("\u001b[?25l\u001b[4:${style}m        ")
                viewport.paint(object : TerminalPainter {
                    override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
                    override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {}
                    override fun underlay(x: Double, y: Double, width: Double, height: Double, color: Int) {
                        val left = kotlin.math.round(x * ratio).toInt()
                        val top = kotlin.math.round(y * ratio).toInt()
                        val w = kotlin.math.round(terminalPixelSpan(x, width, ratio) * ratio).toInt()
                        val h = kotlin.math.round(terminalPixelSpan(y, height, ratio) * ratio).toInt()
                        for (px in left until left + w) for (py in top until top + h) {
                            val point = px to py
                            hits[point] = (hits[point] ?: 0) + 1
                        }
                    }
                }, width, 19.0)
                assertTrue(hits.isNotEmpty())
                assertTrue(hits.values.all { it == 1 }, "Double compositing at width=$width, DPR=$ratio, style=$style: ${hits.filterValues { it > 1 }}")
            } finally { viewport.dispose() }
        }
    }

    @Test fun thinStrokesSurviveFractionalEndpointsAtOddCellHeights() {
        for (height in listOf(15.0, 17.0, 19.0, 21.0)) for (ratio in listOf(1.0, 1.25, 2.0)) {
            val session = TerminalSession(1, 1)
            val viewport = TerminalViewport(session, {})
            try {
                session.write("\u001b[?25l\u001b[9m ")
                val frame = TerminalGpuFrame()
                frame.update(viewport, 8.0, height)
                val batches = TerminalGpuBatches()
                batches.build(frame, TerminalGlyphAtlas(), ratio)
                assertEquals(1, batches.decorations.count)
                assertTrue(batches.decorations.data[3] * ratio >= 0.999999,
                    "Nonempty strike must cover a physical pixel: height=$height, DPR=$ratio")
            } finally { viewport.dispose() }
        }
        assertEquals(0.0, terminalPixelSpan(9.5, 0.0, 1.0))
        assertEquals(0.0, terminalPixelSpan(8.0, 0.4, 1.0), "A cell-edge fragment must not overlap the next cell")
    }

    @Test fun patternedUnderlinesHaveTheSamePhaseAcrossTextRunsWideGlyphsAndBlocks() {
        fun lines(text: String, style: Int): List<TerminalRect> {
            val session = TerminalSession(8, 1)
            val viewport = TerminalViewport(session, {})
            val result = mutableListOf<TerminalRect>()
            try {
                session.write("\u001b[?25l\u001b[4:${style}m$text")
                viewport.paint(object : TerminalPainter {
                    override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
                    override fun underlay(x: Double, y: Double, width: Double, height: Double, color: Int) {
                        result += TerminalRect(x, y, width, height, color)
                    }
                    override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {}
                }, 8.4, 19.0)
                return result
            } finally { viewport.dispose() }
        }
        for (style in 3..5) {
            val expected = lines("        ", style)
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, lines("abcdefgh", style))
            assertEquals(expected, lines("ab😀██cd", style))
        }
    }

    @Test fun immediatePaintersDrawUnderlineAndOverlineBeforeTextAndStrikeAfter() {
        val session = TerminalSession(1, 1)
        val viewport = TerminalViewport(session, {})
        val order = mutableListOf<Pair<String, Double?>>()
        try {
            session.write("\u001b[?25l\u001b[4;53;9mg")
            viewport.paint(object : TerminalPainter {
                override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {}
                override fun underlay(x: Double, y: Double, width: Double, height: Double, color: Int) { order += "under" to y }
                override fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) { order += "over" to y }
                override fun text(value: String, x: Double, y: Double, color: Int, style: Int) { order += "text:$value" to null }
            }, 8.0, 16.0)
            assertEquals(listOf("under" to 14.0, "under" to 0.0, "text:g" to null, "over" to 8.0), order)
        } finally { viewport.dispose() }
    }

    @Test fun retainedLayersKeepOpacityAndReleaseHeldDecorationsForShapedAndUnshapedText() {
        for (shaped in listOf(false, true)) {
            val session = TerminalSession(1, 1)
            val viewport = TerminalViewport(session, {})
            val frame = if (shaped) TerminalGpuFrame({ text, _ -> TerminalResolvedFontCell(0, text) }) {
                listOf(TerminalShapedGlyph(0, 42, 0, 0, 0))
            } else TerminalGpuFrame()
            try {
                session.write("\u001b[?25l\u001b[2;4;53;9;58;2;0;255;0mg")
                frame.update(viewport, 8.0, 16.0)
                val initial = frame.rows[0].decorations.toList()
                assertEquals(listOf(true, true, false), initial.map { it.behindText })
                assertTrue(initial.all { it.opacity == 128.0 / 255.0 })
                assertEquals(0x00ff00, initial.first().color)
                val atlas = TerminalGlyphAtlas(32)
                atlas.prepare(frame.keys, { GlyphBitmap(8, 16, ByteArray(8 * 16 * 4), false) }, {}, { _, _ -> })
                val batches = TerminalGpuBatches()
                batches.build(frame, atlas)
                assertEquals(2, batches.underlays.count)
                assertEquals(1, batches.decorations.count)
                session.write("\u001b[?2026h\u001b[H\u001b[0mg")
                viewport.markAll(); frame.update(viewport, 8.0, 16.0)
                assertEquals(initial, frame.rows[0].decorations)
                session.write("\u001b[?2026l")
                frame.update(viewport, 8.0, 16.0); batches.build(frame, atlas)
                assertEquals(0, batches.underlays.count)
                assertEquals(0, batches.decorations.count)
            } finally { viewport.dispose() }
        }
    }
}
