// Placement geometry adapted from pinned Ghostty kitty/graphics_storage.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal data class TerminalGraphicsSize(val width: Long, val height: Long)
internal data class TerminalGraphicsSource(val x: Long, val y: Long, val width: Long, val height: Long)
internal enum class TerminalGraphicsLayer { BELOW_BACKGROUND, BELOW_TEXT, ABOVE_TEXT }

internal data class TerminalGraphicsGeometry(
    val source: TerminalGraphicsSource,
    val pixels: TerminalGraphicsSize,
    val grid: TerminalGraphicsSize,
    val offset: TerminalGraphicsSize,
)

/** Unsigned wire values remain Long until clipped to actual image, cell or viewport bounds. */
internal data class TerminalGraphicsPlacementGeometry(
    val xOffset: Long = 0,
    val yOffset: Long = 0,
    val sourceX: Long = 0,
    val sourceY: Long = 0,
    val sourceWidth: Long = 0,
    val sourceHeight: Long = 0,
    val columns: Long = 0,
    val rows: Long = 0,
    val z: Int = 0,
) {
    init {
        require(xOffset in 0..MAX && yOffset in 0..MAX && sourceX in 0..MAX && sourceY in 0..MAX &&
            sourceWidth in 0..MAX && sourceHeight in 0..MAX && columns in 0..MAX && rows in 0..MAX)
    }

    val layer: TerminalGraphicsLayer get() = when {
        z < Int.MIN_VALUE / 2 -> TerminalGraphicsLayer.BELOW_BACKGROUND
        z < 0 -> TerminalGraphicsLayer.BELOW_TEXT
        else -> TerminalGraphicsLayer.ABOVE_TEXT
    }

    fun source(imageWidth: Int, imageHeight: Int): TerminalGraphicsSource {
        require(imageWidth in 1..10_000 && imageHeight in 1..10_000)
        val x = minOf(sourceX, imageWidth.toLong())
        val y = minOf(sourceY, imageHeight.toLong())
        return TerminalGraphicsSource(x, y,
            minOf(if (sourceWidth == 0L) imageWidth.toLong() else sourceWidth, imageWidth - x),
            minOf(if (sourceHeight == 0L) imageHeight.toLong() else sourceHeight, imageHeight - y))
    }

    fun resolve(imageWidth: Int, imageHeight: Int, cell: TerminalCellSize?): TerminalGraphicsGeometry {
        val source = source(imageWidth, imageHeight)
        val cw = cell?.width ?: 0
        val ch = cell?.height ?: 0
        val offset = TerminalGraphicsSize(clampOffset(xOffset, cw), clampOffset(yOffset, ch))
        val pixels = when {
            columns == 0L && rows == 0L -> TerminalGraphicsSize(source.width, source.height)
            columns > 0L && rows > 0L -> TerminalGraphicsSize(
                multiply(cw, columns) - offset.width, multiply(ch, rows) - offset.height)
            columns > 0L -> {
                val width = multiply(cw, columns) - offset.width
                TerminalGraphicsSize(width, scale(width, source.height, source.width))
            }
            else -> {
                val height = multiply(ch, rows) - offset.height
                TerminalGraphicsSize(scale(height, source.width, source.height), height)
            }
        }
        val grid = if (columns > 0L && rows > 0L) TerminalGraphicsSize(columns, rows)
        else TerminalGraphicsSize(ceil(minOf(MAX, pixels.width + offset.width), cw), ceil(minOf(MAX, pixels.height + offset.height), ch))
        return TerminalGraphicsGeometry(source, pixels, grid, offset)
    }

    /** Known cell offsets are clamped once when placing; unknown ones survive until geometry arrives. */
    fun atCreation(cell: TerminalCellSize?): TerminalGraphicsPlacementGeometry = copy(
        xOffset = if (cell != null && cell.width > 0) clampOffset(xOffset, cell.width) else xOffset,
        yOffset = if (cell != null && cell.height > 0) clampOffset(yOffset, cell.height) else yOffset)

    /** Materialize a source crop when margin scrolling clips the top; null means no pixels remain. */
    fun clipTop(imageWidth: Int, imageHeight: Int, cell: TerminalCellSize?, count: Long, span: Long): TerminalGraphicsPlacementGeometry? {
        require(count > 0 && count < span && span <= MAX)
        val source = source(imageWidth, imageHeight)
        val crop = if (rows > 0) source.height * count / span else multiply(cell?.height ?: 0, count)
        if (crop >= source.height) return null
        return copy(sourceX = source.x, sourceY = source.y + crop, sourceWidth = source.width,
            sourceHeight = source.height - crop, rows = if (rows > 0) rows - count else 0)
    }

    /** Bottom clipping accounts for the first cell's offset for a native-size placement. */
    fun clipBottom(imageWidth: Int, imageHeight: Int, cell: TerminalCellSize?, count: Long, span: Long): TerminalGraphicsPlacementGeometry? {
        require(count > 0 && count < span && span <= MAX)
        val source = source(imageWidth, imageHeight)
        if (source.height == 0L) return null
        val remaining = if (rows > 0L) {
            val crop = source.height * count / span
            if (crop >= source.height) return null
            source.height - crop
        } else {
            val visible = multiply(cell?.height ?: 0, span - count)
            val offset = clampOffset(yOffset, cell?.height ?: 0)
            if (visible <= offset) return null
            minOf(source.height, visible - offset)
        }
        return copy(sourceX = source.x, sourceY = source.y, sourceWidth = source.width,
            sourceHeight = remaining, rows = if (rows > 0) rows - count else 0)
    }

    companion object {
        private const val MAX = 0xffffffffL
        fun from(command: TerminalGraphicsCommand, cell: TerminalCellSize?): TerminalGraphicsPlacementGeometry =
            TerminalGraphicsPlacementGeometry(command.unsigned('X'), command.unsigned('Y'),
                command.unsigned('x'), command.unsigned('y'), command.unsigned('w'), command.unsigned('h'),
                command.unsigned('c'), command.unsigned('r'), command.signed('z')).atCreation(cell)

        private fun clampOffset(value: Long, cell: Int): Long = if (cell == 0) 0 else minOf(value, cell.toLong() - 1)
        private fun multiply(cell: Int, cells: Long): Long = minOf(MAX, cell.toLong() * cells)
        // Source dimensions are <= 10000, so this product and rounding fit in signed Long on every target.
        private fun scale(value: Long, numerator: Long, denominator: Long): Long =
            if (denominator == 0L) 0 else minOf(MAX, (value * numerator + denominator / 2) / denominator)
        private fun ceil(value: Long, divisor: Int): Long = if (divisor == 0) 0 else (value + divisor - 1) / divisor
    }
}

