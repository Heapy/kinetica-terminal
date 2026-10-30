@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.CoreText.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Font size, baseline and returned bearings are device pixels, independent of the grid column. */
internal fun terminalRasterizeNativeGlyph(font: CTFontRef, glyph: Int, baseline: Double, maxSize: Int): GlyphBitmap = memScoped {
    require(glyph in 0..65535 && baseline.isFinite() && maxSize > 0)
    val id = alloc<CGGlyphVar>(); id.value = glyph.toUShort()
    val bounds = CTFontGetBoundingRectsForGlyphs(font, kCTFontOrientationHorizontal, id.ptr, null, 1)
    var left = 0; var bottom = 0; var right = 0; var top = 0
    bounds.useContents {
        if (size.width < 0.25 || size.height < 0.25) return@memScoped GlyphBitmap(1, 1, ByteArray(4), false)
        check(origin.x.isFinite() && origin.y.isFinite() && size.width.isFinite() && size.height.isFinite())
        // A one-pixel gutter retains antialiasing at fractional outline bounds.
        left = floor(origin.x).toInt() - 1
        bottom = floor(origin.y).toInt() - 1
        right = ceil(origin.x + size.width).toInt() + 1
        top = ceil(origin.y + size.height).toInt() + 1
    }
    val width = right - left; val height = top - bottom
    check(width in 1..maxSize && height in 1..maxSize) { "Native glyph exceeds the bounded atlas" }
    val bytes = ByteArray(width * height * 4)
    val colorSpace = checkNotNull(CGColorSpaceCreateDeviceRGB())
    try {
        bytes.usePinned { pinned ->
            val context = checkNotNull(CGBitmapContextCreate(pinned.addressOf(0), width.toULong(), height.toULong(), 8u,
                (width * 4).toULong(), colorSpace, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
            try {
                // The bitmap's first stored row is the top, while Quartz's
                // default user space and CoreText outlines both have +Y upwards.
                CGContextSetAllowsAntialiasing(context, true)
                CGContextSetShouldAntialias(context, true)
                CGContextSetShouldSmoothFonts(context, false)
                CGContextSetAllowsFontSubpixelPositioning(context, true)
                CGContextSetShouldSubpixelPositionFonts(context, true)
                CGContextSetAllowsFontSubpixelQuantization(context, false)
                CGContextSetShouldSubpixelQuantizeFonts(context, false)
                CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
                val position = alloc<CGPoint>(); position.x = -left.toDouble(); position.y = -bottom.toDouble()
                CTFontDrawGlyphs(font, id.ptr, position.ptr, 1u, context)
            } finally { CGContextRelease(context) }
        }
    } finally { CGColorSpaceRelease(colorSpace) }
    glyphBitmap(width, height, bytes, premultiplied = true).copy(left = left, top = baseline.roundToInt() - top)
}
