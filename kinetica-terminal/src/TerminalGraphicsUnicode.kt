// Placeholder runs and geometry follow Ghostty kitty/graphics_unicode.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.math.roundToLong

internal const val TERMINAL_GRAPHICS_PLACEHOLDER = 0x10eeee

internal data class TerminalGraphicsUnicodeRun(
    val x: Int, val y: Int, val image: Long, val placement: Long,
    val row: Int, val col: Int, val width: Int,
)

/** IDs use the stored palette index/RGB bytes, never the current palette's resolved color. */
private fun graphicsColorId(color: Int): Long = when {
    color < 0 -> 0L
    color >= INDEXED_COLOR -> (color and 255).toLong()
    else -> color.toLong()
}

private fun graphicsDiacriticIndex(codepoint: Int): Int? {
    var low = 0; var high = terminalGraphicsDiacritics.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val value = terminalGraphicsDiacritics[middle]
        when {
            value < codepoint -> low = middle + 1
            value > codepoint -> high = middle - 1
            else -> return middle
        }
    }
    return null
}

internal fun terminalGraphicsUnicodeRuns(line: TerminalLine, y: Int): List<TerminalGraphicsUnicodeRun> {
    val result = mutableListOf<TerminalGraphicsUnicodeRun>()
    var run: TerminalGraphicsUnicodeRun? = null
    var high: Int? = null
    for (x in 0 until line.columns) {
        if (line.codePoint(x) != TERMINAL_GRAPHICS_PLACEHOLDER) {
            run?.let(result::add); run = null
            continue
        }
        val image = graphicsColorId(line.rawForeground(x))
        val placement = graphicsColorId(line.rawUnderlineColor(x))
        val text = line.text(x)
        var index = 2 // The placeholder itself is a supplementary codepoint.
        fun next(): Int? {
            if (index >= text.length) return null
            val first = text[index++].code
            val cp = if (first in 0xd800..0xdbff && index < text.length && text[index].code in 0xdc00..0xdfff)
                0x10000 + ((first - 0xd800) shl 10) + text[index++].code - 0xdc00 else first
            return graphicsDiacriticIndex(cp)
        }
        val row = next(); val col = next(); val upper = next()?.takeIf { it <= 255 }
        val previous = run
        if (previous != null && previous.image and 0xffffffL == image && previous.placement == placement &&
            (row == null || row == previous.row) && (col == null || col == previous.col + previous.width) &&
            (upper == null || upper == high)) {
            run = previous.copy(width = previous.width + 1)
        } else {
            previous?.let(result::add)
            high = upper
            run = TerminalGraphicsUnicodeRun(x, y, image or ((upper ?: 0).toLong() shl 24), placement, row ?: 0, col ?: 0, 1)
        }
    }
    run?.let(result::add)
    return result
}

/** Virtual placements always fit the whole image, centered and preserving its aspect ratio. */
internal fun TerminalGraphicsUnicodeRun.geometry(image: TerminalGraphicsPixels, placement: TerminalGraphicsPlacementGeometry,
    cell: TerminalCellSize?): TerminalGraphicsGeometry? {
    val cw = cell?.width ?: return null; val ch = cell.height
    if (cw <= 0 || ch <= 0) return null
    val columns = placement.columns.takeIf { it != 0L } ?: ((image.width.toLong() + cw - 1) / cw)
    val rows = placement.rows.takeIf { it != 0L } ?: ((image.height.toLong() + ch - 1) / ch)
    if (columns !in 1..65535L || rows !in 1..65535L) return null
    val gridWidth = columns.toDouble() * cw; val gridHeight = rows.toDouble() * ch
    val fitWidth = image.width * gridHeight > image.height * gridWidth
    val scale = if (fitWidth) gridWidth / image.width else gridHeight / image.height
    val offsetX = if (fitWidth) 0.0 else (gridWidth - image.width * scale) / 2 / scale
    val offsetY = if (fitWidth) (gridHeight - image.height * scale) / 2 / scale else 0.0
    val expandedWidth = image.width + offsetX * 2; val expandedHeight = image.height + offsetY * 2
    val sourceX = expandedWidth * (col.toDouble() / columns)
    val sourceY = expandedHeight * (row.toDouble() / rows)
    val sourceWidth = expandedWidth * (width.toDouble() / columns)
    val sourceHeight = expandedHeight * (1.0 / rows)
    // Keep the reference operation order: moving multiplication across the subtraction
    // changes half-pixel rounding for small images in non-square cells.
    data class Axis(val source: Double, val length: Double, val offset: Double, val pixels: Double)
    fun clip(start: Double, length: Double, padding: Double, expanded: Double, original: Int, pixels: Double): Axis {
        var s = start; var size = length; var offset = 0.0; var destination = pixels
        if (s < padding) {
            offset = padding - s; size -= offset; destination -= offset * scale; s = 0.0
            if (size > original) { size = original.toDouble(); destination = original * scale }
        } else if (s + size > expanded - padding) {
            s -= padding; size = expanded - padding - s; size -= padding; destination = size * scale
        } else s -= padding
        return Axis(s,size,offset * scale,destination)
    }
    val x = clip(sourceX,sourceWidth,offsetX,expandedWidth,image.width,width.toDouble() * cw)
    val y = clip(sourceY,sourceHeight,offsetY,expandedHeight,image.height,ch.toDouble())
    if (x.length <= 0 || y.length <= 0) return TerminalGraphicsGeometry(TerminalGraphicsSource(0,0,0,0),
        TerminalGraphicsSize(0,0), TerminalGraphicsSize(width.toLong(),1), TerminalGraphicsSize(0,0))
    fun rounded(value: Double): Long = value.roundToLong().coerceIn(0, 0xffffffffL)
    return TerminalGraphicsGeometry(
        TerminalGraphicsSource(rounded(x.source), rounded(y.source), rounded(x.length), rounded(y.length)),
        TerminalGraphicsSize(rounded(x.pixels), rounded(y.pixels)),
        TerminalGraphicsSize(width.toLong(), 1),
        TerminalGraphicsSize(rounded(x.offset), rounded(y.offset)))
}
