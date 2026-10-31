@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import kotlin.math.abs
import kotlin.test.*

class AppKitTerminalGlyphsTest {
    @Test fun softwareBitmapOrientationColorAndContrastMatchMetalForGraySbixAndText() = withTerminalSbixFont { font ->
        NSApplication.sharedApplication()
        for (scale in listOf(1.0, 1.25, 2.0)) {
            val session = TerminalSession(8, 1, theme = TerminalTheme(background = 0, foreground = 0x101010))
            val view = TerminalViewport(session, {})
            val provider: (String?, Double, Int) -> NSFont = { _, size, _ -> NSFontManager.sharedFontManager.convertFont(font, toSize = size) }
            val software = AppKitTerminalGlyphs(null, 20.0, scale, 12.0, 24.0, provider)
            val metal = MetalTerminalDrawing(fontProvider = provider) {}
            val width = (96 * scale).toInt(); val height = (24 * scale).toInt()
            try {
                metal.resize(96.0, 24.0, scale, 20.0, 12.0, 24.0)
                session.write("\u001b[?25lABDZ") // A/B/D use gray intrinsic sbix artwork; Z uses a monochrome fallback face.
                fun softwarePixels(): ByteArray {
                    val bytes = ByteArray(width * height * 4)
                    val space = checkNotNull(CGColorSpaceCreateDeviceRGB())
                    try {
                        bytes.usePinned { pinned ->
                            val context = checkNotNull(CGBitmapContextCreate(pinned.addressOf(0), width.toULong(), height.toULong(), 8u,
                                (width * 4).toULong(), space, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                            NSGraphicsContext.saveGraphicsState()
                            try {
                                CGContextSetRGBFillColor(context, 0.0, 0.0, 0.0, 1.0)
                                CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
                                CGContextTranslateCTM(context, 0.0, height.toDouble()); CGContextScaleCTM(context, scale, -scale)
                                NSGraphicsContext.setCurrentContext(NSGraphicsContext.graphicsContextWithCGContext(context, true))
                                view.markAll(); software.prepare(view); software.draw()
                            } finally { NSGraphicsContext.restoreGraphicsState(); CGContextRelease(context) }
                        }
                    } finally { CGColorSpaceRelease(space) }
                    return bytes
                }
                session.minimumContrast = 1.0
                val original = softwarePixels()
                session.minimumContrast = 7.0
                val corrected = softwarePixels()
                var grayPixels = 0; var changed = 0
                for (i in original.indices step 4) {
                    val x = (i / 4) % width
                    if (x < (36 * scale).toInt()) {
                        assertEquals(original[i], corrected[i], "Intrinsic gray bitmap changed")
                        if ((corrected[i].toInt() and 255) in 32..224) grayPixels++
                    } else if (original[i] != corrected[i]) changed++
                }
                assertTrue(grayPixels > 10, "Gray fixture pixels=$grayPixels scale=$scale")
                assertTrue(changed > 10, "Corrected fallback pixels=$changed scale=$scale")
                for (faint in listOf(false, true)) {
                    session.write("\u001b[H\u001b[${if (faint) 2 else 22}mABDZ")
                    val actual = softwarePixels()
                    if (faint) for (i in actual.indices step 4) {
                        val x = (i / 4) % width
                        if (x < (36 * scale).toInt()) for (channel in 0..3)
                            assertEquals(corrected[i + channel], actual[i + channel], "Faint changed intrinsic sbix pixels")
                    }
                    view.markAll(); val expected = metal.snapshot(view)
                    var worst = 0
                    for (i in actual.indices step 4) for (c in 0..3) {
                        val other = expected[i + if (c < 3) 2 - c else 3].toInt() and 255
                        worst = maxOf(worst, abs((actual[i + c].toInt() and 255) - other))
                    }
                    assertTrue(worst <= 2, "AppKit/Metal pixel error=$worst scale=$scale faint=$faint")
                }
            } finally { software.dispose(); metal.dispose(); view.dispose() }
        }
    }
}
