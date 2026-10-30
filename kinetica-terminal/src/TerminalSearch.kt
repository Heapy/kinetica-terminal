package io.heapy.kinetica.terminal

/** End position is exclusive and covers complete terminal graphemes. */
public data class TerminalSearchMatch(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
public data class TerminalSearchResults(val matches: List<TerminalSearchMatch>, val truncated: Boolean)

/** Literal search joins soft wraps and retains real hard-line boundaries. */
public fun TerminalSession.search(query: String, caseSensitive: Boolean = false, limit: Int = 10_000): TerminalSearchResults {
    require(limit in 1..100_000)
    if (query.isEmpty()) return TerminalSearchResults(emptyList(), false)
    val matches = mutableListOf<TerminalSearchMatch>()
    var row = 0
    while (row < lineCount) {
        val logical = terminalLogicalLine(row, 0, lineCount - 1, ::line)
        var offset = 0
        while (offset <= logical.text.length - query.length) {
            val found = logical.text.indexOf(query, offset, ignoreCase = !caseSensitive)
            if (found < 0) break
            if (matches.size == limit) return TerminalSearchResults(matches, true)
            val start = logical.position(found, false, ::line)
            val end = logical.position(found + query.length - 1, true, ::line)
            matches += TerminalSearchMatch(start.line, start.column, end.line, end.column)
            offset = found + maxOf(1, query.length)
        }
        row = logical.last + 1
    }
    return TerminalSearchResults(matches, false)
}

public fun TerminalSession.hyperlinkAt(line: Int, column: Int): String? {
    if (line !in 0 until lineCount || column !in 0 until columns) return null
    return terminalHyperlinkAt(line, column, 0, lineCount - 1, this::line, osc8LinksEnabled)
}

internal fun terminalHyperlinkAt(row: Int, column: Int, first: Int, last: Int, line: (Int) -> TerminalLine,
    osc8Enabled: Boolean = true): String? {
    val cell = line(row)
    val x = if (cell.width(column) == 0) (column - 1).coerceAtLeast(0) else column
    if (osc8Enabled) cell.hyperlink(x)?.let { return it.uri }
    return terminalDetectedLinkAt(row, x, first, last, line)?.uri
}

internal data class TerminalDetectedLink(val uri: String, val range: TerminalSelectionRange)

/** Visible-text links only: OSC 8 targets do not determine double-click selection. */
internal fun terminalDetectedLinkAt(row: Int, column: Int, first: Int, last: Int,
    line: (Int) -> TerminalLine): TerminalDetectedLink? {
    val cell = line(row)
    val x = if (cell.width(column) == 0) (column - 1).coerceAtLeast(0) else column
    val range = terminalSemanticLineRange(row, x, first, last, line)
    var offset = 0
    val text = buildString {
        for (y in range.start.line..range.end.line) {
            val value = line(y)
            val from = if (y == range.start.line) range.start.column else 0
            val to = if (y == range.end.line) range.end.column else value.columns
            for (column in from until to) {
                if (y == row && column == x) offset = length
                if (value.width(column) > 0 && !value.isSpacerHead(column)) value.appendTextTo(this, column)
            }
        }
    }
    for (match in terminalUrl.findAll(text)) {
        var url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
        for ((close, open) in listOf(')' to '(', ']' to '[', '}' to '{')) {
            while (url.endsWith(close) && url.count { it == close } > url.count { it == open }) url = url.dropLast(1)
        }
        if (offset in match.range.first until match.range.first + url.length) {
            fun position(offset: Int, end: Boolean): TerminalCellPosition {
                var current = 0
                for (y in range.start.line..range.end.line) {
                    val value = line(y)
                    val from = if (y == range.start.line) range.start.column else 0
                    val to = if (y == range.end.line) range.end.column else value.columns
                    for (column in from until to) {
                        if (value.width(column) == 0 || value.isSpacerHead(column)) continue
                        current += value.text(column).length
                        if (current > offset) return TerminalCellPosition(y, column + if (end) value.width(column) else 0)
                    }
                }
                error("Matched URL offset outside its terminal cells")
            }
            return TerminalDetectedLink(url, TerminalSelectionRange(position(match.range.first, false),
                position(match.range.first + url.length - 1, true)))
        }
    }
    return null
}

private val terminalUrl = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

private class LogicalTerminalLine(val first: Int, val last: Int, val text: String, val starts: IntArray) {
    fun position(offset: Int, end: Boolean, line: (Int) -> TerminalLine): TerminalCellPosition {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) / 2
            if (starts[middle] <= offset) low = middle + 1 else high = middle
        }
        // At a soft-wrap boundary choose the following row, including empty segments.
        val index = (low - 1).coerceAtLeast(0)
        val row = line(first + index)
        var position = starts[index]
        for (x in 0 until row.columns) if (row.width(x) > 0 && !row.isSpacerHead(x)) {
            position += row.text(x).length
            if (position > offset) return TerminalCellPosition(first + index, x + if (end) maxOf(1, row.width(x)) else 0)
        }
        return TerminalCellPosition(first + index, row.columns)
    }
}

private fun terminalLogicalLine(row: Int, firstLine: Int, lastLine: Int, line: (Int) -> TerminalLine): LogicalTerminalLine {
    var first = row
    while (first > firstLine && line(first - 1).wrapped) first--
    var last = row
    while (last < lastLine && line(last).wrapped) last++
    val starts = IntArray(last - first + 1)
    val text = buildString {
        for (y in first..last) {
            starts[y - first] = length
            val value = line(y)
            val part = buildString {
                for (x in 0 until value.columns) if (value.width(x) > 0 && !value.isSpacerHead(x)) value.appendTextTo(this, x)
            }
            append(if (value.wrapped) part else part.trimEnd(' '))
        }
    }
    return LogicalTerminalLine(first, last, text, starts)
}

/** POSIX shell word quoting, also used for file drops; paths are never executed by the host. */
public fun terminalShellQuote(value: String): String {
    require('\u0000' !in value)
    return "'" + value.replace("'", "'\\''") + "'"
}
