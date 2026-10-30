@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import kotlin.test.*

class TerminalNativeGlyphTest {
    @Test fun fittedAtlasGlyphMatchesDirectCoreTextTransform() = withTerminalShapingFont(28.0) { font ->
        val shaper = TerminalNativeShaper(font)
        try {
            val glyph = shaper.shape(TerminalShapingText("F", intArrayOf(0), 1)).single()
            val face = shaper.face(glyph.font)
            val raw = memScoped {
                val id = alloc<CGGlyphVar>(); id.value = glyph.glyph.toUShort()
                CTFontGetBoundingRectsForGlyphs(face, kCTFontOrientationHorizontal, id.ptr, null, 1).useContents {
                    TerminalGlyphBounds(size.width, size.height, origin.x, origin.y)
                }
            }
            val metrics = TerminalGlyphMetrics(16.0, 48.0, 16.0, 48.0, 0.0, 48.0, 32.0)
            val bitmap = terminalRasterizeNativeGlyph(face, glyph.glyph, 36.0, 1022,
                TerminalGlyphConstraint(size = TerminalGlyphSizing.STRETCH), metrics, 1)
            assertEquals(-1, bitmap.left)
            assertEquals(-1, bitmap.top)
            assertEquals(18, bitmap.width)
            assertEquals(50, bitmap.height)
            val actual = ByteArray(64 * 64 * 4)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val px = 8 + bitmap.left + x; val py = bitmap.top + y
                if (px in 0..63 && py in 0..63) bitmap.rgba.copyInto(actual, (py * 64 + px) * 4,
                    (y * bitmap.width + x) * 4, (y * bitmap.width + x + 1) * 4)
            }
            // Independently stretch the actual CoreText outline into the 16x48 grid cell.
            val sx = 16.0 / raw.width; val sy = 48.0 / raw.height
            val expected = directLinePixels("F", font, 8.0 - raw.x * sx, 16.0 - raw.y * sy, sx, sy)
            assertContentEquals(expected, actual)
        } finally { shaper.dispose() }
    }

    @Test fun tightAtlasGlyphReconstructsDirectCoreTextLinePixels() = withTerminalShapingFont(28.0) { font ->
        val shaper = TerminalNativeShaper(font)
        try {
            for (text in listOf("F", "g", "é")) {
                val glyph = shaper.shape(TerminalShapingText(text, intArrayOf(0), 1)).single()
                val bitmap = terminalRasterizeNativeGlyph(shaper.face(glyph.font), glyph.glyph, 36.0, 1022)
                val actual = ByteArray(64 * 64 * 4)
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val destinationX = 8 + bitmap.left + x
                    val destinationY = bitmap.top + y
                    if (destinationX in 0..63 && destinationY in 0..63) {
                        bitmap.rgba.copyInto(actual, (destinationY * 64 + destinationX) * 4,
                            (y * bitmap.width + x) * 4, (y * bitmap.width + x + 1) * 4)
                    }
                }
                val expected = directLinePixels(text, font)
                assertTrue(expected.indices.any { it % 4 == 3 && expected[it] != 0.toByte() })
                val difference = expected.indices.firstOrNull { expected[it] != actual[it] }
                assertEquals(null, difference, "Bounds, bearings and orientation must preserve $text; differing byte=$difference")
            }
        } finally { shaper.dispose() }
    }

    private fun directLinePixels(text: String, font: NSFont, x: Double = 8.0, y: Double = 28.0,
        scaleX: Double = 1.0, scaleY: Double = 1.0): ByteArray {
        val bytes = ByteArray(64 * 64 * 4)
        val space = checkNotNull(CGColorSpaceCreateDeviceRGB())
        try {
            bytes.usePinned { pinned ->
                val context = checkNotNull(CGBitmapContextCreate(pinned.addressOf(0), 64u, 64u, 8u, 256u,
                    space, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                try {
                    CGContextSetAllowsAntialiasing(context, true); CGContextSetShouldAntialias(context, true)
                    CGContextSetShouldSmoothFonts(context, false)
                    CGContextSetAllowsFontSubpixelPositioning(context, true); CGContextSetShouldSubpixelPositionFonts(context, true)
                    CGContextSetAllowsFontSubpixelQuantization(context, false); CGContextSetShouldSubpixelQuantizeFonts(context, false)
                    CGContextTranslateCTM(context, x, y)
                    CGContextScaleCTM(context, scaleX, scaleY)
                    CGContextSetTextPosition(context, 0.0, 0.0)
                    val attributed = checkNotNull(CFBridgingRetain(NSAttributedString.create(string = text,
                        attributes = mapOf(NSFontAttributeName to font, NSForegroundColorAttributeName to NSColor.whiteColor))))
                    try {
                        val line = checkNotNull(CTLineCreateWithAttributedString(attributed.reinterpret()))
                        try { CTLineDraw(line, context) } finally { CFRelease(line) }
                    } finally { CFRelease(attributed) }
                } finally { CGContextRelease(context) }
            }
        } finally { CGColorSpaceRelease(space) }
        return bytes
    }
}
