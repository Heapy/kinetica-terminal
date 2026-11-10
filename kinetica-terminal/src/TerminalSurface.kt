package io.heapy.kinetica.terminal

import kotlin.time.TimeSource

// Views sharing a session must use the same epoch, including views mounted later.
private val graphicsClockOrigin = TimeSource.Monotonic.markNow()
private fun graphicsNowMillis(): Long = graphicsClockOrigin.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)

public enum class TerminalColorScheme(internal val report: Int) { DARK(1), LIGHT(2) }

/** Per-cell physical pixels, after display scaling; zero means unknown. */
public data class TerminalCellSize(val width: Int, val height: Int) {
    init { require(width >= 0 && height >= 0) }
}

/** Default host classification; embedders may pass an explicit scheme independently of RGB. */
public fun TerminalTheme.inferredColorScheme(): TerminalColorScheme {
    val luminance = (background shr 16) * 299 + (background shr 8 and 255) * 587 + (background and 255) * 114
    return if (luminance >= 128_000) TerminalColorScheme.LIGHT else TerminalColorScheme.DARK
}

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
    /** Scope monochrome foreground opacity (0..1). Intrinsic color glyphs keep their own alpha. */
    public fun withOpacity(opacity: Double, draw: () -> Unit) { draw() }
    /** Draw over text; GPU backends keep this in the final overlay batch. */
    public fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
        fill(x, y, width, height, color)
    }
    /** Draw before text; retained renderers must preserve this layer separately from overlays. */
    public fun underlay(x: Double, y: Double, width: Double, height: Double, color: Int) {
        decoration(x, y, width, height, color)
    }
}

/** Cell boundaries are preserved for atlas rendering, including wide and combined glyphs. */
internal interface TerminalGlyphPainter : TerminalPainter {
    fun beginRow(row: Int)
    val shapesRows: Boolean get() = false
    fun shapeRow(line: TerminalLine, row: Int, cursor: Int?, selected: BooleanArray, isolated: IntRange?, foreground: (Int) -> Int): Boolean = false
    fun glyph(value: String, x: Double, y: Double, columns: Int, color: Int, style: Int)
    fun background(x: Double, y: Double, width: Double, height: Double, color: Int, default: Boolean) = fill(x, y, width, height, color)
}

