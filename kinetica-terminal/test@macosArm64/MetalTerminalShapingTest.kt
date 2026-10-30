@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import kotlin.test.*

class MetalTerminalShapingTest {
    @Test fun backgroundKeepsLigatureButStyleAndCursorReshapeIt() = withTerminalShapingFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(8, 1, theme = TerminalTheme(background = 0, foreground = 0xffffff, cursor = 0))
        session.write("\u001b[?25la===>b")
        val viewport = TerminalViewport(session, {})
        // Keep the actual font identical between styles to isolate the run boundary.
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ ->
            NSFontManager.sharedFontManager.convertFont(font, toSize = size)
        }) {}
        try {
            renderer.resize(80.0, 20.0, 2.0, 14.0, 10.0, 20.0)
            fun ink(): ByteArray = renderer.snapshot(viewport).let { pixels ->
                // Foreground is white; both backgrounds have zero red. This retains
                // exact antialiasing coverage while excluding background color changes.
                ByteArray(pixels.size / 4) { pixels[it * 4 + 2] }
            }
            val joined = ink()
            session.write("\u001b[1;3H\u001b[48;2;0;0;80m=\u001b[0m")
            assertContentEquals(joined, ink(), "Background changes preserve shaping context")
            session.write("\u001b[1;3H\u001b[1m=\u001b[0m")
            assertFalse(joined.contentEquals(ink()), "Style boundaries split the operator")
            session.write("\u001b[Ha===>b")
            assertContentEquals(joined, ink())
            session.write("\u001b[4 q\u001b[?25h\u001b[1;3H")
            assertFalse(joined.contentEquals(ink()), "The cursor splits the operator even without a block overlay")
            session.write("\u001b[?25l")
            assertContentEquals(joined, ink())
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun ligaturePixelsSplitAtSelectionAndRestoreWithoutGrowingAtlas() = withTerminalShapingFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(8, 2, theme = TerminalTheme(background = 0, foreground = 0xffffff, selection = 0))
        session.write("\u001b[?25la===>b")
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ ->
            NSFontManager.sharedFontManager.convertFont(font, toSize = size)
        }) {}
        try {
            renderer.resize(80.0, 40.0, 2.0, 14.0, 10.0, 20.0)
            val joined = renderer.snapshot(viewport)
            assertTrue(joined.indices.any { it % 4 != 3 && joined[it] != 0.toByte() }, "Ligatures produce visible ink")
            viewport.beginSelection(2, 0); viewport.endSelection(4, 0)
            val split = renderer.snapshot(viewport)
            assertFalse(joined.contentEquals(split), "A selection boundary must reshape the operator")
            viewport.clearSelection()
            assertContentEquals(joined, renderer.snapshot(viewport))
            val cached = renderer.rasterizations
            viewport.markAll(); assertContentEquals(joined, renderer.snapshot(viewport))
            assertEquals(cached, renderer.rasterizations)
            assertTrue(renderer.shapingFontCount > 0)
            renderer.setVisible(false)
            assertEquals(0, renderer.shapingFontCount)
            renderer.setVisible(true)
            assertContentEquals(joined, renderer.snapshot(viewport))
        } finally { renderer.dispose(); viewport.dispose() }
        assertEquals(0, renderer.shapingFontCount)
    }

    @Test fun longShapingRunsUseBoundedIndividualGlyphs() = withTerminalShapingFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(520, 1)
        session.write("\u001b[?25l" + "=".repeat(512) + ">")
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ ->
            NSFontManager.sharedFontManager.convertFont(font, toSize = size)
        }) {}
        try {
            renderer.resize(5200.0, 20.0, 1.0, 14.0, 10.0, 20.0)
            val pixels = renderer.snapshot(viewport)
            assertEquals(5200 * 20 * 4, pixels.size)
            assertTrue(renderer.glyphCount in 1..10, "Repeated long runs share individual atlas glyphs")
            assertEquals(1, renderer.glyphTextureCount)
        } finally { renderer.dispose(); viewport.dispose() }
    }
}
