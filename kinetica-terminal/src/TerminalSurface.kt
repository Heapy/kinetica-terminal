package io.heapy.kinetica.terminal


public data class TerminalTheme(
    val foreground: Int = 0xd4d4d4,
    val background: Int = 0x1e1e1e,
    val cursor: Int = 0xeeeeee,
    val selection: Int = 0x264f78,
) {
    init { require(listOf(foreground, background, cursor, selection).all { it in 0..0xffffff }) }
}

/** Text coordinates are the top-left of a cell; each platform chooses its font baseline. */
public interface TerminalPainter {
    public fun fill(x: Double, y: Double, width: Double, height: Double, color: Int)
    public fun text(value: String, x: Double, y: Double, color: Int, style: Int)
    /** Draw over text; GPU backends keep this in the final overlay batch. */
    public fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
        fill(x, y, width, height, color)
    }
}

/** Cell boundaries are preserved for atlas rendering, including wide and combined glyphs. */
internal interface TerminalGlyphPainter : TerminalPainter {
    fun beginRow(row: Int)
    fun glyph(value: String, x: Double, y: Double, columns: Int, color: Int, style: Int)
}

/** Per-view damage, scrolling, and selection. Several surfaces may observe the same session. */
public class TerminalViewport(
    public val session: TerminalSession,
    private val invalidate: () -> Unit,
    private val scheduler: TerminalScheduler? = null,
) {
    private var frozen: FrozenFrame? = null
    public val theme: TerminalTheme get() = frozen?.theme ?: session.theme
    public val columns: Int get() = frozen?.lines?.first()?.columns ?: session.columns
    public val rows: Int get() = frozen?.lines?.size ?: session.rows
    public val cursorColumn: Int get() = frozen?.cursorColumn ?: session.cursorColumn
    public val cursorRow: Int get() = frozen?.cursorRow ?: session.cursorRow
    private val cursorVisible: Boolean get() = frozen?.cursorVisible ?: (session.cursorVisible && scrollOffset == 0)
    private val cursorStyle: TerminalCursorStyle get() = frozen?.cursorStyle ?: session.cursorStyle
    private val firstLine: Int get() = frozen?.firstLine ?: (session.historySize - scrollOffset)
    private var focused = true
    private var cursorOn = true
    private var blinkTimer: TerminalDisposable? = null
    private var holdTimer: TerminalDisposable? = null
    private var resizeHeld = false
    private var resizingSession = false
    private var resizeDeadline: TerminalDisposable? = null
    private var resizeQuietTimer: TerminalDisposable? = null
    public var scrollOffset: Int = 0
        private set
    private var firstDirty = 0
    private var lastDirty = session.rows - 1
    public val firstDirtyRow: Int get() = firstDirty
    public val lastDirtyRow: Int get() = lastDirty
    private var scrolledLines = session.scrolledLines
    private var screenEpoch = session.screenEpoch
    private var selectionEpoch = session.selectionEpoch
    private var scrollAnchor: ScrollAnchor? = null
    private val liveSelection = TerminalSelection({ 0 }, { session.lineCount - 1 }, session::line, session::indexOfLine,
        { session.selectionEpoch }, { session.lineOrderEpoch })
    private val activeSelection: TerminalSelection get() = frozen?.selection ?: liveSelection
    private var draggingSelection = false
    private var dragColumn = 0
    private var dragRow = 0
    private var dragTimer: TerminalDisposable? = null
    private var disposed = false
    private val observation = session.observe { change ->
        if (screenEpoch != session.screenEpoch) {
            scrollOffset = 0; scrollAnchor = null
        } else if (scrollOffset > 0) {
            val anchor = scrollAnchor
            val index = if (anchor != null && anchor.row.recycleEpoch == anchor.recycle) session.indexOfLine(anchor.row) else -1
            scrollOffset = when {
                index >= 0 -> (session.historySize - index).coerceIn(0, session.historySize)
                selectionEpoch != session.selectionEpoch -> // Reflow created new rows: retain the existing approximate offset.
                    (scrollOffset.toLong() + session.scrolledLines - scrolledLines).coerceIn(0, session.historySize.toLong()).toInt()
                else -> session.historySize // The anchor was pruned; show the oldest retained row.
            }
            rememberScrollAnchor()
        }
        scrolledLines = session.scrolledLines; screenEpoch = session.screenEpoch; selectionEpoch = session.selectionEpoch
        val hadSelection = liveSelection.hasAnchor
        if (liveSelection.range() == null && frozen == null) cancelSelectionDrag()
        resetBlink()
        if (change.full || scrollOffset > 0 || hadSelection) markAll()
        else { firstDirty = minOf(firstDirty, change.firstRow); lastDirty = maxOf(lastDirty, change.lastRow); invalidate() }
        if (resizeHeld && !resizingSession && !session.synchronizedOutput) {
            resizeQuietTimer?.dispose()
            resizeQuietTimer = scheduler?.schedule(8) { releaseResizeFrame() }
        }
    }
    private val holdObservation = session.onRenderHold { renderHold(it) }

    init { if (session.synchronizedOutput) renderHold(true, capture = false) else resetBlink() }

    private fun renderHold(held: Boolean, capture: Boolean = true) {
        if (disposed) return
        holdTimer?.dispose(); holdTimer = null
        if (held) {
            // A TUI may start its atomic redraw after the grid has already reflowed.
            // Keep the pre-resize frame instead of snapshotting that intermediate grid.
            if (!resizeHeld) captureFrame(capture)
            cancelResizeTimers()
            holdTimer = scheduler?.schedule(TERMINAL_SYNC_TIMEOUT_MILLIS) { holdTimer = null; session.flushSynchronizedOutput() }
        } else {
            cancelResizeTimers(); resizeHeld = false
            cancelSelectionDrag(); frozen?.selection?.clear()
            frozen = null
            resetBlink()
        }
        markAll()
    }

    private fun captureFrame(capture: Boolean = true) {
        cancelSelectionDrag()
        val colors = session.colors.snapshot()
        val first = session.historySize - scrollOffset
        val selected = liveSelection.range()
        val selectedText = if (selected != null) liveSelection.text() else null
        // A newly mounted view has no completed frame to retain.
        val lines = Array(session.rows) {
            if (capture) session.line(first + it).snapshot(colors) else TerminalLine(session.columns, colors)
        }
        val selection = TerminalSelection({ first }, { first + lines.lastIndex }, { lines[it - first] }, { row ->
            val index = lines.indexOfFirst { it === row }; if (index < 0) -1 else first + index
        })
        frozen = FrozenFrame(lines, first, session.theme, session.cursorColumn, session.cursorRow,
            capture && session.cursorVisible && scrollOffset == 0, session.cursorStyle, selection, selected, selectedText)
        blinkTimer?.dispose(); blinkTimer = null
    }

    /** Briefly retain the completed frame while the PTY responds to a live grid resize. */
    internal fun resizeSession(columns: Int, rows: Int) {
        if (disposed || (columns == session.columns && rows == session.rows)) return
        check(!session.synchronizedOutput) { "Finish synchronized output before resizing the grid" }
        if (scheduler == null) { session.resize(columns, rows); return }
        if (!resizeHeld) { captureFrame(); resizeHeld = true }
        resizeQuietTimer?.dispose(); resizeQuietTimer = null
        // Repeated resize events cannot extend this deadline and freeze a silent program.
        if (resizeDeadline == null) resizeDeadline = scheduler.schedule(50) { releaseResizeFrame() }
        resizingSession = true
        try { session.resize(columns, rows) } finally { resizingSession = false }
    }

    private fun cancelResizeTimers() {
        resizeDeadline?.dispose(); resizeQuietTimer?.dispose()
        resizeDeadline = null; resizeQuietTimer = null
    }

    private fun releaseResizeFrame() {
        cancelResizeTimers()
        if (disposed || !resizeHeld || session.synchronizedOutput) return
        resizeHeld = false
        cancelSelectionDrag(); frozen?.selection?.clear(); frozen = null
        resetBlink(); markAll()
    }

    public fun setFocused(focused: Boolean) {
        if (this.focused == focused) return
        if (!focused) cancelSelectionDrag()
        this.focused = focused; resetBlink(); markAll()
    }

    private fun resetBlink() {
        blinkTimer?.dispose(); blinkTimer = null; cursorOn = true
        scheduleBlink()
    }

    private fun scheduleBlink() {
        if (disposed || frozen != null || !focused || !session.cursorBlink || !session.cursorVisible || scrollOffset != 0) return
        blinkTimer = scheduler?.schedule(TERMINAL_CURSOR_BLINK_MILLIS) {
            blinkTimer = null; cursorOn = !cursorOn
            firstDirty = minOf(firstDirty, cursorRow); lastDirty = maxOf(lastDirty, cursorRow); invalidate()
            scheduleBlink()
        }
    }

    private fun visibleLine(row: Int): TerminalLine = frozen?.lines?.get(row) ?: session.line(firstLine + row)
    public fun visibleText(): String = (0 until rows).joinToString("\n") { visibleLine(it).text() }

    public fun dispose() {
        if (!disposed) {
            disposed = true; observation.dispose(); holdObservation.dispose()
            cancelResizeTimers(); resizeHeld = false
            cancelSelectionDrag(); liveSelection.clear(); frozen?.selection?.clear(); scrollAnchor = null
            blinkTimer?.dispose(); holdTimer?.dispose(); blinkTimer = null; holdTimer = null; frozen = null
        }
    }
    public fun markAll() { firstDirty = 0; lastDirty = rows - 1; if (!disposed) invalidate() }
    public fun scroll(lines: Int) {
        if (disposed || frozen != null) return
        scrollOffset = (scrollOffset.toLong() + lines).coerceIn(0, session.historySize.toLong()).toInt()
        rememberScrollAnchor(); resetBlink(); markAll()
    }
    public fun followOutput() {
        if (scrollOffset != 0) { scrollOffset = 0; scrollAnchor = null; resetBlink(); markAll() }
    }
    public fun beginSelection(column: Int, row: Int, mode: TerminalSelectionMode = TerminalSelectionMode.CHARACTER) {
        if (disposed) return
        cancelSelectionDrag()
        frozen?.let { liveSelection.clear(); it.inheritedRange = null; it.inheritedText = null }
        activeSelection.begin(position(column, row), mode)
        draggingSelection = true; dragColumn = column; dragRow = row
        markAll()
    }
    public fun extendSelection(column: Int, row: Int) {
        if (disposed || !draggingSelection) return
        dragColumn = column; dragRow = row
        activeSelection.extend(position(column, row)); updateDragScroll(); markAll()
    }
    /** Complete a local drag while retaining its text selection. */
    public fun endSelection(column: Int, row: Int) { extendSelection(column, row); cancelSelectionDrag() }
    /** Pointer cancellation/focus loss ends autoscrolling but leaves the completed selection. */
    public fun cancelSelectionDrag() { draggingSelection = false; dragTimer?.dispose(); dragTimer = null }
    public fun clearSelection() {
        cancelSelectionDrag(); liveSelection.clear()
        frozen?.let { it.selection.clear(); it.inheritedRange = null; it.inheritedText = null }
        markAll()
    }
    public fun selectedText(): String = frozen?.inheritedText ?: activeSelection.text()

    /** Select retained output without synthesizing a mouse drag or starting autoscroll. */
    public fun selectAll() {
        if (disposed) return
        cancelSelectionDrag()
        val first = frozen?.firstLine ?: 0
        val last = frozen?.let { it.firstLine + it.lines.lastIndex } ?: (session.lineCount - 1)
        frozen?.let { liveSelection.clear(); it.inheritedRange = null; it.inheritedText = null }
        activeSelection.begin(TerminalCellPosition(first, 0), TerminalSelectionMode.CHARACTER)
        activeSelection.extend(TerminalCellPosition(last, columns))
        markAll()
    }

    public fun scrollTo(offset: Int) { scroll(offset.coerceIn(0, session.historySize) - scrollOffset) }

    /** Reveal a search result in retained output. A held atomic frame stays untouched. */
    public fun selectMatch(match: TerminalSearchMatch): Boolean {
        if (disposed || frozen != null || match.startLine !in 0 until session.lineCount ||
            match.endLine !in match.startLine until session.lineCount) return false
        cancelSelectionDrag()
        liveSelection.begin(TerminalCellPosition(match.startLine, match.startColumn), TerminalSelectionMode.CHARACTER)
        liveSelection.extend(TerminalCellPosition(match.endLine, match.endColumn))
        scrollTo((session.historySize - match.startLine + rows / 2).coerceIn(0, session.historySize))
        markAll()
        return true
    }

    public fun hyperlinkAt(column: Int, row: Int): String? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        return if (frozen == null) session.hyperlinkAt(firstLine + row, column)
        else terminalHyperlinkAt(row, column, 0, rows - 1, ::visibleLine)
    }

    private fun rememberScrollAnchor() {
        scrollAnchor = if (scrollOffset > 0) session.line(session.historySize - scrollOffset).let { ScrollAnchor(it, it.recycleEpoch) } else null
    }
    private fun dragScrollAmount(): Int = when {
        dragRow < 0 -> minOf(10L, -dragRow.toLong()).toInt()
        dragRow >= rows -> -minOf(10L, dragRow.toLong() - rows + 1).toInt()
        else -> 0
    }
    private fun updateDragScroll() {
        val amount = dragScrollAmount()
        if (!draggingSelection || frozen != null || amount == 0 || amount > 0 && scrollOffset == session.historySize || amount < 0 && scrollOffset == 0) {
            dragTimer?.dispose(); dragTimer = null; return
        }
        if (dragTimer != null) return
        dragTimer = scheduler?.schedule(50) {
            dragTimer = null
            if (!disposed && draggingSelection && frozen == null) {
                scrollOffset = (scrollOffset + dragScrollAmount()).coerceIn(0, session.historySize)
                rememberScrollAnchor(); resetBlink()
                activeSelection.extend(position(dragColumn, dragRow))
                if (!activeSelection.hasAnchor) cancelSelectionDrag() else updateDragScroll()
                markAll()
            }
        }
    }

    /** Calls are proportional to damaged visible rows and contiguous style runs. */
    public fun paint(painter: TerminalPainter, cellWidth: Double, cellHeight: Double, force: Boolean = false,
        firstRow: Int = 0, lastRow: Int = rows - 1) {
        require(cellWidth > 0 && cellHeight > 0)
        if (disposed) return
        val first = maxOf(firstRow, if (force) 0 else firstDirty)
        val last = minOf(lastRow, if (force) rows - 1 else lastDirty)
        firstDirty = rows; lastDirty = -1
        val selection = selection()
        for (y in first..last.coerceAtMost(rows - 1)) {
            (painter as? TerminalGlyphPainter)?.beginRow(y)
            val index = firstLine + y
            val line = visibleLine(y)
            val cursor = cursorVisible && (cursorOn || !focused) && y == cursorRow
            val cursorX = if (line.width(cursorColumn) == 0) (cursorColumn - 1).coerceAtLeast(0) else cursorColumn
            val block = cursor && focused && cursorStyle == TerminalCursorStyle.BLOCK
            var x = 0
            while (x < columns) {
                val start = x
                val flags = line.style(x)
                var foreground = line.foreground(x).let { if (it < 0) theme.foreground else it }
                var background = line.background(x).let { if (it < 0) theme.background else it }
                if (flags and TerminalStyle.INVERSE != 0) { val swap = foreground; foreground = background; background = swap }
                if (flags and TerminalStyle.FAINT != 0) foreground = dim(foreground)
                val selected = selected(selection, index, x)
                if (selected) background = theme.selection
                if (block && x == cursorX) { foreground = theme.background; background = theme.cursor }
                // Group only ordinary one-column cells. Wide/combined characters get exact cell
                // placement even when the system fallback font has different glyph advances.
                val blockElement = !line.isCombined(x) && isTerminalBlockElement(line.codePoint(x))
                val ordinary = painter !is TerminalGlyphPainter && !blockElement && line.width(x) == 1 && !line.isCombined(x) && line.codePoint(x) <= 0xffff
                val text = StringBuilder()
                do {
                    if (line.width(x) > 0) line.appendTextTo(text, x)
                    x += if (line.width(x) == 2) 2 else 1
                } while (ordinary && x < columns && !(block && (start == cursorX || x == cursorX)) &&
                    line.width(x) == 1 && !line.isCombined(x) && line.codePoint(x) <= 0xffff &&
                    !isTerminalBlockElement(line.codePoint(x)) &&
                    line.style(x) == flags && line.foreground(x) == line.foreground(start) &&
                    line.background(x) == line.background(start) && line.underlineColor(x) == line.underlineColor(start) &&
                    selected(selection, index, x) == selected)
                painter.fill(start * cellWidth, y * cellHeight, (x - start) * cellWidth, cellHeight, background)
                if (flags and TerminalStyle.HIDDEN == 0) {
                    if (blockElement) painter.blockElement(line.codePoint(start), start * cellWidth, y * cellHeight,
                        (x - start) * cellWidth, cellHeight, foreground)
                    else if (painter is TerminalGlyphPainter) painter.glyph(text.toString(), start * cellWidth, y * cellHeight, x - start, foreground, flags)
                    else painter.text(text.toString(), start * cellWidth, y * cellHeight, foreground, flags)
                }
                if (flags and TerminalStyle.HIDDEN == 0) {
                    val underlineColor = line.underlineColor(start).let { if (it < 0) foreground else it }
                    val left = start * cellWidth
                    val width = (x - start) * cellWidth
                    val baseline = (y + 1) * cellHeight - 2
                    when {
                        flags and TerminalStyle.DOUBLE_UNDERLINE != 0 -> {
                            painter.decoration(left, baseline - 2, width, 1.0, underlineColor)
                            painter.decoration(left, baseline, width, 1.0, underlineColor)
                        }
                        flags and TerminalStyle.UNDERCURL != 0 -> {
                            var offset = 0.0
                            while (offset < width) {
                                painter.decoration(left + offset, baseline - if (offset.toInt() % 4 < 2) 1 else 0,
                                    minOf(1.0, width - offset), 1.0, underlineColor)
                                offset++
                            }
                        }
                        flags and (TerminalStyle.DOTTED_UNDERLINE or TerminalStyle.DASHED_UNDERLINE) != 0 -> {
                            val dash = if (flags and TerminalStyle.DOTTED_UNDERLINE != 0) 1.0 else 4.0
                            var offset = 0.0
                            while (offset < width) {
                                painter.decoration(left + offset, baseline, minOf(dash, width - offset), 1.0, underlineColor)
                                offset += dash + 2.0
                            }
                        }
                        flags and TerminalStyle.UNDERLINE != 0 -> painter.decoration(left, baseline, width, 1.0, underlineColor)
                    }
                    if (flags and TerminalStyle.STRIKE != 0) painter.decoration(left, (y + 0.5) * cellHeight, width, 1.0, foreground)
                }
            }
            if (cursor) {
                val left = cursorX * cellWidth
                val top = y * cellHeight
                val width = maxOf(1, line.width(cursorX)) * cellWidth
                if (!focused) {
                    painter.decoration(left, top, width, 1.0, theme.cursor)
                    painter.decoration(left, top + cellHeight - 1, width, 1.0, theme.cursor)
                    painter.decoration(left, top, 1.0, cellHeight, theme.cursor)
                    painter.decoration(left + width - 1, top, 1.0, cellHeight, theme.cursor)
                } else when (cursorStyle) {
                    TerminalCursorStyle.UNDERLINE -> painter.decoration(left, top + cellHeight - 2, width, 2.0, theme.cursor)
                    TerminalCursorStyle.BAR -> painter.decoration(left, top, minOf(2.0, cellWidth), cellHeight, theme.cursor)
                    TerminalCursorStyle.BLOCK -> Unit // Cell colors above keep the glyph readable.
                }
            }
        }
    }

    private fun position(column: Int, row: Int) = TerminalCellPosition(
        firstLine + row.coerceIn(0, rows - 1), when { row < 0 -> 0; row >= rows -> columns; else -> column.coerceIn(0, columns) })
    private fun selection(): TerminalSelectionRange? = frozen?.inheritedRange ?: activeSelection.range()
    private fun selected(selection: TerminalSelectionRange?, row: Int, column: Int): Boolean {
        if (selection == null) return false
        val p = TerminalCellPosition(row, column)
        return p >= selection.start && p < selection.end
    }
    private fun dim(rgb: Int): Int = (((rgb shr 16 and 255) / 2) shl 16) or (((rgb shr 8 and 255) / 2) shl 8) or ((rgb and 255) / 2)
    private data class ScrollAnchor(val row: TerminalLine, val recycle: Long)
    private data class FrozenFrame(val lines: Array<TerminalLine>, val firstLine: Int, val theme: TerminalTheme,
        val cursorColumn: Int, val cursorRow: Int, val cursorVisible: Boolean, val cursorStyle: TerminalCursorStyle,
        val selection: TerminalSelection, var inheritedRange: TerminalSelectionRange?, var inheritedText: String?)
}