/** Per-view damage, scrolling, and selection. Several surfaces may observe the same session. */
public class TerminalViewport internal constructor(
    public val session: TerminalSession,
    private val invalidate: () -> Unit,
    private val scheduler: TerminalScheduler?,
    private val graphicsClock: () -> Long,
) {
    public constructor(session: TerminalSession, invalidate: () -> Unit, scheduler: TerminalScheduler? = null) :
        this(session, invalidate, scheduler, ::graphicsNowMillis)

    private var frozen: FrozenFrame? = null
    private var captureAccessibleDocument = false
    private var frameEpoch = 0L
    private var preedit: TerminalPreedit? = null
    private val renderedPreedit: TerminalPreedit? get() = frozen.let { if (it == null) preedit else it.preedit }
    internal val preeditWidth: Double get() = preedit?.width ?: 0.0
    internal fun setPreedit(text: String) {
        if (disposed) return
        preedit = TerminalPreedit.from(text)
        markAll()
    }
    internal fun graphicsFrame(): List<TerminalGraphicsFrameEntry> {
        if (disposed) return emptyList()
        frozen?.let { return it.graphics }
        advanceGraphicsFrame()
        return if (disposed) emptyList() else frozen?.graphics
            ?: session.graphicsState.captureFrame(scrollOffset, columns, rows, session.cellSize, ::visibleLine)
    }
    private var visible = true
    private var advancingGraphics = false
    private var graphicsTimer: TerminalDisposable? = null
    private var graphicsWake: Any? = null

    /** Hidden surfaces retain snapshots but do not advance animations or schedule redraws. */
    public fun setVisible(visible: Boolean) {
        if (disposed || this.visible == visible) return
        this.visible = visible
        session.requestHistoryCompression()
        cancelGraphicsTimer(); resetBlink()
        if (visible) markAll()
    }

    private fun cancelGraphicsTimer() {
        graphicsWake = null
        graphicsTimer?.dispose(); graphicsTimer = null
    }

    private fun advanceGraphicsFrame() {
        val scheduler = scheduler ?: return
        if (!visible || advancingGraphics) return
        cancelGraphicsTimer()
        advancingGraphics = true
        val delay = try { session.advanceGraphicsAnimations(graphicsClock()) } finally { advancingGraphics = false }
        if (delay == null || disposed || !visible || frozen != null) return
        val wake = Any(); graphicsWake = wake
        graphicsTimer = scheduler.schedule(delay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            if (graphicsWake === wake && !disposed && visible && frozen == null) {
                graphicsTimer = null; graphicsWake = null
                // Advance only when a renderer builds the next frame. A busy/hidden GPU
                // cannot spin timers or skip displayed frames while waiting for a drawable.
                markAll()
            }
        }
    }
    /** Effective rendering defaults, including DEC 5 and any retained synchronized frame. */
    public val theme: TerminalTheme get() = frozen?.theme ?: session.renderTheme
    internal val faintOpacity: Double get() = frozen?.faintOpacity ?: session.faintOpacity
    internal val minimumContrast: Double get() = frozen?.minimumContrast ?: session.minimumContrast
    internal fun contrastCursor(): TerminalCellPosition? {
        if (renderedPreedit != null || !cursorVisible || !cursorOn || !focused || cursorStyle != TerminalCursorStyle.BLOCK) return null
        val line = visibleLine(cursorRow)
        val column = if (line.width(cursorColumn) == 0) (cursorColumn - 1).coerceAtLeast(0) else cursorColumn
        return TerminalCellPosition(cursorRow, column)
    }
    private val boldColor: TerminalBoldColor? get() {
        val frame = frozen
        return if (frame == null) session.boldColor else frame.boldColor
    }
    private val selectionColors: TerminalSelectionColors? get() {
        val frame = frozen
        return if (frame == null) session.selectionColors else frame.selectionColors
    }
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
        { session.selectionEpoch }, { session.lineOrderEpoch }, { session.selectionWordBoundaries }, { session.detectedLinksEnabled })
    private val activeSelection: TerminalSelection get() = frozen?.selection ?: liveSelection
    private var draggingSelection = false
    private var promptClickCandidate: Pair<Int, Int>? = null
    private var dragColumn = 0
    private var dragRectangle = false
    private var dragPointer: TerminalSelectionPointer? = null
    private var pointerPressTime: Double? = null
    private val pointerClicks = TerminalClickSequence()
    private var pointerClickRow: TerminalLine? = null
    private var pointerClickRecycle = 0L
    private var pointerClickColumn = 0
    private var dragRow = 0
    private var dragTimer: TerminalDisposable? = null
    private var disposed = false
    private var hoverColumn = -1
    private var hoverRow = -1
    internal var linkHover: TerminalLinkHover? = null
        private set
    private val observation = session.observe { change ->
        if (!advancingGraphics) cancelGraphicsTimer()
        if (screenEpoch != session.screenEpoch) {
            if (frozen == null) resetPointerClicks()
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
        if (refreshLinkHover() || change.full || scrollOffset > 0 || hadSelection) markAll()
        else {
            firstDirty = minOf(firstDirty, change.firstRow); lastDirty = maxOf(lastDirty, change.lastRow)
            if (!advancingGraphics) invalidate()
        }
        if (resizeHeld && !resizingSession && !session.synchronizedOutput) {
            resizeQuietTimer?.dispose()
            resizeQuietTimer = scheduler?.schedule(8) { releaseResizeFrame() }
        }
    }
    private val linkPolicyObservation = session.onLinkPolicyChanged {
        if (refreshLinkHover()) markAll()
    }
    private val holdObservation = session.onRenderHold { renderHold(it) }
    private val historyCompression = session.attachHistoryCompression(scheduler) {
        if (disposed || !visible || frozen != null || session.alternateScreen) null
        else (session.historySize - scrollOffset) until (session.historySize - scrollOffset + rows)
    }

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
            restorePointerClicksAfterHold()
            frozen = null
            resetBlink()
        }
        markAll()
    }

    private fun captureFrame(capture: Boolean = true) {
        cancelGraphicsTimer()
        // Stop autoscroll while holding presentation, but keep a held gesture alive.
        dragTimer?.dispose(); dragTimer = null; promptClickCandidate = null
        val colors = session.colors.snapshot()
        val first = session.historySize - scrollOffset
        val selected = liveSelection.range()
        val gesture = liveSelection.snapshot()
        if (gesture == null) cancelSelectionDrag()
        val clickRow = pointerClickRow
        val clickLine = if (clickRow == null || clickRow.recycleEpoch != pointerClickRecycle) -1 else session.indexOfLine(clickRow)
        val capturedFirst = minOf(first, selected?.start?.line ?: first, gesture?.firstLine ?: first,
            clickLine.takeIf { it >= 0 } ?: first)
        val visibleLast = first + session.rows - 1
        val capturedLast = maxOf(visibleLast, selected?.end?.line ?: visibleLast, gesture?.lastLine ?: visibleLast,
            clickLine.takeIf { it >= 0 } ?: visibleLast)
        val selectedText = if (selected != null) liveSelection.text() else null
        // A newly mounted view has no completed frame to retain.
        val selectionLines = Array(capturedLast - capturedFirst + 1) { index ->
            val y = capturedFirst + index
            if (capture) session.line(y).snapshot(colors).also { if (y !in first..visibleLast) it.compress() }
            else TerminalLine(session.columns, colors)
        }
        val lines = Array(session.rows) { selectionLines[first + it - capturedFirst] }
        val copiedText = selected?.takeIf { it.start != it.end }?.let { range ->
            TerminalCopySnapshot(range, Array(range.end.line - range.start.line + 1) { index ->
                selectionLines[range.start.line + index - capturedFirst]
            }, colors.theme)
        }
        val selection = TerminalSelection({ capturedFirst }, { capturedLast }, { selectionLines[it - capturedFirst] }, { row ->
            val index = selectionLines.indexOfFirst { it === row }; if (index < 0) -1 else capturedFirst + index
        }, wordBoundaries = { session.selectionWordBoundaries }, detectedLinksEnabled = { session.detectedLinksEnabled })
        frozen = FrozenFrame(lines, first, session.renderTheme, session.cursorColumn, session.cursorRow,
            capture && session.cursorVisible && scrollOffset == 0, session.cursorStyle, selection, selected, selectedText, copiedText, colors.theme,
            selectionLines, capturedFirst, gesture, session.screenEpoch, clickRow, pointerClickRecycle, session.faintOpacity, session.boldColor, session.selectionColors, session.minimumContrast,
            if (capture) session.graphicsState.captureFrame(scrollOffset, session.columns, session.rows, session.cellSize) { lines[it] } else emptyList(),
            if (capture) preedit else null,
            ++frameEpoch,
            if (!captureAccessibleDocument) null else if (!capture) lines else Array(session.lineCount) { y ->
                if (y in capturedFirst..capturedLast) selectionLines[y - capturedFirst]
                else session.line(y).snapshot(colors)
            })
        if (clickLine >= 0) {
            pointerClickRow = selectionLines[clickLine - capturedFirst]
            pointerClickRecycle = pointerClickRow!!.recycleEpoch
        } else {
            pointerClicks.reset(); pointerClickRow = null
            if (gesture == null) pointerPressTime = null
        }
        blinkTimer?.dispose(); blinkTimer = null
    }

    /** Briefly retain the completed frame while the PTY responds to a live grid resize. */
    internal fun resizeSession(columns: Int, rows: Int, cellSize: TerminalCellSize = session.cellSize ?: TerminalCellSize(0, 0)) {
        if (disposed || (columns == session.columns && rows == session.rows)) return
        check(!session.synchronizedOutput) { "Finish synchronized output before resizing the grid" }
        if (scheduler == null) { session.resize(columns, rows, cellSize); return }
        if (!resizeHeld) { captureFrame(); resizeHeld = true }
        resizeQuietTimer?.dispose(); resizeQuietTimer = null
        // Repeated resize events cannot extend this deadline and freeze a silent program.
        if (resizeDeadline == null) resizeDeadline = scheduler.schedule(50) { releaseResizeFrame() }
        resizingSession = true
        try { session.resize(columns, rows, cellSize) } finally { resizingSession = false }
    }

    private fun cancelResizeTimers() {
        resizeDeadline?.dispose(); resizeQuietTimer?.dispose()
        resizeDeadline = null; resizeQuietTimer = null
    }

    private fun releaseResizeFrame() {
        cancelResizeTimers()
        if (disposed || !resizeHeld || session.synchronizedOutput) return
        resizeHeld = false
        restorePointerClicksAfterHold()
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
        if (disposed || !visible || frozen != null || !focused || !session.cursorBlink || !session.cursorVisible || scrollOffset != 0) return
        blinkTimer = scheduler?.schedule(TERMINAL_CURSOR_BLINK_MILLIS) {
            blinkTimer = null; cursorOn = !cursorOn
            firstDirty = minOf(firstDirty, cursorRow); lastDirty = maxOf(lastDirty, cursorRow); invalidate()
            scheduleBlink()
        }
    }

    private fun visibleLine(row: Int): TerminalLine = frozen?.lines?.get(row) ?: session.line(firstLine + row)
    public fun visibleText(): String = (0 until rows).joinToString("\n") { visibleLine(it).text() }

    /** Hosts opt in before receiving input so a first AX query during a hold has a complete old document. */
    internal fun enableAccessibleDocument() {
        captureAccessibleDocument = true
        // A view mounted during a hold has no completed frame and initially presents blank rows.
        frozen?.let { if (it.accessibleLines == null) it.accessibleLines = it.lines }
    }

    internal fun accessibleDocumentKey(): TerminalAccessibleDocumentKey =
        // A held document depends only on its frame and selection, not hidden live mutations.
        TerminalAccessibleDocumentKey(
            if (frozen == null) session.selectionEpoch else null,
            if (frozen == null) session.historyClearEpoch else null,
            frozen?.epoch,
            selection(),
        )

    internal fun accessibleDocument(): TerminalAccessibleText {
        check(captureAccessibleDocument)
        val retained = frozen?.accessibleLines
        return if (retained == null) terminalAccessibleDocument(session.lineCount, session::line, selection())
        else terminalAccessibleDocument(retained.size, { retained[it] }, selection())
    }

    internal fun accessibleText(): TerminalAccessibleText = terminalAccessibleText(rows, firstLine, ::visibleLine, selection())

    // Cheap notification key: never copy a potentially very large scrollback selection
    // on each damage event. Selection watches invalidate overwritten text themselves.
    internal fun accessibleSelectionKey(): Pair<Int, TerminalSelectionRange>? =
        selection()?.takeIf { it.start != it.end && (!it.rectangle || it.start.column != it.end.column) }?.let { firstLine to it }

    public fun dispose() {
        resetPointerClicks()
        if (!disposed) {
            disposed = true; linkHover = null; hoverColumn = -1; hoverRow = -1; observation.dispose(); holdObservation.dispose(); linkPolicyObservation.dispose(); historyCompression.dispose()
            cancelGraphicsTimer()
            cancelResizeTimers(); resizeHeld = false
            cancelSelectionDrag(); liveSelection.clear(); frozen?.selection?.clear(); scrollAnchor = null
            blinkTimer?.dispose(); holdTimer?.dispose(); blinkTimer = null; holdTimer = null; frozen = null; preedit = null
        }
    }
    public fun markAll() {
        refreshLinkHover()
        firstDirty = 0; lastDirty = rows - 1
        if (!disposed && !advancingGraphics) invalidate()
    }
    public fun scroll(lines: Int) {
        if (disposed || frozen != null) return
        scrollOffset = (scrollOffset.toLong() + lines).coerceIn(0, session.historySize.toLong()).toInt()
        session.requestHistoryCompression()
        rememberScrollAnchor(); resetBlink(); markAll()
    }
    public fun followOutput() {
        if (scrollOffset != 0) { scrollOffset = 0; scrollAnchor = null; session.requestHistoryCompression(); resetBlink(); markAll() }
    }
    public fun beginSelection(column: Int, row: Int, mode: TerminalSelectionMode = TerminalSelectionMode.CHARACTER) {
        if (disposed) return
        cancelSelectionDrag()
        frozen?.let { liveSelection.clear(); it.clearInheritedSelection() }
        activeSelection.begin(position(column, row), mode)
        draggingSelection = true; dragColumn = column; dragRow = row; dragRectangle = false
        promptClickCandidate = if (mode == TerminalSelectionMode.CHARACTER && frozen == null &&
            column in 0 until columns && row in 0 until rows) column to row else null
        markAll()
    }
    public fun extendSelection(column: Int, row: Int) { extendSelection(column, row, rectangle = false) }
    internal fun beginPointerSelection(pointer: TerminalSelectionPointer, mode: TerminalSelectionMode = TerminalSelectionMode.CHARACTER,
        timeMillis: Double? = null, origin: TerminalCellPosition = pointerPosition(pointer), originX: Double = pointer.x) {
        beginSelection(pointer.column, pointer.row, mode)
        if (disposed) return
        activeSelection.beginPointer(pointerPosition(pointer), mode, originX, origin)
        pointerPressTime = timeMillis
        dragPointer = pointer
        markAll()
    }
    internal fun resetPointerClicks() { pointerClicks.reset(); pointerClickRow = null; pointerPressTime = null }

    private fun restorePointerClicksAfterHold() {
        val frame = frozen ?: return
        val row = frame.liveClickRow
        if (!frame.interacted && frame.screenEpoch == session.screenEpoch &&
            (row == null || row.recycleEpoch == frame.liveClickRecycle && session.indexOfLine(row) >= 0)) {
            pointerClickRow = row; pointerClickRecycle = frame.liveClickRecycle
        } else resetPointerClicks()
    }

    /** Count actual local presses, independent of the host's clickCount/detail hint. */
    internal fun pressPointerSelection(pointer: TerminalSelectionPointer, timeMillis: Double,
        repeatIntervalMillis: Double, output: Boolean) {
        if (disposed) return
        val row = pointerClickRow
        val frame = frozen
        val index = if (row == null || row.recycleEpoch != pointerClickRecycle) -1 else if (frame == null)
            session.indexOfLine(row) else frame.selectionLines.indexOfFirst { it === row }.let { if (it < 0) -1 else frame.selectionFirst + it }
        val previous = if (index >= 0) TerminalCellPosition(index, pointerClickColumn.coerceAtMost(row!!.columns - 1)) else null
        val count = pointerClicks.press(timeMillis, pointer.x, pointer.y, repeatIntervalMillis, pointer.cellWidth.toDouble(), previous != null)
        val origin = if (count == 1) pointerPosition(pointer).also {
            pointerClickRow = frame?.selectionLine(it.line) ?: session.line(it.line)
            pointerClickRecycle = pointerClickRow!!.recycleEpoch; pointerClickColumn = it.column
        } else checkNotNull(previous)
        beginPointerSelection(pointer, when (count) {
            1 -> TerminalSelectionMode.CHARACTER
            2 -> TerminalSelectionMode.WORD
            else -> if (output) TerminalSelectionMode.OUTPUT else TerminalSelectionMode.LINE
        }, timeMillis, origin, pointerClicks.x)
    }
    /** Shift-click continues the original gesture after the repeat-click interval.
     * Keep its anchor, granularity and timestamp; a quick click may still become a multi-click. */
    internal fun continuePointerSelection(pointer: TerminalSelectionPointer, timeMillis: Double,
        repeatIntervalMillis: Double, rectangle: Boolean): Boolean {
        val previous = pointerPressTime ?: return false
        if (disposed || !timeMillis.isFinite() || !previous.isFinite() ||
            timeMillis - previous <= repeatIntervalMillis) return false
        prepareInheritedGesture()
        if (!activeSelection.canContinuePointer()) return false
        draggingSelection = true; promptClickCandidate = null
        extendPointerSelection(pointer, rectangle)
        return true
    }
    internal fun extendPointerSelection(pointer: TerminalSelectionPointer, rectangle: Boolean) {
        if (disposed || !draggingSelection) return
        prepareInheritedGesture(); changeFrozenSelection()
        dragPointer = pointer; dragRectangle = rectangle; dragColumn = pointer.column; dragRow = pointer.row
        if (activeSelection.extendPointer(pointerPosition(pointer), pointer.x, pointer.cellWidth, rectangle)) promptClickCandidate = null
        updateDragScroll(); markAll()
    }
    internal fun selectionModifiersChanged(rectangle: Boolean) { dragRectangle = rectangle }
    internal fun linkHoverAllowedWhileDragging(pointer: TerminalSelectionPointer): Boolean {
        if (!draggingSelection) return true
        prepareInheritedGesture()
        return activeSelection.isPointerOrigin(pointerPosition(pointer))
    }
    internal fun pointerLinkAt(pointer: TerminalSelectionPointer): TerminalLinkTarget? {
        if (disposed || !draggingSelection || pointer.column !in 0 until columns || pointer.row !in 0 until rows) return null
        prepareInheritedGesture()
        if (!activeSelection.isPointerClick(pointerPosition(pointer))) return null
        return linkTargetAt(pointer.column, pointer.row)
    }
    internal fun endPointerSelection(pointer: TerminalSelectionPointer): Boolean {
        // Ghostty releases without synthesizing a final drag. Moving cells still cancels click actions.
        if (pointer.column != dragColumn || pointer.row != dragRow) promptClickCandidate = null
        dragColumn = pointer.column; dragRow = pointer.row
        return finishSelection(pointer.column, pointer.row, true)
    }
    private fun pointerPosition(pointer: TerminalSelectionPointer) = TerminalCellPosition(
        firstLine + pointer.row.coerceIn(0, rows - 1), pointer.column.coerceIn(0, columns - 1))
    /** Extend by cell boundaries; rectangle applies only to CHARACTER gestures.
     * The shape may change during a drag. Word, line and output gestures keep their granularity. */
    public fun extendSelection(column: Int, row: Int, rectangle: Boolean) {
        if (disposed || !draggingSelection) return
        prepareInheritedGesture(); changeFrozenSelection()
        if (promptClickCandidate != (column to row)) promptClickCandidate = null
        dragColumn = column; dragRow = row; dragRectangle = rectangle
        activeSelection.extend(position(column, row, rectangle), rectangle); updateDragScroll(); markAll()
    }
    /** Consume a held local pointer gesture and select the word at its original press.
     * Returns true for a consumed gesture even when an unwritten cell has no word;
     * in that case the previous selection remains. */
    public fun deepPressSelection(): Boolean {
        if (disposed || !draggingSelection) return false
        prepareInheritedGesture()
        val consumed = activeSelection.deepPress()
        if (consumed) changeFrozenSelection()
        if (consumed) resetPointerClicks()
        cancelSelectionDrag()
        if (consumed) markAll()
        return consumed
    }
    /** Complete a local drag while retaining its text selection. */
    public fun endSelection(column: Int, row: Int) { finishSelection(column, row, false) }
    /** Release a local left-button gesture, including negotiated OSC 133 click reporting.
     * Call only when the press was handled locally, never after an application mouse press. */
    public fun endPointerSelection(column: Int, row: Int): Boolean = finishSelection(column, row, true)
    private fun finishSelection(column: Int, row: Int, reportPromptClick: Boolean): Boolean {
        // Release alone must not synthesize a drag: a double-click link override
        // persists until an actual movement resumes word-wise selection.
        if (column != dragColumn || row != dragRow) extendSelection(column, row, dragRectangle)
        val range = activeSelection.range()
        val report = reportPromptClick && !disposed && frozen == null && promptClickCandidate != null &&
            (range == null || range.start == range.end)
        cancelSelectionDrag()
        return report && session.sendPromptClick(column, firstLine + row, row)
    }
    /** Pointer cancellation/focus loss ends autoscrolling but leaves the completed selection. */
    public fun cancelSelectionDrag() { draggingSelection = false; promptClickCandidate = null; dragPointer = null; dragTimer?.dispose(); dragTimer = null }
    private fun prepareInheritedGesture() {
        frozen?.let { frame -> frame.inheritedGesture?.let { frame.selection.restore(it); frame.inheritedGesture = null } }
    }
    private fun changeFrozenSelection() { frozen?.let { liveSelection.clear(); it.clearInheritedSelection() } }
    public fun clearSelection() {
        cancelSelectionDrag(); liveSelection.clear()
        frozen?.let { it.selection.clear(); it.clearInheritedSelection() }
        markAll()
    }
    public fun selectedText(): String = frozen?.inheritedText ?: activeSelection.text()
    /** Includes selected empty cells, whose clipboard representation may be an empty string. */
    public val hasSelection: Boolean get() = !disposed && selection()?.let {
        it.start != it.end && (!it.rectangle || it.start.column != it.end.column)
    } == true

    /** Clipboard formatting is independent of selection/accessible text. The default trims
     * trailing ASCII spaces, matching Ghostty; soft-wrapped interior spaces stay intact. */
    public fun copyText(trimTrailingSpaces: Boolean = true): String = copyText(trimTrailingSpaces, emptyList())

    /** Codepoint mappings apply after trimming, to source cell text only, in list order. */
    public fun copyText(trimTrailingSpaces: Boolean, codepointMap: List<TerminalClipboardCodepointMap>): String {
        if (disposed) return ""
        frozen?.inheritedCopyText?.let { return it.text(trimTrailingSpaces, codepointMap) }
        return if (codepointMap.isEmpty()) activeSelection.copyText(trimTrailingSpaces)
        else activeSelection.copyContent(trimTrailingSpaces).format(codepointMap)
    }

    /** A user-copy item. Mixed matches Ghostty's default: plain text plus styled HTML.
     * A null result means there is no selection and the clipboard should remain unchanged. */
    public fun copyData(format: TerminalCopyFormat = TerminalCopyFormat.MIXED, trimTrailingSpaces: Boolean = true,
        codepointMap: List<TerminalClipboardCodepointMap> = emptyList()): TerminalCopyData? {
        if (!hasSelection) return null
        val frame = frozen
        frame?.inheritedCopyText?.let { return it.data(format, trimTrailingSpaces, codepointMap) }
        val range = selection() ?: return null
        return if (frame == null) terminalCopyData(range, session::line, session.theme, format, trimTrailingSpaces, codepointMap)
        else terminalCopyData(range, frame::selectionLine, frame.copyTheme, format, trimTrailingSpaces, codepointMap)
    }

    /** A lookup reads visible cells without changing the user's selection or gesture. */
    internal fun lookupWord(column: Int, row: Int): TerminalWordLookup? {
        if (disposed || column !in 0 until columns || row !in 0 until rows) return null
        val frame = frozen
        val first = if (frame == null) 0 else firstLine
        val last = if (frame == null) session.lineCount - 1 else firstLine + rows - 1
        val line: (Int) -> TerminalLine = if (frame == null) session::line else { y -> frame.lines[y - firstLine] }
        val range = terminalWordRange(TerminalCellPosition(firstLine + row, column), first, last, line,
            session.selectionWordBoundaries) ?: return null
        return TerminalWordLookup(terminalSelectionText(range, line), visibleSelectionStart(range))
    }

    internal fun selectionForLookup(): TerminalWordLookup? = selection()?.takeIf { it.start != it.end }?.let {
        TerminalWordLookup(selectedText(), visibleSelectionStart(it))
    }

    private fun visibleSelectionStart(range: TerminalSelectionRange): TerminalCellPosition? {
        if (range.end <= TerminalCellPosition(firstLine, 0) || range.start.line >= firstLine + rows) return null
        return if (range.start.line < firstLine) TerminalCellPosition(0, 0)
        else range.start.copy(line = range.start.line - firstLine)
    }

    /** Select retained output without synthesizing a mouse drag or starting autoscroll. */
    public fun selectAll() { trySelectAll() }

    internal fun trySelectAll(): Boolean {
        if (disposed) return false
        val frame = frozen
        if (!(if (frame == null) activeSelection.selectAll() else activeSelection.selectAll(frame.firstLine, frame.firstLine + frame.lines.lastIndex))) return false
        cancelSelectionDrag()
        frozen?.let { liveSelection.clear(); it.clearInheritedSelection() }
        markAll()
        return true
    }

    /** Select a detected link or word for a local context menu. Returns true only when
     * a new selection is committed; clicking inside the current selection preserves it. */
    public fun selectForContextMenu(column: Int, row: Int, linkModifiers: Boolean = false): Boolean {
        if (disposed || column !in 0 until columns || row !in 0 until rows) return false
        val point = TerminalCellPosition(firstLine + row, column)
        selection()?.let { range ->
            if (if (range.rectangle) point.line in range.start.line..range.end.line &&
                point.column in range.start.column until range.end.column else point >= range.start && point < range.end) return false
        }
        if (!activeSelection.selectContextWord(point, linkModifiers, session.osc8LinksEnabled)) return false
        cancelSelectionDrag()
        frozen?.let { liveSelection.clear(); it.clearInheritedSelection() }
        markAll()
        return true
    }

    public fun scrollTo(offset: Int) { scroll(offset.coerceIn(0, session.historySize) - scrollOffset) }

    /** Reveal the existing selection's first row without replacing the selection.
     * Returns true when a selection is available, including one already in view.
     * Atomic presentation holds and disposed views cannot navigate. */
    public fun scrollToSelection(): Boolean {
        if (disposed || frozen != null) return false
        val range = liveSelection.range() ?: return false
        if (range.start == range.end || range.rectangle && range.start.column == range.end.column) return false
        scrollTo(session.historySize - range.start.line)
        return true
    }

    /** Move the viewport to a shell prompt: -1 for previous, +1 for next. Requires OSC 133 marks. */
    public fun jumpToPrompt(direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        if (disposed || frozen != null || session.alternateScreen) return false
        val current = session.historySize - scrollOffset
        var target = current + direction
        if (direction > 0 && session.line(current).promptMarker != TerminalPromptMarker.NONE) {
            while (target < session.lineCount && session.line(target).promptMarker == TerminalPromptMarker.CONTINUATION) target++
        }
        while (target in 0 until session.lineCount && session.line(target).promptMarker == TerminalPromptMarker.NONE) target += direction
        if (target !in 0 until session.lineCount) return false
        if (direction < 0 && session.line(target).promptMarker == TerminalPromptMarker.CONTINUATION) {
            val continuation = target
            while (target > 0 && session.line(target).promptMarker == TerminalPromptMarker.CONTINUATION &&
                session.line(target - 1).promptMarker != TerminalPromptMarker.NONE) target--
            if (target == 0 && session.line(target).promptMarker == TerminalPromptMarker.CONTINUATION) target = continuation
        }
        val before = scrollOffset
        scrollTo(session.historySize - target)
        return before != scrollOffset
    }

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

    /** Hosts apply their current modifier/capture policy before supplying a hover position. */
    internal fun setLinkHover(column: Int = -1, row: Int = -1): TerminalLinkTarget? {
        if (disposed) return null
        hoverColumn = column; hoverRow = row
        if (refreshLinkHover()) markAll()
        return linkHover?.target
    }
    private fun refreshLinkHover(): Boolean {
        val next = if (disposed || hoverRow !in 0 until rows || hoverColumn !in 0 until columns) null
            else if (frozen == null) terminalLinkHoverAt(hoverColumn, hoverRow, firstLine, rows,
                session.lineCount - 1, session::line, session.osc8LinksEnabled, session.detectedLinksEnabled)
            else terminalLinkHoverAt(hoverColumn, hoverRow, 0, rows, rows - 1, ::visibleLine, session.osc8LinksEnabled, session.detectedLinksEnabled)
        if (next == linkHover) return false
        linkHover = next
        return true
    }

    public fun hyperlinkAt(column: Int, row: Int): String? = linkTargetAt(column, row)?.uri

    internal fun linkTargetAt(column: Int, row: Int): TerminalLinkTarget? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        return if (frozen == null) terminalLinkTargetAt(firstLine + row, column, 0, session.lineCount - 1,
            session::line, session.osc8LinksEnabled, session.detectedLinksEnabled)
        else terminalLinkTargetAt(row, column, 0, rows - 1, ::visibleLine, session.osc8LinksEnabled, session.detectedLinksEnabled)
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
                val pointer = dragPointer
                if (pointer == null) activeSelection.extend(position(dragColumn, dragRow, dragRectangle), dragRectangle)
                else if (activeSelection.extendPointer(pointerPosition(pointer), pointer.x, pointer.cellWidth, dragRectangle)) promptClickCandidate = null
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
        val theme = theme
        val boldColor = boldColor
        val selectionColors = selectionColors
        val preedit = renderedPreedit
        for (y in first..last.coerceAtMost(rows - 1)) {
            (painter as? TerminalGlyphPainter)?.beginRow(y)
            val index = firstLine + y
            val originalLine = visibleLine(y)
            val preeditRange = preedit?.takeIf { y == cursorRow && scrollOffset == 0 }?.range(cursorColumn, columns - 1)
            val isolated = preeditRange?.let { it.start..it.end }
            val line = if (preeditRange != null) preedit.line(originalLine, preeditRange) else originalLine
            fun isPreedit(column: Int): Boolean = isolated?.contains(column) == true
            fun isSelected(column: Int): Boolean = !isPreedit(column) && selected(selection, index, column)
            fun style(column: Int): Int = terminalLinkHoverStyle(line.style(column), !isPreedit(column) && linkHover?.contains(column, y) == true)
            val cursor = preedit == null && cursorVisible && (cursorOn || !focused) && y == cursorRow
            val cursorX = if (line.width(cursorColumn) == 0) (cursorColumn - 1).coerceAtLeast(0) else cursorColumn
            val block = cursor && focused && cursorStyle == TerminalCursorStyle.BLOCK
            val shaped = (painter as? TerminalGlyphPainter)?.takeIf { it.shapesRows }?.shapeRow(line, y, if (cursor) cursorX else null,
                BooleanArray(columns) { isSelected(it) }, isolated) { column ->
                val flags = line.style(column)
                var foreground = terminalForegroundColor(line, column, theme.foreground, boldColor)
                var background = line.background(column).let { if (it < 0) theme.background else it }
                if (flags and TerminalStyle.INVERSE != 0) { val swap = foreground; foreground = background; background = swap }
                if (isSelected(column)) foreground = terminalSelectionForeground(selectionColors, theme, foreground, background)
                if (block && column == cursorX) theme.background else foreground
            } == true
            var x = 0
            while (x < columns) {
                val start = x
                val flags = style(x)
                var foreground = terminalForegroundColor(line, x, theme.foreground, boldColor)
                var background = line.background(x).let { if (it < 0) theme.background else it }
                if (flags and TerminalStyle.INVERSE != 0) { val swap = foreground; foreground = background; background = swap }
                val selected = isSelected(x)
                if (selected) {
                    val selectedForeground = terminalSelectionForeground(selectionColors, theme, foreground, background)
                    background = terminalSelectionBackground(selectionColors, theme, foreground, background)
                    foreground = selectedForeground
                }
                if (block && x == cursorX) { foreground = theme.background; background = theme.cursor }
                // Group only ordinary one-column cells. Wide/combined characters get exact cell
                // placement even when the system fallback font has different glyph advances.
                val blockElement = !line.isCombined(x) && isTerminalBlockElement(line.codePoint(x))
                val ordinary = !isPreedit(x) && painter !is TerminalGlyphPainter && !blockElement && line.width(x) == 1 && !line.isCombined(x) && line.codePoint(x) <= 0xffff
                val text = StringBuilder()
                do {
                    if (line.width(x) > 0) line.appendTextTo(text, x)
                    x += if (line.width(x) == 2) 2 else 1
                } while (ordinary && x < columns && !isPreedit(x) && !(block && (start == cursorX || x == cursorX)) &&
                    line.width(x) == 1 && !line.isCombined(x) && line.codePoint(x) <= 0xffff &&
                    !isTerminalBlockElement(line.codePoint(x)) &&
                    style(x) == flags && line.rawForeground(x) == line.rawForeground(start) &&
                    line.background(x) == line.background(start) && line.underlineColor(x) == line.underlineColor(start) &&
                    isSelected(x) == selected)
                val visibleEnd = minOf(x, columns)
                if (painter is TerminalGlyphPainter) painter.background(start * cellWidth, y * cellHeight, (visibleEnd - start) * cellWidth, cellHeight, background,
                    line.rawBackground(start) == TerminalStyle.DEFAULT && flags and TerminalStyle.INVERSE == 0 && !selected && !(block && start == cursorX))
                else painter.fill(start * cellWidth, y * cellHeight, (visibleEnd - start) * cellWidth, cellHeight, background)
                if (flags and TerminalStyle.HIDDEN == 0) {
                    val onCursor = block && start == cursorX
                    val opacity = if (onCursor) 1.0 else terminalForegroundOpacity(flags, faintOpacity)
                    val underlineColor = if (onCursor) foreground else line.underlineColor(start).let { if (it < 0) foreground else it }
                    val left = start * cellWidth
                    val width = (visibleEnd - start) * cellWidth
                    val baseline = (y + 1) * cellHeight - 2
                    // Original sprite decorations use the same grayscale shader as text:
                    // minimum contrast applies, and the block cursor overrides color/alpha last.
                    fun paintDecoration(color: Int, draw: (Int) -> Unit) {
                        val corrected = if (onCursor) null else terminalContrastCorrection(minimumContrast, color, background, opacity)
                        painter.withOpacity(if (corrected == null) opacity else 1.0) { draw(corrected ?: color) }
                    }
                    if (flags and TerminalStyle.UNDERLINES != 0) paintDecoration(underlineColor) { color ->
                        when {
                            flags and TerminalStyle.DOUBLE_UNDERLINE != 0 -> {
                                painter.underlay(left, baseline - 2, width, 1.0, color)
                                painter.underlay(left, baseline, width, 1.0, color)
                            }
                            flags and TerminalStyle.UNDERCURL != 0 -> {
                                for (column in start until visibleEnd) {
                                    var offset = 0.0
                                    while (offset < cellWidth) {
                                        painter.underlay(column * cellWidth + offset, baseline - if (offset.toInt() % 4 < 2) 1 else 0,
                                            minOf(2.0, cellWidth - offset), 1.0, color)
                                        offset += 2.0
                                    }
                                }
                            }
                            flags and (TerminalStyle.DOTTED_UNDERLINE or TerminalStyle.DASHED_UNDERLINE) != 0 -> {
                                val dash = if (flags and TerminalStyle.DOTTED_UNDERLINE != 0) 1.0 else 4.0
                                for (column in start until visibleEnd) {
                                    var offset = 0.0
                                    while (offset < cellWidth) {
                                        painter.underlay(column * cellWidth + offset, baseline, minOf(dash, cellWidth - offset), 1.0, color)
                                        offset += dash + 2.0
                                    }
                                }
                            }
                            flags and TerminalStyle.UNDERLINE != 0 -> painter.underlay(left, baseline, width, 1.0, color)
                        }
                    }
                    if (flags and TerminalStyle.OVERLINE != 0) paintDecoration(foreground) { color ->
                        painter.underlay(left, y * cellHeight, width, 1.0, color)
                    }
                    painter.withOpacity(opacity) {
                        if (line.codePoint(start) != TERMINAL_GRAPHICS_PLACEHOLDER) {
                            if (blockElement) painter.blockElement(line.codePoint(start), left, y * cellHeight, width, cellHeight, foreground)
                            else if (painter is TerminalGlyphPainter) {
                                if (!shaped) painter.glyph(text.toString() + if (isPreedit(start)) "\ufe0e" else "",
                                    left, y * cellHeight, x - start, foreground, flags)
                            } else painter.text(text.toString() + if (isPreedit(start)) "\ufe0e" else "", left, y * cellHeight, foreground, flags)
                        }
                    }
                    if (flags and TerminalStyle.STRIKE != 0) paintDecoration(foreground) { color ->
                        painter.decoration(left, (y + 0.5) * cellHeight, width, 1.0, color)
                    }
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

    private fun position(column: Int, row: Int, rectangle: Boolean = false) = TerminalCellPosition(
        firstLine + row.coerceIn(0, rows - 1), when { rectangle -> column.coerceIn(0, columns)
            row < 0 -> 0; row >= rows -> columns; else -> column.coerceIn(0, columns) })
    private fun selection(): TerminalSelectionRange? = frozen?.inheritedRange ?: activeSelection.range()
    private fun selected(selection: TerminalSelectionRange?, row: Int, column: Int): Boolean {
        if (selection == null) return false
        if (selection.rectangle) {
            if (row !in selection.start.line..selection.end.line || selection.start.column == selection.end.column) return false
            val width = maxOf(1, visibleLine(row - firstLine).width(column))
            return column < selection.end.column && column + width > selection.start.column
        }
        val p = TerminalCellPosition(row, column)
        return p >= selection.start && p < selection.end
    }
    private data class ScrollAnchor(val row: TerminalLine, val recycle: Long)
    private data class FrozenFrame(val lines: Array<TerminalLine>, val firstLine: Int, val theme: TerminalTheme,
        val cursorColumn: Int, val cursorRow: Int, val cursorVisible: Boolean, val cursorStyle: TerminalCursorStyle,
        val selection: TerminalSelection, var inheritedRange: TerminalSelectionRange?, var inheritedText: String?,
        var inheritedCopyText: TerminalCopySnapshot?, val copyTheme: TerminalTheme,
        val selectionLines: Array<TerminalLine>, val selectionFirst: Int, var inheritedGesture: TerminalSelection.Snapshot?,
        val screenEpoch: Long, val liveClickRow: TerminalLine?, val liveClickRecycle: Long, val faintOpacity: Double, val boldColor: TerminalBoldColor?,
        val selectionColors: TerminalSelectionColors?, val minimumContrast: Double,
        val graphics: List<TerminalGraphicsFrameEntry>, val preedit: TerminalPreedit?, val epoch: Long,
        var accessibleLines: Array<TerminalLine>?) {
        var interacted: Boolean = false
        fun selectionLine(index: Int): TerminalLine = selectionLines[index - selectionFirst]
        fun clearInheritedSelection() {
            inheritedRange = null; inheritedText = null; inheritedCopyText = null; inheritedGesture = null; interacted = true
        }
    }
}

// Ghostty quantizes the normalized faint-opacity to ceil(opacity * 255).
internal fun terminalForegroundOpacity(style: Int, faintOpacity: Double): Double =
    if (style and TerminalStyle.FAINT != 0) kotlin.math.ceil(faintOpacity * 255.0) / 255.0 else 1.0

internal data class TerminalWordLookup(val text: String, val start: TerminalCellPosition?)
