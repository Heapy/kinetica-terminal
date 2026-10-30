@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Read once per retained face; do not repeatedly request a large system sbix table. */
internal fun terminalNativeFontHasSbix(font: CTFontRef): Boolean {
    val table = CTFontCopyTable(font, 0x73626978u, 0u) ?: return false
    return try { CFDataGetLength(table) > 0 } finally { CFRelease(table) }
}

/** Font size, baseline and returned bearings are device pixels, independent of the grid column. */
internal fun terminalRasterizeNativeGlyph(font: CTFontRef, glyph: Int, baseline: Double, maxSize: Int,
    constraint: TerminalGlyphConstraint = TerminalGlyphConstraint.NONE, metrics: TerminalGlyphMetrics? = null,
    columns: Int = 1, intrinsicColor: Boolean = terminalNativeFontHasSbix(font)): GlyphBitmap = memScoped {
    require(glyph in 0..65535 && baseline.isFinite() && maxSize > 0)
    val id = alloc<CGGlyphVar>(); id.value = glyph.toUShort()
    val bounds = CTFontGetBoundingRectsForGlyphs(font, kCTFontOrientationHorizontal, id.ptr, null, 1)
    var original: TerminalGlyphBounds? = null
    bounds.useContents {
        if (size.width < 0.25 || size.height < 0.25) return@memScoped GlyphBitmap(1, 1, ByteArray(4), false)
        check(origin.x.isFinite() && origin.y.isFinite() && size.width.isFinite() && size.height.isFinite())
        original = TerminalGlyphBounds(size.width, size.height, origin.x, origin.y)
    }
    val raw = checkNotNull(original)
    val adjusted = if (constraint.active) {
        val grid = checkNotNull(metrics)
        val bottomBaseline = grid.cellHeight - baseline.roundToInt()
        constraint.constrain(raw.copy(y = raw.y + bottomBaseline), grid, columns).let { it.copy(y = it.y - bottomBaseline) }
    } else raw
    val scaleX = adjusted.width / raw.width; val scaleY = adjusted.height / raw.height
    // A one-pixel gutter retains antialiasing at fractional outline bounds.
    val left = floor(adjusted.x).toInt() - 1; val bottom = floor(adjusted.y).toInt() - 1
    val right = ceil(adjusted.x + adjusted.width).toInt() + 1; val top = ceil(adjusted.y + adjusted.height).toInt() + 1
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
                CGContextTranslateCTM(context, adjusted.x - left - raw.x * scaleX, adjusted.y - bottom - raw.y * scaleY)
                CGContextScaleCTM(context, scaleX, scaleY)
                val position = alloc<CGPoint>(); position.x = 0.0; position.y = 0.0
                CTFontDrawGlyphs(font, id.ptr, position.ptr, 1u, context)
            } finally { CGContextRelease(context) }
        }
    } finally { CGColorSpaceRelease(colorSpace) }
    // Like Ghostty's CoreText ColorState, sbix is intrinsic color even when every
    // pixel happens to be gray. Pixel-channel equality cannot identify a text mask.
    glyphBitmap(width, height, bytes, premultiplied = true).let {
        it.copy(left = left, top = baseline.roundToInt() - top, colored = intrinsicColor || it.colored)
    }
}
