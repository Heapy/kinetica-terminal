package io.heapy.kinetica.terminal

/** Offsets into [TerminalAccessibleText.text] are UTF-16 code units, as required by NSRange. */
internal data class TerminalAccessibleRange(val start: Int, val end: Int)

internal data class TerminalAccessibleText(val text: String, val selections: List<TerminalAccessibleRange>)

/** Use the same physical rows and trimmed trailing blanks as TerminalViewport.visibleText(). */
internal fun terminalAccessibleText(
    rows: Int,
    firstLine: Int,
    line: (Int) -> TerminalLine,
    selection: TerminalSelectionRange?,
): TerminalAccessibleText {
    val text = StringBuilder()
    val starts = IntArray(rows)
    val lengths = IntArray(rows)
    val offsets = Array(rows) { row ->
        if (row > 0) text.append('\n')
        starts[row] = text.length
        val cells = line(row)
        val columns = IntArray(cells.columns + 1)
        for (column in 0 until cells.columns) {
            columns[column] = text.length - starts[row]
            if (cells.width(column) != 0) cells.appendTextTo(text, column)
        }
        while (text.length > starts[row] && text.last() == ' ') text.setLength(text.length - 1)
        lengths[row] = text.length - starts[row]
        columns[cells.columns] = lengths[row]
        columns
    }
    fun offset(position: TerminalCellPosition, end: Boolean): Int {
        val row = position.line - firstLine
        if (row < 0) return 0
        if (row >= rows) return text.length
        val cells = line(row)
        var column = position.column.coerceIn(0, cells.columns)
        // A rectangular edge may bisect a wide cell. Expose the whole glyph just as
        // selection rendering does, without introducing a surrogate-half range.
        if (column < cells.columns && cells.width(column) == 0) column += if (end) 1 else -1
        return starts[row] + offsets[row][column.coerceAtLeast(0)].coerceAtMost(lengths[row])
    }
    val ranges = if (selection == null || selection.start == selection.end ||
        selection.rectangle && selection.start.column == selection.end.column) emptyList() else if (selection.rectangle) {
        (maxOf(firstLine, selection.start.line)..minOf(firstLine + rows - 1, selection.end.line)).mapNotNull { row ->
            val start = offset(TerminalCellPosition(row, selection.start.column), false)
            val end = offset(TerminalCellPosition(row, selection.end.column), true)
            if (end > start) TerminalAccessibleRange(start, end) else null
        }
    } else {
        val start = offset(selection.start, false)
        val end = offset(selection.end, true)
        if (end > start) listOf(TerminalAccessibleRange(start, end)) else emptyList()
    }
    return TerminalAccessibleText(text.toString(), ranges)
}

/** Ghostty's SCREEN text: retained history and active rows, with soft wraps joined.
 * Selection offsets refer to that same text, independently of viewport scrolling.
 * Only rows containing selection boundaries need cell-offset arrays. */
internal fun terminalAccessibleDocument(
    rows: Int,
    line: (Int) -> TerminalLine,
    selection: TerminalSelectionRange?,
): TerminalAccessibleText {
    require(rows > 0)
    val boundaries = mutableMapOf<Int, IntArray>()
    fun track(row: Int) {
        if (row in 0 until rows && row !in boundaries) boundaries[row] = IntArray(line(row).columns + 1) { -1 }
    }
    if (selection != null) {
        if (selection.rectangle) {
            for (row in maxOf(0, selection.start.line)..minOf(rows - 1, selection.end.line)) track(row)
        } else { track(selection.start.line); track(selection.end.line) }
    }
    val text = terminalSelectionText(
        TerminalSelectionRange(TerminalCellPosition(0, 0), TerminalCellPosition(rows - 1, line(rows - 1).columns)),
        line,
        cellRange = if (boundaries.isEmpty()) null else { row, column, width, start, end ->
            boundaries[row]?.let { offsets ->
                offsets[column] = start
                if (width > 0) offsets[column + width] = end
            }
        },
    )
    for (offsets in boundaries.values) {
        var previous = offsets.firstOrNull { it >= 0 } ?: text.length
        for (column in offsets.indices) {
            if (offsets[column] < 0) offsets[column] = previous else previous = offsets[column]
        }
    }
    fun offset(position: TerminalCellPosition, end: Boolean): Int {
        if (position.line < 0) return 0
        if (position.line >= rows) return text.length
        val cells = line(position.line)
        return cells.readPreservingCompression {
            var column = position.column.coerceIn(0, cells.columns)
            if (column < cells.columns && cells.width(column) == 0) column += if (end) 1 else -1
            checkNotNull(boundaries[position.line])[column.coerceAtLeast(0)]
        }
    }
    val ranges = if (selection == null || selection.start == selection.end ||
        selection.rectangle && selection.start.column == selection.end.column) emptyList() else if (selection.rectangle) {
        (maxOf(0, selection.start.line)..minOf(rows - 1, selection.end.line)).mapNotNull { row ->
            val start = offset(TerminalCellPosition(row, selection.start.column), false)
            val end = offset(TerminalCellPosition(row, selection.end.column), true)
            if (end > start) TerminalAccessibleRange(start, end) else null
        }
    } else {
        val start = offset(selection.start, false)
        val end = offset(selection.end, true)
        if (end > start) listOf(TerminalAccessibleRange(start, end)) else emptyList()
    }
    return TerminalAccessibleText(text, ranges)
}
