@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import kotlin.test.*

class TerminalSvgTest {
    @Test fun monochromeGlyphInSvgFontKeepsItsOriginalSize() = withRenderer { session, renderer, viewport ->
        session.write("\u001b[?25lB")
        val pixels = renderer.snapshot(viewport)
        assertEquals(16, pixels.indices.step(4).count { pixels[it + 2] != 0.toByte() },
            "The ordinary 4x4 B outline must not acquire emoji cover sizing")
    }

    @Test fun graySvgKeepsItsColorWhenForegroundChanges() = withRenderer { session, renderer, viewport ->
        session.write("\u001b[?25lA")
        val first = renderer.snapshot(viewport)
        var ink = 0
        for (i in first.indices step 4) {
            val b = first[i].toInt() and 255; val g = first[i + 1].toInt() and 255; val r = first[i + 2].toInt() and 255
            if (maxOf(r, g, b) > 40) { ink++; assertTrue(maxOf(r, g, b) - minOf(r, g, b) <= 1, "SVG gray is intrinsic color") }
        }
        assertTrue(ink > 20)
        session.write("\u001b[H\u001b[38;2;0;0;255mA")
        assertContentEquals(first, renderer.snapshot(viewport))
    }

    @Test fun compressedSvgGradientReachesMetalInColor() = withRenderer { session, renderer, viewport ->
        session.write("\u001b[?25lC")
        val pixels = renderer.snapshot(viewport)
        assertTrue(pixels.indices.step(4).any { (pixels[it].toInt() and 255) > 50 }, "Gradient blue must reach the surface")
        assertTrue(pixels.indices.step(4).any { (pixels[it + 2].toInt() and 255) > 100 }, "Gradient red must reach the surface")
    }

    private fun withRenderer(block: (TerminalSession, MetalTerminalDrawing, TerminalViewport) -> Unit) = withTerminalSvgFont { font ->
        NSApplication.sharedApplication()
        val session = TerminalSession(4, 1, theme = TerminalTheme(background = 0, foreground = 0xff0000))
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing(fontProvider = { _, size, _ -> NSFontManager.sharedFontManager.convertFont(font, toSize = size) }) {}
        try { renderer.resize(48.0, 26.0, 1.0, 20.0, 12.0, 26.0); block(session, renderer, viewport) }
        finally { renderer.dispose(); viewport.dispose() }
    }
}
