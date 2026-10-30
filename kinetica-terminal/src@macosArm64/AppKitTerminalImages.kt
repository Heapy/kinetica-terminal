@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.NSGraphicsContext
import platform.CoreFoundation.*
import platform.CoreGraphics.*

/** Core Graphics images retain their CFData; pixels are written directly into its owned buffer. */
internal class AppKitTerminalImages {
    private val cache = TerminalImageCache<CGImageRef>(64L * 1024 * 1024, release = ::CGImageRelease)
    val bytes: Long get() = cache.bytes
    val tileCount: Int get() = cache.size
    var uploads: Int = 0
        private set
    fun beginFrame() = cache.beginFrame()
    fun endFrame() = cache.endFrame()
    fun clear() = cache.clear()

    fun draw(images: List<TerminalImageQuad>, layer: TerminalGraphicsLayer) {
        val context = checkNotNull(NSGraphicsContext.currentContext?.CGContext)
        for (image in images) if (image.layer == layer) for (draw in image.tiles()) {
            val bitmap = cache.get(draw.tile) {
                val tile = draw.tile
                val length = image.image.pixels.tileByteCount(tile).toLong()
                val data = checkNotNull(CFDataCreateMutable(null, length))
                try {
                    CFDataSetLength(data, length)
                    val bytes = checkNotNull(CFDataGetMutableBytePtr(data))
                    image.image.pixels.writeTile(tile, premultiplied = true) { index, value -> bytes[index] = value.toUByte() }
                    val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
                    try {
                        val colorSpace = checkNotNull(CGColorSpaceCreateDeviceRGB())
                        try {
                            checkNotNull(CGImageCreate(tile.width.toULong(), tile.height.toULong(), 8u, 32u,
                                (tile.width * 4).toULong(), colorSpace,
                                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
                                provider, null, true, CGColorRenderingIntent.kCGRenderingIntentDefault)).also { uploads++ }
                        } finally { CGColorSpaceRelease(colorSpace) }
                    } finally { CGDataProviderRelease(provider) }
                } finally { CFRelease(data) }
            }
            val q = draw.quad
            val factorX = q.width / q.sourceWidth; val factorY = q.height / q.sourceHeight
            val left = q.x - q.sourceX * factorX; val top = q.y - q.sourceY * factorY
            val width = draw.tile.width * factorX; val height = draw.tile.height * factorY
            CGContextSaveGState(context)
            try {
                // Adjacent tile clips share a fractional boundary. Antialiasing each clip
                // separately would blend the background into both halves of that pixel.
                CGContextSetShouldAntialias(context, false)
                CGContextClipToRect(context, CGRectMake(q.x, q.y, q.width, q.height))
                CGContextSetInterpolationQuality(context, kCGInterpolationLow)
                // The terminal context is flipped; CGImage drawing expects a bottom-left origin.
                CGContextTranslateCTM(context, left, top + height)
                CGContextScaleCTM(context, 1.0, -1.0)
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width, height), bitmap)
            } finally { CGContextRestoreGState(context) }
        }
    }
}
