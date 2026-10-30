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
