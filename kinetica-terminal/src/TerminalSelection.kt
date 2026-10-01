package io.heapy.kinetica.terminal

/** Drag granularity. WORD uses whitespace and shell punctuation; LINE includes soft wraps. */
public enum class TerminalSelectionMode { CHARACTER, WORD, LINE }

internal data class TerminalCellPosition(val line: Int, val column: Int) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

internal data class TerminalSelectionRange(val start: TerminalCellPosition, val end: TerminalCellPosition)

/** Row references are anchors, not snapshots: selected cells invalidate before being overwritten.
 * Watches grow/shrink only at the selection's edges. Ordinary scrolling resolves history anchors
 * in O(1), without scanning or copying the selected history on every output notification. */
internal class TerminalSelection(
    private val firstLine: () -> Int,
    private val lastLine: () -> Int,
    private val line: (Int) -> TerminalLine,
    private val indexOf: (TerminalLine) -> Int,
    private val resetEpoch: () -> Long = { 0L },
    private val orderEpoch: () -> Long = { 0L },
) {
    private data class Anchor(val row: TerminalLine, val column: Int, val recycle: Long = row.recycleEpoch)
    private inner class Watch(val row: TerminalLine) {
        var first = 0
        var end = row.columns + 1
        val recycle = row.recycleEpoch
        val subscription = row.observeText { from, to -> if (from < end && to > first) invalid = true }
    }
    private val watches = ArrayDeque<Watch>()
    private var watchFirst = 0
    private var anchorStart: Anchor? = null
    private var anchorEnd: Anchor? = null
    private var bounds: TerminalSelectionRange? = null
    private var reset = resetEpoch()
    private var order = orderEpoch()
    private var invalid = false
    private var mode = TerminalSelectionMode.CHARACTER
    val hasAnchor: Boolean get() = bounds != null

    fun clear() {
        watches.forEach { it.subscription.dispose() }; watches.clear()
        bounds = null; anchorStart = null; anchorEnd = null; invalid = false
        reset = resetEpoch(); order = orderEpoch()
    }

    fun range(): TerminalSelectionRange? { reconcile(); return bounds }

    fun begin(position: TerminalCellPosition, mode: TerminalSelectionMode) {
        clear(); this.mode = mode
        val unit = unit(position)
        anchorStart = anchor(unit.start); anchorEnd = anchor(unit.end)
        setRange(unit)
    }

    fun extend(position: TerminalCellPosition) {
        reconcile()
        val start = anchorStart?.position() ?: return
        val end = anchorEnd?.position() ?: return
        val focus = unit(position)
        setRange(normalize(TerminalSelectionRange(minOf(start, focus.start), maxOf(end, focus.end))))
    }

    fun text(): String {
        val range = range() ?: return ""
        if (range.start == range.end) return ""
        return buildString {
            for (y in range.start.line..range.end.line) {
                val row = line(y)
                val from = if (y == range.start.line) range.start.column else 0
                val to = if (y == range.end.line) range.end.column else row.columns
                val value = buildString {
                    for (x in from until to) if (row.width(x) != 0 && !row.isSpacerHead(x)) row.appendTextTo(this, x)
                }
                append(if (to == row.columns && !row.wrapped) value.trimEnd(' ') else value)
                if (y < range.end.line && !row.wrapped) append('\n')
            }
            if (mode == TerminalSelectionMode.LINE && !line(range.end.line).wrapped) append('\n')
        }
    }

    private fun Anchor.position(): TerminalCellPosition? {
        val index = indexOf(row)
        return if (index >= firstLine() && index <= lastLine() && row.recycleEpoch == recycle)
            TerminalCellPosition(index, column) else null
    }
    private fun anchor(position: TerminalCellPosition) = Anchor(line(position.line), position.column)

    private fun reconcile() {
        val old = bounds ?: return
        if (invalid || reset != resetEpoch()) { clear(); return }
        val first = watches.firstOrNull() ?: return
        val last = watches.last()
        val index = indexOf(first.row)
        if (index < firstLine() || indexOf(last.row) != index + watches.size - 1 ||
            first.row.recycleEpoch != first.recycle || last.row.recycleEpoch != last.recycle) { clear(); return }
        if (order != orderEpoch()) {
            for ((offset, watch) in watches.withIndex()) {
                if (indexOf(watch.row) != index + offset || watch.row.recycleEpoch != watch.recycle) { clear(); return }
            }
            order = orderEpoch()
        }
        val delta = index - watchFirst
        watchFirst = index
        bounds = TerminalSelectionRange(old.start.copy(line = old.start.line + delta), old.end.copy(line = old.end.line + delta))
    }

    private fun setRange(range: TerminalSelectionRange) {
        val first = range.start.line
        val end = range.end.line
        // Former edge rows become ordinary interior rows as the pointer moves.
        watches.firstOrNull()?.let { it.first = 0; it.end = it.row.columns + 1 }
        watches.lastOrNull()?.let { it.first = 0; it.end = it.row.columns + 1 }
        while (watches.isNotEmpty() && watchFirst < first) { watches.removeFirst().subscription.dispose(); watchFirst++ }
        while (watches.isNotEmpty() && watchFirst + watches.size - 1 > end) watches.removeLast().subscription.dispose()
        if (watches.isEmpty()) watchFirst = first
        while (watchFirst > first) { watchFirst--; watches.addFirst(Watch(line(watchFirst))) }
        while (watchFirst + watches.size <= end) watches.addLast(Watch(line(watchFirst + watches.size)))
        watches.first().first = range.start.column
        // Include the hard/soft line boundary only when copying it matters.
        watches.last().end = range.end.column + if (mode == TerminalSelectionMode.LINE) 1 else 0
        bounds = range
    }

    private fun unit(position: TerminalCellPosition): TerminalSelectionRange {
        val y = position.line.coerceIn(firstLine(), lastLine())
        val row = line(y)
        val x = position.column.coerceIn(0, row.columns)
        val point = TerminalCellPosition(y, x)
        return when (mode) {
            TerminalSelectionMode.CHARACTER -> TerminalSelectionRange(point, point)
            TerminalSelectionMode.WORD -> word(TerminalCellPosition(y, x.coerceAtMost(row.columns - 1)))
            TerminalSelectionMode.LINE -> {
                var first = y; var last = y
                while (first > firstLine() && line(first - 1).wrapped) first--
                while (last < lastLine() && line(last).wrapped) last++
                TerminalSelectionRange(TerminalCellPosition(first, 0), TerminalCellPosition(last, line(last).columns))
            }
        }
    }

    private fun normalize(range: TerminalSelectionRange): TerminalSelectionRange {
        if (range.start == range.end) return range
        fun edge(point: TerminalCellPosition, end: Boolean): TerminalCellPosition {
            val row = line(point.line)
            return if (point.column < row.columns && row.width(point.column) == 0)
                point.copy(column = point.column + if (end) 1 else -1) else point
        }
        return TerminalSelectionRange(edge(range.start, false), edge(range.end, true))
    }

    private fun word(point: TerminalCellPosition): TerminalSelectionRange {
        var start = if (line(point.line).width(point.column) == 0) point.copy(column = point.column - 1) else point
        if (line(start.line).isSpacerHead(start.column)) start = next(start) ?: previous(start) ?: start
        var last = start
        val kind = wordKind(start)
        // Punctuation is selected one glyph at a time; path/URL characters stay in words.
        if (kind != 2) {
            while (true) { val previous = previous(start) ?: break; if (wordKind(previous) != kind) break; start = previous }
            while (true) { val next = next(last) ?: break; if (wordKind(next) != kind) break; last = next }
        }
        val row = line(last.line)
        return TerminalSelectionRange(start, last.copy(column = last.column + maxOf(1, row.width(last.column))))
    }
    private fun wordKind(point: TerminalCellPosition): Int {
        val cp = line(point.line).codePoint(point.column)
        return when {
            cp <= 0xffff && cp.toChar().isWhitespace() -> 0
            cp <= 0xffff && cp.toChar() in "()[]{}<>\"'`|,;" -> 2
            else -> 1
        }
    }
    private fun previous(point: TerminalCellPosition): TerminalCellPosition? {
        var y = point.line; var x = point.column - 1
        while (true) {
            if (x < 0) {
                if (y == firstLine() || !line(y - 1).wrapped) return null
                y--; x = line(y).columns - 1
            }
            if (line(y).width(x) == 0) x--
            if (!line(y).isSpacerHead(x)) return TerminalCellPosition(y, x)
            x--
        }
    }
    private fun next(point: TerminalCellPosition): TerminalCellPosition? {
        var y = point.line; var x = point.column + maxOf(1, line(y).width(point.column))
        while (true) {
            if (x >= line(y).columns) {
                if (y == lastLine() || !line(y).wrapped) return null
                y++; x = 0
            }
            if (!line(y).isSpacerHead(x)) return TerminalCellPosition(y, x)
            x++
        }
    }
}
