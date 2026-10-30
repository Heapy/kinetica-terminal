// Output selection adapted from Ghostty Screen.selectOutput and PageList semantic helpers (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal fun terminalOutputRange(point: TerminalCellPosition, first: Int, last: Int,
    line: (Int) -> TerminalLine): TerminalSelectionRange? {
    if (line(point.line).semanticContent(point.column) != TerminalSemanticContent.OUTPUT) return null
    val prompt = outputPromptAbove(point.line, first, line)
    if (prompt == null) {
        val next = (point.line..last).firstOrNull { line(it).promptMarker != TerminalPromptMarker.NONE } ?: return null
        if (next <= first) return null
        val start = TerminalCellPosition(first, 0)
        var end = start
        outer@ for (y in next - 1 downTo first) for (x in line(y).columns - 1 downTo 0) {
            end = TerminalCellPosition(y, x)
            if (line(y).hasText(x)) break@outer
        }
        return TerminalSelectionRange(start, end.copy(column = end.column + 1))
    }
    // A prompt and its continuation rows form one command zone.
    var afterPrompt = prompt + 1
    while (afterPrompt <= last && line(afterPrompt).promptMarker == TerminalPromptMarker.CONTINUATION) afterPrompt++
    val next = (afterPrompt..last).firstOrNull { line(it).promptMarker != TerminalPromptMarker.NONE }
    val endRow = next?.minus(1) ?: last
    var start: TerminalCellPosition? = null
    var end: TerminalCellPosition? = null
    outer@ for (y in prompt..endRow) for (x in 0 until line(y).columns) {
        val cell = line(y)
        if (cell.semanticContent(x) != TerminalSemanticContent.OUTPUT) {
            if (start != null) break@outer
        } else if (cell.hasText(x)) {
            val position = TerminalCellPosition(y, x)
            if (start == null) start = position
            end = position.copy(column = x + 1)
        }
    }
    return start?.let { TerminalSelectionRange(it, checkNotNull(end)) }
}

private fun outputPromptAbove(row: Int, first: Int, line: (Int) -> TerminalLine): Int? {
    for (y in row downTo first) when (line(y).promptMarker) {
        TerminalPromptMarker.NONE -> Unit
        TerminalPromptMarker.PROMPT -> return y
        TerminalPromptMarker.CONTINUATION -> {
            var current = y
            while (current > first) {
                when (line(current - 1).promptMarker) {
                    TerminalPromptMarker.NONE -> return current
                    TerminalPromptMarker.PROMPT -> return current - 1
                    TerminalPromptMarker.CONTINUATION -> current--
                }
            }
            // Original PromptIterator returns its starting continuation when history ends.
            return y
        }
    }
    return null
}
