// Resize/reflow semantics adapted from Ghostty's Screen.zig and PageList.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal data class TerminalPosition(val x: Int, val y: Int, val pendingWrap: Boolean)

internal data class TerminalResizeResult(
    val history: List<TerminalLine>,
    val screen: Array<TerminalLine>,
    val cursor: TerminalPosition,
    val saved: TerminalPosition?,
)

/** Rewrites packed rows; only the two cursor positions allocate markers, never individual cells. */
internal fun resizeTerminal(
    screen: Array<TerminalLine>,
    history: List<TerminalLine>,
    cursor: TerminalPosition,
    saved: TerminalPosition?,
    columns: Int,
    rows: Int,
    historyLimit: Int,
    historyByteLimit: Long,
    reflow: Boolean,
): TerminalResizeResult = TerminalResizer(screen, history, cursor, saved, historyLimit, historyByteLimit).resize(columns, rows, reflow)

private class TerminalResizer(
    screen: Array<TerminalLine>,
    history: List<TerminalLine>,
    cursor: TerminalPosition,
    saved: TerminalPosition?,
    private val historyLimit: Int,
    private val historyByteLimit: Long,
) {
    private var lines = ArrayDeque<TerminalLine>(history.size + screen.size).apply {
        addAll(history); addAll(screen)
    }
    private val colors = screen[0].colors
    private val hyperlinkLimit = screen[0].hyperlinkLimit
    private var columns = screen[0].columns
    private var rows = screen.size
    private val originalCursorRow = cursor.y
    private var cursor = cursor.copy(y = history.size + cursor.y)
    private var saved = saved?.copy(y = history.size + saved.y)

    fun resize(columns: Int, rows: Int, reflow: Boolean): TerminalResizeResult {
        when {
            !reflow -> {
                if (columns != this.columns) {
                    lines = ArrayDeque(lines.map { it.resized(columns) })
                    cursor = cursor.copy(x = cursor.x.coerceAtMost(columns - 1))
                    saved = saved?.let { it.copy(x = it.x.coerceAtMost(columns - 1)) }
                    this.columns = columns
                }
                resizeRows(rows)
            }
            columns > this.columns -> { reflowColumns(columns); resizeRows(rows) }
            columns < this.columns -> { resizeRows(rows); reflowColumns(columns) }
            else -> resizeRows(rows)
        }
        val historySize = lines.size - rows
        var firstHistory = historySize
        var historyBytes = 0L
        while (firstHistory > 0 && historySize - firstHistory < historyLimit) {
            val line = lines[firstHistory - 1]
            if (line.storageBytes > historyByteLimit - historyBytes) break
            historyBytes += line.storageBytes
            firstHistory--
        }
        fun active(position: TerminalPosition, saved: Boolean): TerminalPosition {
            val y = position.y - historySize
            val mapped = if (y in 0 until rows) position.copy(y = y)
                else TerminalPosition(0, 0, !saved && position.pendingWrap)
            return if (mapped.pendingWrap && mapped.x != columns - 1)
                mapped.copy(x = mapped.x + 1, pendingWrap = false) else mapped
        }
        return TerminalResizeResult(
            history = (firstHistory until historySize).map { lines[it] },
            screen = Array(rows) { lines[historySize + it] },
            cursor = active(cursor, false),
            saved = saved?.let { active(it, true) },
        )
    }

    private fun resizeRows(next: Int) {
        if (next < rows) {
            // Prefer retiring unused bottom rows; a cursor pin makes a blank row significant.
            var remaining = rows - next
            while (remaining-- > 0) {
                val y = lines.lastIndex
                if (lines.last().hasText() || cursor.y == y || saved?.y == y) break
                lines.removeLast()
            }
        } else if (next > rows) {
            if (originalCursorRow < rows - 1) repeat(next - rows) { lines.addLast(TerminalLine(columns, colors, hyperlinkLimit)) }
            else while (lines.size < next) lines.addLast(TerminalLine(columns, colors, hyperlinkLimit))
        }
        rows = next
    }

    private fun wrappedBefore(position: TerminalPosition): Int {
        val start = lines.size - rows
        if (position.y !in start until lines.size) return 0
        return (start..position.y).count { lines[it].wrapContinuation }
    }

    private fun reflowColumns(next: Int) {
        val remainingRows = (rows - originalCursorRow - 1).coerceAtLeast(0)
        val oldWraps = wrappedBefore(cursor)
        // Evict while writing, so a very narrow resize cannot allocate one row per old cell.
        val capacity = rows + historyLimit
        val output = ArrayDeque<TerminalLine>()
        var historyBytes = 0L
        var dropped = 0
        var destination = TerminalLine(next, colors, hyperlinkLimit)
        output.addLast(destination)
        var x = 0
        var deferredRows = 0
        var newCursor: TerminalPosition? = null
        var newSaved: TerminalPosition? = null
        fun retain(line: TerminalLine) {
            output.addLast(line)
            if (output.size > rows) historyBytes += output[output.size - rows - 1].storageBytes
            while (output.size > rows && (output.size > capacity || historyBytes > historyByteLimit)) {
                historyBytes -= output.removeFirst().storageBytes
                dropped++
            }
        }
        fun nextLine(continuation: Boolean = false) {
            destination = TerminalLine(next, colors, hyperlinkLimit).also { it.wrapContinuation = continuation }
            retain(destination)
            x = 0
        }
        fun outputPosition(position: TerminalPosition) = position.copy(
            x = x.coerceAtMost(next - 1), y = dropped + output.lastIndex,
        )
        for ((y, source) in lines.withIndex()) {
            var length = columns
            if (!source.wrapped) while (length > 0 && source.isEmpty(length - 1)) length--
            val savedHere = saved?.takeIf { it.y == y }?.let {
                if (it.x >= length) it.copy(x = minOf(it.x, next - 1 - x.coerceAtMost(next - 1))) else it
            }
            if (savedHere != null) length = maxOf(length, savedHere.x + 1)
            if (cursor.y == y) length = maxOf(length, cursor.x + 1)
            if (length == 0) {
                if (!source.wrapContinuation) deferredRows++
                continue
            }
            repeat(deferredRows) { nextLine() }
            deferredRows = 0
            var sourceX = 0
            while (sourceX < length) {
                if (x == next) {
                    destination.wrapped = true
                    nextLine(continuation = true)
                }
                // Map before copying, including a wide glyph that first needs a spacer head.
                if (newCursor == null && cursor.y == y && cursor.x == sourceX) newCursor = outputPosition(cursor)
                if (newSaved == null && savedHere?.x == sourceX) newSaved = outputPosition(savedHere)
                when {
                    source.isSpacerHead(sourceX) -> sourceX++
                    source.width(sourceX) == 2 && next == 1 -> {
                        destination.clear(x, x + 1, -1, -1, 0)
                        x++
                        if (newCursor == null && cursor.y == y && cursor.x == sourceX + 1) newCursor = outputPosition(cursor)
                        if (newSaved == null && savedHere?.x == sourceX + 1) newSaved = outputPosition(savedHere)
                        sourceX += 2
                    }
                    source.width(sourceX) == 2 && x == next - 1 -> {
                        destination.putSpacerHead(x)
                        x++
                    }
                    else -> { destination.copyCell(source, sourceX++, x++) }
                }
            }
            if (!source.wrapped) deferredRows++
        }
        cursor = checkNotNull(newCursor).let { it.copy(y = it.y - dropped) }
        saved = newSaved?.let { it.copy(y = it.y - dropped) }
        lines = output
        columns = next
        while (lines.size < rows) retain(TerminalLine(columns, colors, hyperlinkLimit))
        val activeY = cursor.y - (lines.size - rows)
        if (activeY in 0 until rows) {
            val additionalWraps = (wrappedBefore(cursor) - oldWraps).coerceAtLeast(0)
            val padding = (remainingRows - additionalWraps - (rows - activeY - 1)).coerceAtLeast(0)
            val droppedBeforePadding = dropped
            repeat(padding) { retain(TerminalLine(columns, colors, hyperlinkLimit)) }
            cursor = cursor.copy(y = cursor.y - (dropped - droppedBeforePadding))
            saved = saved?.let { it.copy(y = it.y - (dropped - droppedBeforePadding)) }
        }
    }
}
