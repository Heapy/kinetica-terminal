// Selection gestures and plain-text formatting adapted from Ghostty (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** LINE selects semantic lines; OUTPUT selects command output identified by OSC 133. */
public enum class TerminalSelectionMode { CHARACTER, WORD, LINE, OUTPUT }

internal data class TerminalCellPosition(val line: Int, val column: Int) : Comparable<TerminalCellPosition> {
    override fun compareTo(other: TerminalCellPosition): Int =
        if (line != other.line) line.compareTo(other.line) else column.compareTo(other.column)
}

internal data class TerminalSelectionRange(val start: TerminalCellPosition, val end: TerminalCellPosition,
    val rectangle: Boolean = false)

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
    private val wordBoundaries: () -> IntArray = { terminalDefaultWordBoundaries },
    private val detectedLinksEnabled: () -> Boolean = { true },
) {
    private data class Anchor(val row: TerminalLine, val column: Int, val recycle: Long = row.recycleEpoch)
    private inner class Watch(val row: TerminalLine) {
        var first = 0
        var end = row.columns + 1
        var boundary = false
        val recycle = row.recycleEpoch
        val subscription = row.observeText { from, to ->
            if (from < end && to > first || boundary && from <= row.columns && to > row.columns) invalid = true
        }
    }
    private val watches = ArrayDeque<Watch>()
    private var watchFirst = 0
    private var anchorStart: Anchor? = null
    private var anchorEnd: Anchor? = null
    private var clickAnchor: Anchor? = null
    private var bounds: TerminalSelectionRange? = null
    private var reset = resetEpoch()
    private var order = orderEpoch()
    private var invalid = false
    private var mode = TerminalSelectionMode.CHARACTER
    private var pointerX: Double? = null
    private var pointerDragged = false
    private var preserveWrittenWhitespace = false
    val hasAnchor: Boolean get() = bounds != null

    fun clear() {
        watches.forEach { it.subscription.dispose() }; watches.clear()
        bounds = null; anchorStart = null; anchorEnd = null; clickAnchor = null; invalid = false
        reset = resetEpoch(); order = orderEpoch()
        pointerX = null
        pointerDragged = false
        preserveWrittenWhitespace = false
    }

    fun range(): TerminalSelectionRange? { reconcile(); return bounds }

    internal data class Snapshot(val range: TerminalSelectionRange, val start: TerminalCellPosition?,
        val end: TerminalCellPosition?, val click: TerminalCellPosition?, val mode: TerminalSelectionMode,
        val pointerX: Double?, val dragged: Boolean, val preserveWhitespace: Boolean) {
        private val positions get() = listOfNotNull(range.start, range.end, start, end, click)
        val firstLine: Int get() = positions.minOf { it.line }
        val lastLine: Int get() = positions.maxOf { it.line }
    }

    /** Scalar gesture state; restoration rebinds every anchor to the destination's rows. */
    fun snapshot(): Snapshot? {
        val range = range() ?: return null
        return Snapshot(range, anchorStart?.position(), anchorEnd?.position(), clickAnchor?.position(),
            mode, pointerX, pointerDragged, preserveWrittenWhitespace)
    }

    fun restore(snapshot: Snapshot) {
        clear()
        mode = snapshot.mode; pointerX = snapshot.pointerX; pointerDragged = snapshot.dragged
        preserveWrittenWhitespace = snapshot.preserveWhitespace
        anchorStart = snapshot.start?.let(::anchor); anchorEnd = snapshot.end?.let(::anchor)
        clickAnchor = snapshot.click?.let(::anchor)
        setRange(snapshot.range)
    }

    fun selectAll(first: Int = firstLine(), last: Int = lastLine()): Boolean {
        val range = terminalSelectAllRange(first, last, line) ?: return false
        begin(range.start, TerminalSelectionMode.CHARACTER)
        extend(range.end)
        preserveWrittenWhitespace = true
        return true
    }

    fun selectContextWord(position: TerminalCellPosition, linkModifiers: Boolean, osc8Enabled: Boolean): Boolean {
        val point = clamp(position)
        val row = line(point.line)
        val x = if (row.width(point.column) == 0) (point.column - 1).coerceAtLeast(0) else point.column
        // Surface.linkAtPos gives OSC 8 precedence and selects only the clicked cell.
        val linkedCell = if (linkModifiers && osc8Enabled && row.hyperlink(x) != null)
            TerminalSelectionRange(point.copy(column = x), point.copy(column = x + row.width(x))) else null
        val link = if (linkModifiers && linkedCell == null && detectedLinksEnabled())
            terminalDetectedLinkAt(point.line, x, firstLine(), lastLine(), line) else null
        val range = linkedCell ?: link?.range ?: terminalWordRange(point, firstLine(), lastLine(), line, wordBoundaries()) ?: return false
        clear()
        mode = TerminalSelectionMode.WORD
        setRange(normalize(range))
        return true
    }

    fun beginPointer(position: TerminalCellPosition, mode: TerminalSelectionMode, x: Double,
        origin: TerminalCellPosition = position) {
        begin(clamp(origin), mode)
        pointerX = x
        if (position != origin) {
            val point = clamp(position)
            val link = if (mode == TerminalSelectionMode.WORD && detectedLinksEnabled())
                terminalDetectedLinkAt(point.line, point.column, firstLine(), lastLine(), line) else null
            setRange(link?.range ?: unit(point)?.let(::normalize) ?: TerminalSelectionRange(point, point))
        }
    }

    fun isPointerOrigin(position: TerminalCellPosition): Boolean {
        reconcile()
        return pointerX != null && clickAnchor?.position() == clamp(position)
    }
    fun isPointerClick(position: TerminalCellPosition): Boolean = isPointerOrigin(position) && !pointerDragged

    fun canContinuePointer(): Boolean {
        reconcile()
        return pointerX != null && clickAnchor?.position() != null && bounds?.let { it.start != it.end } == true
    }

    /** True once the pointer moves cells or crosses the same-cell selection threshold. */
    fun extendPointer(position: TerminalCellPosition, x: Double, cellWidth: Int, rectangle: Boolean): Boolean {
        reconcile()
        val click = clickAnchor?.position() ?: return false
        val focus = clamp(position)
        if (mode != TerminalSelectionMode.CHARACTER) {
            extend(focus)
            if (focus != click) pointerDragged = true
            return pointerDragged
        }
        val range = terminalPointerSelectionRange(click, focus, pointerX ?: return false, x,
            line(focus.line).columns, cellWidth, firstLine(), lastLine(), rectangle)
        setRange(if (range == null) TerminalSelectionRange(click, click) else if (rectangle) range else normalize(range))
        if (focus != click || range != null) pointerDragged = true
        return pointerDragged
    }

    fun begin(position: TerminalCellPosition, mode: TerminalSelectionMode) {
        clear(); this.mode = mode
        val unit = unit(position)?.let(::normalize)
        // A blank line has no initial selection, but remains a valid drag origin.
        val y = position.line.coerceIn(firstLine(), lastLine())
        val point = clamp(position)
        clickAnchor = anchor(point)
        val origin = unit ?: if (mode == TerminalSelectionMode.LINE) terminalSemanticLineRange(y, point.column, firstLine(), lastLine(), line)
            else TerminalSelectionRange(point, point)
        anchorStart = anchor(origin.start); anchorEnd = anchor(origin.end)
        val x = position.column.coerceIn(0, line(y).columns - 1)
        val link = if (mode == TerminalSelectionMode.WORD && detectedLinksEnabled()) terminalDetectedLinkAt(y, x, firstLine(), lastLine(), line) else null
        setRange(link?.range ?: unit ?: TerminalSelectionRange(origin.start, origin.start))
    }

    fun extend(position: TerminalCellPosition, rectangle: Boolean = false) {
        reconcile()
        if (mode == TerminalSelectionMode.CHARACTER && rectangle) {
            val origin = anchorStart?.position() ?: return
            val y = position.line.coerceIn(firstLine(), lastLine())
            val x = position.column.coerceIn(0, line(y).columns)
            setRange(TerminalSelectionRange(
                TerminalCellPosition(minOf(origin.line, y), minOf(origin.column, x)),
                TerminalCellPosition(maxOf(origin.line, y), maxOf(origin.column, x)), rectangle = true))
            return
        }
        if (mode == TerminalSelectionMode.OUTPUT) {
            val origin = clickAnchor?.position() ?: return
            val initial = terminalOutputRange(origin, firstLine(), lastLine(), line) ?: return
            val focus = clamp(position)
            val current = terminalOutputRange(focus, firstLine(), lastLine(), line)
            setRange(normalize(when {
                current == null -> initial
                focus < origin -> TerminalSelectionRange(current.start, initial.end)
                else -> TerminalSelectionRange(initial.start, current.end)
            }))
            return
        }
        if (mode == TerminalSelectionMode.WORD) {
            val origin = clickAnchor?.position() ?: return
            val focus = clamp(position)
            val boundaries = wordBoundaries()
            val startWord = terminalWordBetween(origin, focus, firstLine(), lastLine(), line, boundaries) ?: return
            val focusWord = terminalWordBetween(focus, origin, firstLine(), lastLine(), line, boundaries) ?: return
            setRange(normalize(if (focus < origin) TerminalSelectionRange(focusWord.start, startWord.end)
                else TerminalSelectionRange(startWord.start, focusWord.end)))
            return
        }
        val start = anchorStart?.position() ?: return
        val end = anchorEnd?.position() ?: return
        val focus = unit(position) ?: return
        setRange(normalize(TerminalSelectionRange(minOf(start, focus.start), maxOf(end, focus.end))))
    }

    /** Force click uses the original press's word, without the double-click URL override. */
    fun deepPress(): Boolean {
        reconcile()
        val origin = clickAnchor?.position() ?: return false
        val word = terminalWordRange(origin, firstLine(), lastLine(), line, wordBoundaries())
        clickAnchor = null; anchorStart = null; anchorEnd = null
        if (word != null) { mode = TerminalSelectionMode.WORD; setRange(normalize(word)) }
        // An unwritten cell produces no replacement selection, but still consumes the gesture.
        return true
    }

    fun copyText(trimTrailingSpaces: Boolean): String = range()?.takeIf { it.start != it.end }?.let {
        terminalSelectionText(it, line, trimTrailingSpaces)
    }.orEmpty()

    fun copyContent(trimTrailingSpaces: Boolean): TerminalCopyContent = range()?.takeIf { it.start != it.end }?.let {
        terminalSelectionCopyContent(it, line, trimTrailingSpaces)
    } ?: TerminalCopyContent.EMPTY

    fun text(): String {
        val range = range() ?: return ""
        if (range.start == range.end) return ""
        if (preserveWrittenWhitespace || pointerX != null || range.rectangle || mode != TerminalSelectionMode.CHARACTER) return terminalSelectionText(range, line)
        return buildString {
            for (y in range.start.line..range.end.line) {
                val row = line(y)
                row.readPreservingCompression {
                    val from = if (y == range.start.line) range.start.column else 0
                    val to = if (y == range.end.line) range.end.column else row.columns
                    val value = buildString {
                        for (x in from until to) if (row.width(x) != 0 && !row.isSpacerHead(x)) row.appendTextTo(this, x)
                    }
                    append(if (to == row.columns && !row.wrapped) value.trimEnd(' ') else value)
                    if (y < range.end.line && !row.wrapped) append('\n')
                }
            }
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
        bounds = old.copy(start = old.start.copy(line = old.start.line + delta), end = old.end.copy(line = old.end.line + delta))
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
        if (range.rectangle || bounds?.rectangle == true) {
            for ((offset, watch) in watches.withIndex()) {
                val row = watch.row
                val from = range.start.column
                val to = range.end.column
                watch.first = if (!range.rectangle) 0 else
                    if (from < row.columns && row.width(from) == 0) from - 1 else from
                watch.end = if (!range.rectangle) row.columns + 1 else
                    if (to < row.columns && row.width(to) == 0) to + 1 else to
                watch.boundary = range.rectangle && offset < watches.size - 1
            }
        }
        if (!range.rectangle) {
            watches.first().first = range.start.column
            // Include the hard/soft line boundary only when copying it matters.
            watches.last().end = range.end.column
        }
        bounds = range
    }

    private fun unit(position: TerminalCellPosition): TerminalSelectionRange? {
        val y = position.line.coerceIn(firstLine(), lastLine())
        val row = line(y)
        val x = position.column.coerceIn(0, row.columns)
        val point = TerminalCellPosition(y, x)
        return when (mode) {
            TerminalSelectionMode.CHARACTER -> TerminalSelectionRange(point, point)
            TerminalSelectionMode.WORD -> terminalWordRange(TerminalCellPosition(y, x.coerceAtMost(row.columns - 1)), firstLine(), lastLine(), line, wordBoundaries())
            TerminalSelectionMode.LINE -> terminalSelectedLineRange(y, x.coerceAtMost(row.columns - 1), firstLine(), lastLine(), line)
            TerminalSelectionMode.OUTPUT -> terminalOutputRange(point.copy(column = x.coerceAtMost(row.columns - 1)), firstLine(), lastLine(), line)
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

    private fun clamp(position: TerminalCellPosition): TerminalCellPosition {
        val y = position.line.coerceIn(firstLine(), lastLine())
        return TerminalCellPosition(y, position.column.coerceIn(0, line(y).columns - 1))
    }
}

/** Screen.selectAll trims only NUL, ASCII space and tab at the outer cell boundaries. */
internal fun terminalSelectAllRange(first: Int, last: Int, line: (Int) -> TerminalLine): TerminalSelectionRange? {
    fun edge(reverse: Boolean): TerminalCellPosition? {
        for (y in if (reverse) last downTo first else first..last) {
            val row = line(y)
            for (x in if (reverse) row.columns - 1 downTo 0 else 0 until row.columns) {
                val code = row.codePoint(x)
                if (!row.hasText(x) || row.isSpacerHead(x) || code == 0 || code == 32 || code == 9) continue
                return TerminalCellPosition(y, x + if (reverse) row.width(x) else 0)
            }
        }
        return null
    }
    val start = edge(false) ?: return null
    return TerminalSelectionRange(start, checkNotNull(edge(true)))
}

// ScreenFormatter's plain, unwrapped output. Written spaces survive unless trimming is
// requested; trailing empty cells/rows do not. No newline is synthesized after selection.
// cellRange reports (row, column, cell width, UTF-16 start, UTF-16 end) for emitted
// content. A hard newline has width zero at its source row's right boundary.
internal fun terminalSelectionText(range: TerminalSelectionRange, line: (Int) -> TerminalLine,
    trimTrailingSpaces: Boolean = false,
    cellRange: ((Int, Int, Int, Int, Int) -> Unit)? = null,
    cellText: ((Int, Int) -> Unit)? = null): String = buildString {
    if (range.rectangle && range.start.column == range.end.column) return@buildString
    var blankRows = 0
    var firstBlankRow = range.start.line
    var blankCells = 0
    val blankPositions = if (cellRange == null) null else ArrayList<Pair<Int, Int>>()
    for (y in range.start.line..range.end.line) {
        val row = line(y)
        row.readPreservingCompression {
            var from = if (range.rectangle || y == range.start.line) range.start.column else 0
            val to = if (range.rectangle || y == range.end.line) range.end.column else row.columns
            if ((range.rectangle || y == range.start.line) && row.isSpacerHead(from)) return@readPreservingCompression
            if (from < row.columns && row.width(from) == 0) from--
            if ((from until to).none { row.hasText(it) && !row.isSpacerHead(it) }) {
                if (blankRows == 0) firstBlankRow = y
                blankRows++; return@readPreservingCompression
            }
            repeat(blankRows) {
                val source = firstBlankRow + it
                cellRange?.invoke(source, line(source).columns, 0, length, length + 1)
                append('\n')
            }
            blankRows = if (row.wrapped) 0 else 1; firstBlankRow = y
            if (y == range.start.line || !line(y - 1).wrapped) { blankCells = 0; blankPositions?.clear() }
            for (x in from until to) {
                if (row.width(x) == 0 || row.isSpacerHead(x)) continue
                if (!row.hasText(x) || trimTrailingSpaces && row.codePoint(x) == ' '.code) {
                    blankCells++; blankPositions?.add(y to x); continue
                }
                repeat(blankCells) {
                    blankPositions?.get(it)?.let { (source, column) -> cellRange?.invoke(source, column, 1, length, length + 1) }
                    append(' ')
                }
                blankCells = 0; blankPositions?.clear()
                val start = length
                row.appendTextTo(this, x)
                cellRange?.invoke(y, x, row.width(x), start, length)
                if (length > start) cellText?.invoke(start, length)
            }
        }
    }
}
