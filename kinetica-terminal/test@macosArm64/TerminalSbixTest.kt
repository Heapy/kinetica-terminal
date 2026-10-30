@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import kotlin.test.*

class TerminalSbixTest {
    @Test fun bitmapStrikesRetainGrayColorAtTheirAuthoredBounds() {
        for (size in listOf(20.0, 30.0, 40.0)) withTerminalSbixFont(size) { font ->
            val shaper = TerminalNativeShaper(font)
            try {
                val glyph = shaper.shape(TerminalShapingText("A", intArrayOf(0), 1)).single()
                val bitmap = terminalRasterizeNativeGlyph(shaper.face(glyph.font), glyph.glyph, 40.0, 1022)
                assertTrue(bitmap.colored)
                // At 30px the authored [1.5, 16.5] box spans 16 whole pixels,
                // plus the two antialiasing gutters.
                assertEquals(when (size) { 20.0 -> 12; 30.0 -> 18; else -> 22 }, bitmap.width)
                assertEquals((size * 0.7).toInt() + 2, bitmap.height)
                val center = ((bitmap.height / 2) * bitmap.width + bitmap.width / 2) * 4
                val gray = if (size == 20.0) 96 else 176
                assertEquals(gray, bitmap.rgba[center].toInt() and 255)
                assertEquals(gray, bitmap.rgba[center + 1].toInt() and 255)
                assertEquals(gray, bitmap.rgba[center + 2].toInt() and 255)
                assertEquals(255, bitmap.rgba[center + 3].toInt() and 255)
            } finally { shaper.dispose() }
        }
    }

    @Test fun graySbixArtworkIsNotTintedByTerminalForeground() = withTerminalSbixFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(4, 1, theme = TerminalTheme(background = 0, foreground = 0xff0000))
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ ->
            NSFontManager.sharedFontManager.convertFont(font, toSize = size)
        }) {}
        try {
            renderer.resize(48.0, 26.0, 1.0, 20.0, 12.0, 26.0)
            session.write("\u001b[?25lA")
            val red = renderer.snapshot(viewport)
            var ink = 0
            for (i in red.indices step 4) {
                val b = red[i].toInt() and 255; val g = red[i + 1].toInt() and 255; val r = red[i + 2].toInt() and 255
                if (maxOf(r, g, b) > 40) {
                    ink++
                    assertTrue(maxOf(r, g, b) - minOf(r, g, b) <= 1, "Gray sbix is intrinsic color, not a text mask")
                }
            }
            assertTrue(ink > 20, "The embedded gray PNG must actually reach Metal")
            session.write("\u001b[H\u001b[38;2;0;0;255mA")
            assertContentEquals(red, renderer.snapshot(viewport), "Changing SGR cannot tint the embedded PNG")
            session.write("\u001b[HC")
            assertContentEquals(red, renderer.snapshot(viewport), "sbix dupe glyph uses the referenced image")
        } finally { renderer.dispose(); viewport.dispose() }
    }
}
