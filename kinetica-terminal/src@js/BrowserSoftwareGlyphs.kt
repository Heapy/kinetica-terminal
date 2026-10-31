package io.heapy.kinetica.terminal

import kotlin.math.ceil

/** The system-font path has no font tables. Probe paint response, including gray color artwork,
 * instead of guessing color glyphs from their Unicode codepoint or chromatic pixels alone. */
internal fun browserRasterizeGlyph(key: GlyphKey, raster: dynamic, context: dynamic,
    cellWidth: Double, cellHeight: Double, ratio: Double, size: Double, family: String?): GlyphBitmap {
    val width = ceil(cellWidth * key.columns * ratio).toInt().coerceAtLeast(1)
    val height = ceil(cellHeight * ratio).toInt().coerceAtLeast(1)
    raster.width = width; raster.height = height
    context.setTransform(ratio, 0, 0, ratio, 0, 0)
    context.font = browserFont(size, key.style, family); context.textBaseline = "top"
    context.fillStyle = "white"; context.fillText(key.text, 0, 1)
    val white: dynamic = context.getImageData(0, 0, width, height).data
    val bytes = ByteArray(width * height * 4) { (white[it] as Number).toByte() }
    context.clearRect(0, 0, width / ratio, height / ratio)
    context.fillStyle = "black"; context.fillText(key.text, 0, 1)
    val black: dynamic = context.getImageData(0, 0, width, height).data
    var intrinsic = false
    for (i in bytes.indices step 4) {
        // Low-coverage pixels can have large unpremultiplication rounding errors.
        // Compare premultiplied channels within one byte instead.
        val a = bytes[i + 3].toInt() and 255
        val b = (black[i + 3] as Number).toInt()
        for (c in 0..2) {
            val w = bytes[i + c].toInt() and 255
            val k = (black[i + c] as Number).toInt()
            if ((255 - w) * a > 255 || k * b > 255) intrinsic = true
        }
    }
    return glyphBitmap(width, height, bytes).let { it.copy(colored = intrinsic || it.colored) }
}

/** Software glyph bitmaps share the same bounded atlas accounting and clipping as WebGL. */
internal class BrowserSoftwareGlyphs(fonts: BrowserTerminalFontSet?, private val family: String?,
    private val size: Double, private val ratio: Double, private val cellWidth: Double, private val cellHeight: Double) {
    private val shaper = fonts?.createShaper(family, size * ratio)
    val frame: TerminalGpuFrame = shaper?.let { TerminalGpuFrame(it::resolve, it::shape) } ?: TerminalGpuFrame()
    val shaping: Boolean get() = shaper != null
    private val atlas = TerminalGlyphAtlas()
    private val bitmaps = mutableMapOf<AtlasGlyph, dynamic>()
    private val raster: dynamic = js("globalThis.document.createElement('canvas')")
    private val rasterContext: dynamic = raster.getContext("2d", js("({willReadFrequently:true})"))
    private val scratch: dynamic = js("globalThis.document.createElement('canvas')")
    private val scratchContext: dynamic = scratch.getContext("2d")
    private fun clearBitmaps() { bitmaps.values.forEach { it.width = 0; it.height = 0 }; bitmaps.clear() }
    fun prepare(viewport: TerminalViewport) {
        frame.update(viewport, cellWidth, cellHeight)
        atlas.prepare(frame.keys, { key ->
            if (key.font >= 0) checkNotNull(shaper).rasterize(key, raster, rasterContext, cellWidth * ratio, cellHeight * ratio, atlas.size - 2)
            else browserRasterizeGlyph(key, raster, rasterContext, cellWidth, cellHeight, ratio, size, family)
        }, ::clearBitmaps) { entry, bitmap ->
            val canvas: dynamic = js("globalThis.document.createElement('canvas')")
            canvas.width = bitmap.width; canvas.height = bitmap.height
            val context = canvas.getContext("2d")
            val data = context.createImageData(bitmap.width, bitmap.height)
            for (i in bitmap.rgba.indices step 4) {
                val alpha = bitmap.rgba[i + 3].toInt() and 255
                for (c in 0..2) data.data[i + c] = if (alpha == 0) 0 else
                    (((bitmap.rgba[i + c].toInt() and 255) * 255 + alpha / 2) / alpha).coerceAtMost(255)
                data.data[i + 3] = alpha
            }
            context.putImageData(data, 0, 0); bitmaps[entry] = canvas
        }
    }
    fun draw(context: dynamic, firstRow: Int = 0, lastRow: Int = frame.rows.lastIndex) {
        val previous = context.globalAlpha
        try {
            context.imageSmoothingEnabled = false
            drawTerminalGlyphs(frame, atlas, ratio, firstRow, lastRow) { entry, x, y, width, height, sx, sy, sw, sh, tint, opacity ->
                val bitmap = bitmaps.getValue(entry)
                val source = if (entry.colored) bitmap else {
                    scratch.width = entry.width; scratch.height = entry.height
                    scratchContext.drawImage(bitmap, 0, 0)
                    scratchContext.globalCompositeOperation = "source-in"
                    scratchContext.fillStyle = terminalCssColor(tint)
                    scratchContext.fillRect(0, 0, entry.width, entry.height)
                    scratchContext.globalCompositeOperation = "source-over"
                    scratch
                }
                context.globalAlpha = opacity
                context.drawImage(source, sx, sy, sw, sh, x, y, width, height)
            }
        } finally { context.globalAlpha = previous; context.imageSmoothingEnabled = true }
    }
    fun dispose() {
        clearBitmaps(); atlas.clear(); shaper?.dispose()
        raster.width = 0; raster.height = 0; scratch.width = 0; scratch.height = 0
    }
}
