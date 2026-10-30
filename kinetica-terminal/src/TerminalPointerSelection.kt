// Pointer thresholds adapted from Ghostty SelectionGesture.dragSelection (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.math.floor

internal data class TerminalSelectionPointer(val column: Int, val row: Int, val x: Double, val cellWidth: Int, val y: Double)

/** Align fractional host cells to an integer device-pixel grid before applying Ghostty's threshold. */
internal fun terminalSelectionPointer(x: Double, y: Double, cellWidth: Double, cellHeight: Double,
    scale: Double): TerminalSelectionPointer {
    val width = floor(cellWidth * scale + 0.5).toInt().coerceAtLeast(1)
    return TerminalSelectionPointer(floor(x / cellWidth).toInt(), floor(y / cellHeight).toInt(), x / cellWidth * width, width, y * scale)
}

internal fun terminalPointerSelectionRange(click: TerminalCellPosition, drag: TerminalCellPosition,
    clickX: Double, dragX: Double, columns: Int, cellWidth: Int, first: Int, last: Int,
    rectangle: Boolean, paddingLeft: Int = 0): TerminalSelectionRange? {
    if (columns <= 0 || cellWidth <= 0) return null
    val threshold = floor(cellWidth * 0.6 + 0.5).toLong()
    val maxX = minOf(columns.toLong() * cellWidth, 0xffff_ffffL) - 1
    fun fraction(x: Double): Long {
        val pixel = if (x.isNaN() || x <= 0) 0L else x.coerceAtMost(0xffff_ffffL.toDouble()).toLong()
        return (pixel - paddingLeft).coerceIn(0, maxX) % cellWidth
    }
    val clickFraction = fraction(clickX)
    val dragFraction = fraction(dragX)
    val same = click == drag
    val backwards = when {
        same || rectangle && click.column == drag.column -> dragFraction < clickFraction
        rectangle -> drag.column < click.column
        else -> drag < click
    }
    val includeClick = if (backwards) clickFraction >= threshold else clickFraction < threshold
    val includeDrag = if (backwards) dragFraction < threshold else dragFraction >= threshold
    fun move(point: TerminalCellPosition, delta: Int): TerminalCellPosition {
        val x = point.column + delta
        if (rectangle) return point.copy(column = x.coerceIn(0, columns - 1))
        return when {
            x < 0 && point.line > first -> TerminalCellPosition(point.line - 1, columns - 1)
            x >= columns && point.line < last -> TerminalCellPosition(point.line + 1, 0)
            x !in 0 until columns -> point
            else -> point.copy(column = x)
        }
    }
    val start = if (includeClick) click else move(click, if (backwards) -1 else 1)
    val end = if (includeDrag) drag else move(drag, if (backwards) 1 else -1)
    if (!includeClick && (same || end == click || rectangle && (click.column == drag.column || end.column == click.column)) ||
        !includeDrag && (start == drag || rectangle && start.column == drag.column)) return null
    return if (rectangle) TerminalSelectionRange(
        TerminalCellPosition(minOf(start.line, end.line), minOf(start.column, end.column)),
        TerminalCellPosition(maxOf(start.line, end.line), maxOf(start.column, end.column) + 1), true)
    else TerminalSelectionRange(minOf(start, end), maxOf(start, end).let { it.copy(column = it.column + 1) })
}
