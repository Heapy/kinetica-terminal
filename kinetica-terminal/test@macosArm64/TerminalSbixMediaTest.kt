@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import kotlin.test.*

class TerminalSbixMediaTest {
    @Test fun jpegTiffAndDupeKeepAuthoredColorsAtMultipleSizes() {
        for (size in listOf(20.0, 30.0, 40.0)) withTerminalSbixMediaFont(size) { font ->
            val shaper = TerminalNativeShaper(font)
            try {
                val bitmaps = "ABCD".map { character ->
                    val glyph = shaper.shape(TerminalShapingText(character.toString(), intArrayOf(0), 1)).single()
                    assertTrue(shaper.isColorGlyph(glyph.font, glyph.glyph))
                    terminalRasterizeNativeGlyph(shaper.face(glyph.font), glyph.glyph, 40.0, 1022)
                }
                val gray = if (size == 20.0) 96 else 176
                for ((index, bitmap) in bitmaps.withIndex()) {
                    assertTrue(bitmap.colored)
                    assertEquals((size * 0.7).toInt() + 2, bitmap.height)
                    val center = ((bitmap.height / 2) * bitmap.width + bitmap.width / 2) * 4
                    val expected = if (index == 1) listOf(32, 64, 240, 255) else listOf(gray, gray, gray, 255)
                    assertEquals(expected, (0..3).map { bitmap.rgba[center + it].toInt() and 255 }, "Glyph $index at $size")
                }
                assertContentEquals(bitmaps[0].rgba, bitmaps[2].rgba, "dupe preserves JPEG pixels")
                assertTrue(bitmaps[3].left < bitmaps[0].left, "Negative TIFF offset is preserved")
            } finally { shaper.dispose() }
        }
    }

    @Test fun jpegAndTiffReachMetalWithoutForegroundTinting() = withTerminalSbixMediaFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(4, 1, theme = TerminalTheme(background = 0, foreground = 0xff0000))
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ -> NSFontManager.sharedFontManager.convertFont(font, toSize = size) }) {}
        try {
            renderer.resize(48.0, 26.0, 1.0, 20.0, 12.0, 26.0)
            session.write("\u001b[?25lABCD")
            val first = renderer.snapshot(viewport)
            assertTrue(first.indices.step(4).count {
                (first[it].toInt() and 255) > 200 && (first[it + 2].toInt() and 255) < 60
            } > 20, "TIFF blue reaches the surface")
            assertTrue(first.indices.step(4).count {
                // Emoji fitting can select the larger, lighter authored strike.
                (first[it].toInt() and 255) in listOf(96, 176) && first[it] == first[it + 1] && first[it] == first[it + 2]
            } > 20, "Gray bitmap artwork reaches the surface")
            session.write("\u001b[H\u001b[38;2;0;255;0mABCD")
            assertContentEquals(first, renderer.snapshot(viewport))
        } finally { renderer.dispose(); viewport.dispose() }
    }
}
