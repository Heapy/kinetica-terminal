@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSFont
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import kotlin.math.roundToInt

/** Bounded software glyph cache. Intrinsic color is decided by native font metadata. */
internal class AppKitTerminalGlyphs(private val family: String?, private val size: Double,
    val scale: Double, private val cellWidth: Double, private val cellHeight: Double,
    private val fontProvider: (String?, Double, Int) -> NSFont = ::terminalNativeFont) {
    private val atlas = TerminalGlyphAtlas()
    private val images = mutableMapOf<AtlasGlyph, CGImageRef>()
    private val shapers = mutableMapOf<Int, TerminalNativeShaper>()
    private fun shaper(style: Int): TerminalNativeShaper = shapers.getOrPut(style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)) {
        TerminalNativeShaper(fontProvider(family, size * scale, style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)))
    }
    val frame: TerminalGpuFrame = TerminalGpuFrame({ text, style -> shaper(style).resolve(text) }) { run ->
        val current = shaper(run.style)
        current.shape(run.input, run.font).map { if (current.isColorGlyph(it.font, it.glyph)) it.copy(emoji = true) else it }
    }
    private fun clearImages() { images.values.forEach(::CGImageRelease); images.clear() }
    fun prepare(viewport: TerminalViewport) {
        frame.update(viewport, cellWidth, cellHeight)
        val font = fontProvider(family, size * scale, 0)
        val baseline = (cellHeight * scale + font.ascender + font.descender) / 2.0
        val faceHeight = font.ascender - font.descender + font.leading
        val metrics = TerminalGlyphMetrics(cellWidth * scale, cellHeight * scale, cellWidth * scale, faceHeight,
            cellHeight * scale - baseline.roundToInt() - (font.leading / 2 - font.descender),
            faceHeight, (2 * font.capHeight + faceHeight) / 3)
        atlas.prepare(frame.keys, { key ->
            val current = shaper(key.style)
            terminalRasterizeNativeGlyph(current.face(key.font), key.glyph, baseline, atlas.size - 2,
                key.constraint, metrics, key.columns, intrinsicColor = current.isColorGlyph(key.font, key.glyph))
        }, ::clearImages) { entry, bitmap ->
            val data = bitmap.rgba.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bitmap.rgba.size.toLong()) }
            checkNotNull(data)
            try {
                val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
                try {
                    val space = checkNotNull(CGColorSpaceCreateDeviceRGB())
                    try {
                        images[entry] = checkNotNull(CGImageCreate(bitmap.width.toULong(), bitmap.height.toULong(), 8u, 32u,
                            (bitmap.width * 4).toULong(), space,
                            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
                            provider, null, false, CGColorRenderingIntent.kCGRenderingIntentDefault))
                    } finally { CGColorSpaceRelease(space) }
                } finally { CGDataProviderRelease(provider) }
            } finally { CFRelease(data) }
        }
    }
    fun draw(firstRow: Int = 0, lastRow: Int = frame.rows.lastIndex) {
        val context = checkNotNull(NSGraphicsContext.currentContext?.CGContext)
        drawTerminalGlyphs(frame, atlas, scale, firstRow, lastRow) { entry, x, y, width, height, sx, sy, _, _, tint, opacity ->
            CGContextSaveGState(context)
            try {
                CGContextSetShouldAntialias(context, false)
                CGContextClipToRect(context, CGRectMake(x, y, width, height))
                CGContextSetInterpolationQuality(context, kCGInterpolationNone)
                CGContextSetAlpha(context, opacity)
                CGContextTranslateCTM(context, x - sx / scale, y - sy / scale + entry.height / scale)
                CGContextScaleCTM(context, 1.0, -1.0)
                val rect = CGRectMake(0.0, 0.0, entry.width / scale, entry.height / scale)
                val image = images.getValue(entry)
                if (entry.colored) CGContextDrawImage(context, rect, image) else {
                    CGContextClipToMask(context, rect, image)
                    CGContextSetRGBFillColor(context, (tint shr 16 and 255) / 255.0,
                        (tint shr 8 and 255) / 255.0, (tint and 255) / 255.0, 1.0)
                    CGContextFillRect(context, rect)
                }
            } finally { CGContextRestoreGState(context) }
        }
    }
    fun dispose() { clearImages(); atlas.clear(); shapers.values.forEach { it.dispose() }; shapers.clear() }
}
