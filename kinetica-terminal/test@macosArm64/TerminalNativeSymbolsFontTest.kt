@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.CoreText.*
import kotlin.math.abs
import kotlin.test.*

class TerminalNativeSymbolsFontTest {
    private val icons = listOf("\ue0a0", "\ue0b0", "\uf09b", "\ue73c", "\ue7a8", "\uf179", codePointString(0xf0001))

    @Test fun builtInIconsResolveWithoutSystemInstallationAtEveryStyleAndScale() {
        val installedBefore = NSFontManager.sharedFontManager.availableFonts.contains("SymbolsNF")
        for (size in listOf(14.0, 17.5, 28.0)) for (style in listOf(0, TerminalStyle.BOLD, TerminalStyle.ITALIC,
            TerminalStyle.BOLD or TerminalStyle.ITALIC)) {
            val primary = terminalNativeFont(null, size, style)
            val shaper = TerminalNativeShaper(primary)
            try {
                for (text in icons) {
                    val resolved = shaper.resolve(text)
                    assertEquals(text, resolved.text, "Icon must not become a replacement character")
                    val font = interpretObjCPointer<NSFont>(shaper.face(resolved.font).rawValue)
                    assertEquals("SymbolsNF", font.fontName)
                    assertEquals(size, font.pointSize)
                    val glyph = shaper.shape(TerminalShapingText(text, IntArray(text.length), 1), resolved.font).single()
                    assertEquals(resolved.font, glyph.font)
                    assertNotEquals(0, glyph.glyph)
                    assertFalse(shaper.isColorGlyph(glyph.font, glyph.glyph), "Icons use the terminal foreground")
                    val bitmap = terminalRasterizeNativeGlyph(shaper.face(glyph.font), glyph.glyph, size * 1.5, 1022)
                    assertTrue(bitmap.rgba.indices.any { it % 4 == 3 && bitmap.rgba[it] != 0.toByte() })
                }
                val text = shaper.resolve("A")
                assertTrue(interpretObjCPointer<NSFont>(shaper.face(text.font).rawValue).isEqual(primary))
                val emoji = shaper.resolve("👍🏽")
                assertEquals("👍🏽", emoji.text)
                val glyph = shaper.shape(TerminalShapingText(emoji.text, IntArray(emoji.text.length), 2), emoji.font).single()
                assertTrue(shaper.isColorGlyph(glyph.font, glyph.glyph))
                assertEquals("�", shaper.resolve(codePointString(0x10fffd)).text)
                assertEquals("�", shaper.resolve("\uf09b" + codePointString(0x10fffd)).text)
                val count = shaper.fontCount
                repeat(3) { for (icon in icons) shaper.resolve(icon) }
                assertEquals(count, shaper.fontCount)
            } finally { shaper.dispose() }
            assertEquals(0, shaper.fontCount)
        }
        assertEquals(installedBefore, NSFontManager.sharedFontManager.availableFonts.contains("SymbolsNF"),
            "Bundled fallback must not register a font in the system")
    }

    @Test fun softwareAndMetalRenderDistinctIconsWithForegroundColor() {
        NSApplication.sharedApplication()
        for (scale in listOf(1.0, 1.25, 2.0)) {
            val columns = icons.size * 3 + 1
            val session = TerminalSession(columns, 4, theme = TerminalTheme(background = 0, foreground = 0x40c080))
            val viewport = TerminalViewport(session, {})
            val software = AppKitTerminalGlyphs(null, 14.0, scale, 10.0, 24.0)
            val metal = MetalTerminalDrawing {}
            val width = (columns * 10 * scale).toInt(); val height = (96 * scale).toInt()
            try {
                session.write("\u001b[?25l")
                for ((row, style) in listOf("0", "1", "3", "1;3").withIndex()) {
                    session.write("\u001b[${row + 1};1H\u001b[0;${style}m" + icons.joinToString("  "))
                }
                val pixels = ByteArray(width * height * 4)
                val space = checkNotNull(CGColorSpaceCreateDeviceRGB())
                try {
                    pixels.usePinned { pinned ->
                        val context = checkNotNull(CGBitmapContextCreate(pinned.addressOf(0), width.toULong(), height.toULong(),
                            8u, (width * 4).toULong(), space, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                        NSGraphicsContext.saveGraphicsState()
                        try {
                            CGContextSetRGBFillColor(context, 0.0, 0.0, 0.0, 1.0)
                            CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
                            CGContextTranslateCTM(context, 0.0, height.toDouble()); CGContextScaleCTM(context, scale, -scale)
                            NSGraphicsContext.setCurrentContext(NSGraphicsContext.graphicsContextWithCGContext(context, true))
                            software.prepare(viewport); software.draw()
                        } finally { NSGraphicsContext.restoreGraphicsState(); CGContextRelease(context) }
                    }
                } finally { CGColorSpaceRelease(space) }
                metal.resize(columns * 10.0, 96.0, scale, 14.0, 10.0, 24.0)
                viewport.markAll()
                val gpu = metal.snapshot(viewport)
                var worst = 0
                for (i in pixels.indices step 4) for (channel in 0..3) {
                    worst = maxOf(worst, abs((pixels[i + channel].toInt() and 255) -
                        (gpu[i + if (channel < 3) 2 - channel else 3].toInt() and 255)))
                }
                assertTrue(worst <= 2, "AppKit/Metal pixel error=$worst scale=$scale")
                for (row in 0..3) {
                    val artwork = mutableSetOf<List<Byte>>()
                    for (column in icons.indices) {
                        val left = (column * 30 * scale).toInt(); val right = ((column * 30 + 20) * scale).toInt()
                        val top = (row * 24 * scale).toInt(); val bottom = ((row + 1) * 24 * scale).toInt()
                        val glyph = mutableListOf<Byte>()
                        var ink = 0
                        for (y in top until bottom) for (x in left until right) {
                            val offset = (y * width + x) * 4
                            val red = pixels[offset].toInt() and 255
                            val green = pixels[offset + 1].toInt() and 255
                            if (green > 20) { ink++; assertTrue(green > red, "Icon must use the green foreground") }
                            glyph += pixels[offset + 1]
                        }
                        assertTrue(ink > 5, "Missing icon=$column row=$row scale=$scale")
                        artwork += glyph
                    }
                    assertEquals(icons.size, artwork.size, "Different icons must not become identical replacement boxes")
                }
            } finally { software.dispose(); metal.dispose(); viewport.dispose() }
        }
    }
}