internal data class TerminalGraphicsCursorAdvance(val indexRows: Int, val column: Int)
internal data class TerminalGraphicsViewportPosition(val column: Int, val row: Int)

/** Origins can be negative for scrollback or relative placements; clip in the renderer. */
internal fun graphicsViewportPosition(column: Int, row: Int, grid: TerminalGraphicsSize,
    viewportColumns: Int, viewportRows: Int): TerminalGraphicsViewportPosition? {
    require(viewportColumns > 0 && viewportRows > 0)
    return if (row.toLong() + grid.height > 0 && row < viewportRows &&
        column.toLong() + grid.width > 0 && column < viewportColumns) TerminalGraphicsViewportPosition(column, row) else null
}

internal data class TerminalGraphicsMarginPlacement(val geometry: TerminalGraphicsPlacementGeometry, val row: Int)

/**
 * Margin scrolling only moves placements fully contained in the rectangle. The caller
 * leaves virtual/relative placements and full-screen scrolls to their own anchor tracking.
 * Null removes the placement, retaining its image data; a result supplies its new pin row.
 */
internal fun graphicsScrollWithinMargins(
    geometry: TerminalGraphicsPlacementGeometry,
    imageWidth: Int,
    imageHeight: Int,
    cell: TerminalCellSize?,
    column: Int,
    row: Int,
    terminalColumns: Int,
    top: Int,
    bottom: Int,
    left: Int,
    right: Int,
    delta: Int,
): TerminalGraphicsMarginPlacement? {
    require(terminalColumns > 0 && column in 0 until terminalColumns && top >= 0 && bottom >= top && left in 0..right && right < terminalColumns)
    val unchanged = TerminalGraphicsMarginPlacement(geometry, row)
    val grid = geometry.resolve(imageWidth, imageHeight, cell).grid
    if (grid.width == 0L || grid.height == 0L || row < top || row + grid.height - 1 > bottom ||
        column < left || minOf(column + grid.width - 1, terminalColumns.toLong() - 1) > right) return unchanged
    val next = row.toLong() + delta
    val topClip = maxOf(0L, top - next)
    val bottomClip = maxOf(0L, next + grid.height - 1 - bottom)
    if (topClip >= grid.height || bottomClip >= grid.height) return null
    if (topClip > 0) return geometry.clipTop(imageWidth, imageHeight, cell, topClip, grid.height)?.let {
        TerminalGraphicsMarginPlacement(it, top)
    }
    if (bottomClip > 0) return geometry.clipBottom(imageWidth, imageHeight, cell, bottomClip, grid.height)?.let {
        TerminalGraphicsMarginPlacement(it, next.toInt())
    }
    return TerminalGraphicsMarginPlacement(geometry, next.toInt())
}

/** Cursor advancement invokes the terminal's normal index operation, bounded to at most two screens. */
internal fun graphicsCursorAdvance(
    grid: TerminalGraphicsSize,
    column: Int,
    terminalColumns: Int,
    terminalRows: Int,
    rowsBeforeScroll: Int,
): TerminalGraphicsCursorAdvance {
    require(terminalColumns > 0 && terminalRows > 0 && column in 0 until terminalColumns && rowsBeforeScroll in 0 until terminalRows)
    val target = column + grid.width
    val wraps = target >= terminalColumns
    val requested = maxOf(0L, grid.height - 1) + if (wraps) 1 else 0
    return TerminalGraphicsCursorAdvance(minOf(requested, rowsBeforeScroll.toLong() + terminalRows).toInt(),
        if (wraps) 0 else target.toInt())
}
