// Semantic line boundaries adapted from Ghostty Screen.selectLine (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Untrimmed, end-exclusive cell range for one semantic region of a soft-wrapped line. */
internal fun terminalSemanticLineRange(row: Int, column: Int, first: Int, last: Int,
    line: (Int) -> TerminalLine): TerminalSelectionRange {
    val content = line(row).semanticContent(column)
    var startRow = row
    var startColumn = column
    while (true) {
        val previousRow = if (startColumn == 0) startRow - 1 else startRow
        if (previousRow < first || previousRow != startRow && !line(previousRow).wrapped) break
        val previousColumn = if (previousRow == startRow) startColumn - 1 else line(previousRow).columns - 1
        if (line(previousRow).semanticContent(previousColumn) != content) break
        startRow = previousRow; startColumn = previousColumn
    }
    var endRow = row
    var endColumn = column
    while (true) {
        val nextRow = if (endColumn == line(endRow).columns - 1) endRow + 1 else endRow
        if (nextRow > last || nextRow != endRow && !line(endRow).wrapped) break
        val nextColumn = if (nextRow == endRow) endColumn + 1 else 0
        if (line(nextRow).semanticContent(nextColumn) != content) break
        endRow = nextRow; endColumn = nextColumn
    }
    return TerminalSelectionRange(TerminalCellPosition(startRow, startColumn), TerminalCellPosition(endRow, endColumn + 1))
}

/** Ghostty's default line selection trims only empty cells, NUL, ASCII space and tab. */
internal fun terminalSelectedLineRange(row: Int, column: Int, first: Int, last: Int,
    line: (Int) -> TerminalLine): TerminalSelectionRange? {
    val range = terminalSemanticLineRange(row, column, first, last, line)
    var start: TerminalCellPosition? = null
    var end: TerminalCellPosition? = null
    for (y in range.start.line..range.end.line) {
        val value = line(y)
        val from = if (y == range.start.line) range.start.column else 0
        val to = if (y == range.end.line) range.end.column else value.columns
        for (x in from until to) {
            if (!value.hasText(x) || value.isSpacerHead(x)) continue
            when (value.codePoint(x)) { 0, 32, 9 -> continue }
            if (start == null) start = TerminalCellPosition(y, x)
            end = TerminalCellPosition(y, x + 1)
        }
    }
    return start?.let { TerminalSelectionRange(it, checkNotNull(end)) }
}
