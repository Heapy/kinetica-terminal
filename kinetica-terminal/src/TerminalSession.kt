// VT state transitions and erasure semantics adapted from Ghostty's Terminal.zig/Screen.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal


public data class TerminalChange(val firstRow: Int, val lastRow: Int, val full: Boolean)

public enum class TerminalCursorStyle(internal val code: Int) { BAR(0), BLOCK(1), UNDERLINE(2) }

/**
 * Streaming VT terminal. Confine this object and its observers to one thread (the UI thread for
 * mounted surfaces). A write parses synchronously and sends one damage notification. Transports
 * must deliver bounded chunks and apply backpressure; the engine never queues unbounded output.
 */
public class TerminalSession(columns: Int = 80, rows: Int = 24, public val scrollback: Int = 10_000,
    graphemeClustering: Boolean = false, theme: TerminalTheme = TerminalTheme(),
    cursorStyle: TerminalCursorStyle = TerminalCursorStyle.UNDERLINE, cursorBlink: Boolean = false,
    public val limits: TerminalLimits = TerminalLimits()) {
    init {
        validateSize(columns, rows)
        require(scrollback in 0..100_000) { "Scrollback must be between 0 and 100000 lines" }
    }
    public var columns: Int = columns
        private set
    public var rows: Int = rows
        private set
    public var cursorColumn: Int = 0
        private set
    public var cursorRow: Int = 0
        private set
    public var cursorVisible: Boolean = true
        private set
    public var cursorStyle: TerminalCursorStyle = cursorStyle
        private set
    public var cursorBlink: Boolean = cursorBlink
        private set
    public var synchronizedOutput: Boolean = false
        private set
    public var title: String = ""
        private set
    public var applicationCursor: Boolean = false
        private set
    public var applicationKeypad: Boolean = false
        private set
    /** DEC mode 1035: force numeric encoding, matching Ghostty's default keypad policy. */
    public var keypadNumericOverride: Boolean = true
        private set
    public var bracketedPaste: Boolean = false
        private set
    public var focusReporting: Boolean = false
        private set
    public var mouseTracking: Int = 0
        private set
    public var sgrMouse: Boolean = false
        private set
    /** Mode 1007: translate wheel scrolling to cursor keys on the alternate screen. */
    public var alternateScroll: Boolean = true
        private set
    internal var mouseEpoch: Int = 0
        private set
    public var newlineMode: Boolean = false
        private set
    public var graphemeClustering: Boolean = graphemeClustering
        private set
    public var alternateScreen: Boolean = false
        private set
    public var bellCount: Long = 0
        private set
    public var scrolledLines: Long = 0
        private set
    public val historySize: Int get() = if (alternateScreen) 0 else history.size
    public val lineCount: Int get() = historySize + rows
    // Full primary scrolling preserves relative row order. Other rearrangements
    // require selections to validate their interior anchors too.
    internal var lineOrderEpoch: Long = 0
        private set
    internal var selectionEpoch: Long = 0
        private set
    internal var screenEpoch: Long = 0
        private set
    internal fun indexOfLine(line: TerminalLine): Int {
        if (!alternateScreen) history.indexOf(line).takeIf { it >= 0 }?.let { return it }
        val row = screen.indexOfFirst { it === line }
        return if (row >= 0) historySize + row else -1
    }
    /** Accounted primary scrollback storage, including while the alternate screen is active. */
    public val historyStorageBytes: Long get() = history.storageBytes
    internal val cursorPendingWrap: Boolean get() = pendingWrap
    internal fun inspectedModes(): BooleanArray = booleanArrayOf(applicationCursor, origin, wrap,
        insertMode, newlineMode, bracketedPaste, focusReporting, sgrMouse, graphemeClustering, synchronizedOutput, cursorBlink)

    internal val colors = TerminalColors(theme)
    /** Effective theme, including OSC overrides; configured defaults are restored by OSC 110–112. */
    public val theme: TerminalTheme get() = colors.theme
    public fun paletteColor(index: Int): Int { require(index in 0..255); return colors.palette(index) }
    private val history: LineRing
    private val defaultGraphemeClustering = graphemeClustering
    private val defaultCursorStyle = cursorStyle
    private val defaultCursorBlink = cursorBlink
    private var primaryCursorStyle = cursorStyle
    private var screen: Array<TerminalLine>
    private var primary: Array<TerminalLine>? = null
    private var alternate: Array<TerminalLine>? = null
    private var primaryCursor = SavedCursor()
    private var alternateCursor = SavedCursor()
    private var primarySaved = SavedCursor()
    private var alternateSaved = SavedCursor()
    private var saved = SavedCursor()
    private var fg = TerminalStyle.DEFAULT
    private var bg = TerminalStyle.DEFAULT
    private var style = 0
    private var attributes: CellAttributes? = null
    private var protectedCell = false
    // Off changes the pen only. Ordinary erasure remembers the last ISO/DEC protection mode.
    private var protectionMode = 0
    private var primaryProtectionMode = 0
    private var alternateProtectionMode = 0
    private var top = 0
    private var bottom = rows - 1
    private var origin = false
    private var wrap = true
    private var pendingWrap = false
    private var insertMode = false
    private var graphics = false
    private var g1Graphics = false
    private var useG1 = false
    private var tabs = BooleanArray(columns) { it > 0 && it % 8 == 0 }
    private val params = IntArray(64)
    private val separators = BooleanArray(64)
    private var paramIndex = 0
    private var privateCsi = false
    // Transport budget checkpoints: ordinary text is checked every small byte slice, while
    // potentially expensive control operations are followed by an immediate checkpoint.
    private var controlEpoch = 0
    private val parser = VtParser(object : VtParser.Handler {
        override fun print(code: Int) = this@TerminalSession.print(code)
        override fun execute(code: Int) { controlEpoch++; executeControl(code) }
        override fun escape(final: Int, intermediates: Int) { controlEpoch++; escapeSequence(final, intermediates) }
        override fun csi(final: Int, command: VtParser) {
            controlEpoch++
            params.fill(0)
            for (i in 0 until command.count) params[i] = command.parameters[i].coerceAtLeast(0)
            command.colon.copyInto(separators)
            paramIndex = (command.count - 1).coerceAtLeast(0)
            privateCsi = command.prefix == '?'.code
            dispatchCsi(final.toChar(), command.prefix, command.intermediates)
        }
        override fun string(kind: Int, value: String, terminator: Int) {
            controlEpoch++
            if (kind == VtParser.OSC) finishOsc(value, terminator)
            else if (kind == VtParser.DCS) {
                // DECRQSS ignores numeric DCS parameters; its request buffer holds at most two bytes.
                val request = value.dropWhile { it in '0'..'9' || it == ';' }
                if (!request.startsWith("\$q") || request.length > 4) return
                if (request == "\$q q") {
                    val number = when (this@TerminalSession.cursorStyle) {
                        TerminalCursorStyle.BLOCK -> 2
                        TerminalCursorStyle.UNDERLINE -> 4
                        TerminalCursorStyle.BAR -> 6
                    } - if (this@TerminalSession.cursorBlink) 1 else 0
                    sendInput("\u001bP1\$r$number q\u001b\\")
                } else when (request) {
                    "\$qm" -> sendInput("\u001bP1\$r${sgrSettings()}m\u001b\\")
                    "\$qr" -> sendInput("\u001bP1\$r${top + 1};${bottom + 1}r\u001b\\")
                    else -> sendInput("\u001bP0\$r\u001b\\")
                }
            }
        }
    })
    private var utfCode = 0
    private var utfNeeded = 0
    private var utfMinimum = 0
    private var utfLower = 0x80
    private var utfUpper = 0xbf
    private var highSurrogate: Char? = null
    private var damageStart = rows
    private var damageEnd = -1
    private var fullDamage = false
    private val observers = mutableListOf<(TerminalChange) -> Unit>()
    private val inputObservers = mutableListOf<(TerminalInputData) -> Unit>()
    private val resizeObservers = mutableListOf<(Int, Int) -> Unit>()
    private val renderHoldObservers = mutableListOf<(Boolean) -> Unit>()

    init {
        history = LineRing(scrollback, limits.historyBytes)
        screen = Array(rows) { TerminalLine(columns, colors, limits.hyperlinkBytesPerLine) }
    }

    public fun observe(observer: (TerminalChange) -> Unit): TerminalDisposable {
        val listener: (TerminalChange) -> Unit = { observer(it) }
        observers += listener
        return TerminalDisposable { observers.remove(listener) }
    }

    /** Capture the visible frame on true, before any following bytes are applied. Paired transitions only. */
    public fun onRenderHold(observer: (Boolean) -> Unit): TerminalDisposable {
        val listener: (Boolean) -> Unit = { observer(it) }
        renderHoldObservers += listener
        return TerminalDisposable { renderHoldObservers.remove(listener) }
    }

    /** Host timeout/EOF escape hatch. Parsing and protocol replies continue during a hold. */
    public fun flushSynchronizedOutput() { setSynchronizedOutput(false) }

    private fun setSynchronizedOutput(enabled: Boolean) {
        if (synchronizedOutput == enabled) return
        if (enabled) flushDamage()
        synchronizedOutput = enabled
        if (!enabled) flushDamage()
        renderHoldObservers.toList().forEach { it(enabled) }
    }

    /** Input and protocol replies for the PTY, including cursor-position reports. */
    public fun onInput(observer: (TerminalInputData) -> Unit): TerminalDisposable {
        val listener: (TerminalInputData) -> Unit = { observer(it) }
        inputObservers += listener
        return TerminalDisposable { inputObservers.remove(listener) }
    }

    public fun onResize(observer: (Int, Int) -> Unit): TerminalDisposable {
        val listener: (Int, Int) -> Unit = { columns, rows -> observer(columns, rows) }
        resizeObservers += listener
        return TerminalDisposable { resizeObservers.remove(listener) }
    }

    public fun sendInput(text: String) = emitInput(TerminalInputData.Text(text))
    internal fun sendProtocolBytes(bytes: ByteArray) = emitInput(TerminalInputData.Bytes(bytes))
    private fun emitInput(data: TerminalInputData) { inputObservers.toList().forEach { it(data) } }

    /** Index zero is the oldest retained history line; returned lines are live, read-only views. */
    public fun line(index: Int): TerminalLine {
        require(index in 0 until lineCount)
        return if (index < historySize) history[index] else screen[index - historySize]
    }

    public fun screenLine(row: Int): TerminalLine = screen[row]

    /** Apply user-selected defaults without rewriting cell colors or resetting terminal modes. */
    public fun configureTheme(theme: TerminalTheme) {
        colors.configure(theme)
        damageAll(); flushDamage()
    }

    /** Clear retained primary history, including when a full-screen application is active. */
    public fun clearHistory() {
        flushSynchronizedOutput()
        history.clear(); selectionEpoch++
        damageAll(); flushDamage()
    }

    /** Move the current logical cursor line to the top, preserving the prompt and VT modes. */
    public fun clearScreen() {
        flushSynchronizedOutput()
        var first = cursorRow
        while (first > 0 && screen[first - 1].wrapped) first--
        val retained = screen.sliceArray(first..cursorRow)
        val spare = screen.filterIndexed { index, _ -> index !in first..cursorRow }
        spare.forEach { row ->
            row.recycleEpoch++; row.clear(0, columns, -1, -1, 0)
            row.wrapped = false; row.wrapContinuation = false
        }
        screen = Array(rows) { if (it < retained.size) retained[it] else spare[it - retained.size] }
        screen[0].wrapContinuation = false
        cursorRow -= first
        selectionEpoch++; screenEpoch++
        damageAll(); flushDamage()
    }

    public fun write(text: String) {
        damage(cursorRow)
        for (char in text) {
            val high = highSurrogate
            highSurrogate = null
            if (high != null) {
                if (char in '\udc00'..'\udfff') {
                    accept(0x10000 + ((high.code - 0xd800) shl 10) + char.code - 0xdc00)
                    continue
                }
                accept(0xfffd)
            }
            if (char in '\ud800'..'\udbff') highSurrogate = char
            else accept(if (char in '\udc00'..'\udfff') 0xfffd else char.code)
        }
        damage(cursorRow)
        flushDamage()
    }

    /** UTF-8 decoding state survives arbitrary transport chunk boundaries. */
    public fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        writeBytes(bytes, offset, length, null)
    }

    /** Returns consumed bytes; decoder/parser state and the unconsumed suffix remain resumable. */
    internal fun writeAvailable(bytes: ByteArray, offset: Int, length: Int, canContinue: () -> Boolean): Int =
        writeBytes(bytes, offset, length, canContinue)

    private fun writeBytes(bytes: ByteArray, offset: Int, length: Int, canContinue: (() -> Boolean)?): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var consumed = 0
        var epoch = controlEpoch
        var nextCheckpoint = 0
        for (i in offset until offset + length) {
            if (canContinue != null && (consumed == nextCheckpoint || epoch != controlEpoch)) {
                if (!canContinue()) break
                nextCheckpoint = consumed + TERMINAL_READ_SLICE
                epoch = controlEpoch
            }
            if (consumed == 0) damage(cursorRow)
            consumed++
            acceptByte(bytes[i].toInt() and 255)
        }
        if (consumed > 0) { damage(cursorRow); flushDamage() }
        return consumed
    }

    private fun acceptByte(b: Int) {
        if (parser.rawByteState) { parser.accept(b); return }
        if (utfNeeded > 0 && b in utfLower..utfUpper) {
            utfCode = (utfCode shl 6) or (b and 63)
            utfLower = 0x80; utfUpper = 0xbf
            if (--utfNeeded == 0) accept(if (utfCode < utfMinimum || utfCode > 0x10ffff || utfCode in 0xd800..0xdfff) 0xfffd else utfCode)
            return
        }
        if (utfNeeded > 0) { utfNeeded = 0; accept(0xfffd) }
        when (b) {
            in 0..0x7f -> accept(b)
            in 0xc2..0xdf -> { utfCode = b and 31; utfNeeded = 1; utfMinimum = 0x80; utfLower = 0x80; utfUpper = 0xbf }
            in 0xe0..0xef -> {
                utfCode = b and 15; utfNeeded = 2; utfMinimum = 0x800
                utfLower = if (b == 0xe0) 0xa0 else 0x80; utfUpper = if (b == 0xed) 0x9f else 0xbf
            }
            in 0xf0..0xf4 -> {
                utfCode = b and 7; utfNeeded = 3; utfMinimum = 0x10000
                utfLower = if (b == 0xf0) 0x90 else 0x80; utfUpper = if (b == 0xf4) 0x8f else 0xbf
            }
            else -> accept(0xfffd)
        }
    }

    /** Flush an incomplete character at transport EOF. */
    public fun finishInput() {
        if (utfNeeded > 0 || highSurrogate != null) {
            utfNeeded = 0; highSurrogate = null; accept(0xfffd); damage(cursorRow); flushDamage()
        }
        flushSynchronizedOutput()
    }

    /** Reflow primary text and history; full-screen alternate applications retain their physical grid. */
    public fun resize(columns: Int, rows: Int) {
        validateSize(columns, rows)
        flushSynchronizedOutput()
        if (columns == this.columns && rows == this.rows) return
        selectionEpoch++
        fun SavedCursor.position() = TerminalPosition(x, y, wrap)
        fun SavedCursor.at(position: TerminalPosition?) = if (position == null) this else
            copy(x = position.x, y = position.y, wrap = position.pendingWrap)
        fun resized(old: Array<TerminalLine>, cursor: SavedCursor, saved: SavedCursor, primary: Boolean): TerminalResizeResult {
            val result = resizeTerminal(old, if (primary) (0 until history.size).map { history[it] } else emptyList(),
                cursor.position(), saved.takeIf { it.valid }?.position(), columns, rows,
                if (primary) scrollback else 0, limits.historyBytes, reflow = primary)
            if (primary) {
                scrolledLines += (result.history.size - history.size).coerceAtLeast(0)
                history.clear()
                result.history.forEach { history.add(it) }
            }
            return result
        }
        val active = resized(screen, saveCursor(), saved, !alternateScreen)
        screen = active.screen; saved = saved.at(active.saved)
        primary = primary?.let { old ->
            val result = resized(old, primaryCursor, primarySaved, true)
            primaryCursor = primaryCursor.at(result.cursor); primarySaved = primarySaved.at(result.saved)
            result.screen
        }
        alternate = alternate?.let { old ->
            val result = resized(old, alternateCursor, alternateSaved, false)
            alternateCursor = alternateCursor.at(result.cursor); alternateSaved = alternateSaved.at(result.saved)
            result.screen
        }
        cursorRow = active.cursor.y; cursorColumn = active.cursor.x; pendingWrap = active.cursor.pendingWrap
        this.columns = columns; this.rows = rows
        top = 0; bottom = rows - 1
        tabs = BooleanArray(columns) { if (it < tabs.size) tabs[it] else it % 8 == 0 }
        damageAll(); flushDamage()
        resizeObservers.toList().forEach { it(columns, rows) }
    }

    public fun reset() {
        selectionEpoch++; screenEpoch++
        history.clear(); primary = null; alternate = null; alternateScreen = false
        screen = Array(rows) { TerminalLine(columns, colors, limits.hyperlinkBytesPerLine) }
        cursorColumn = 0; cursorRow = 0; cursorVisible = true
        cursorStyle = defaultCursorStyle; primaryCursorStyle = defaultCursorStyle; cursorBlink = defaultCursorBlink
        top = 0; bottom = rows - 1; fg = -1; bg = -1; style = 0; attributes = null
        protectedCell = false; protectionMode = 0; primaryProtectionMode = 0; alternateProtectionMode = 0
        origin = false; wrap = true; pendingWrap = false; insertMode = false; graphics = false; g1Graphics = false; useG1 = false
        applicationCursor = false; bracketedPaste = false; focusReporting = false
        applicationKeypad = false; keypadNumericOverride = true; alternateScroll = true
        mouseTracking = 0; sgrMouse = false; mouseEpoch++; newlineMode = false; graphemeClustering = defaultGraphemeClustering
        parser.reset(); utfNeeded = 0; highSurrogate = null; title = ""
        saved = SavedCursor(); primaryCursor = SavedCursor(); alternateCursor = SavedCursor()
        primarySaved = SavedCursor(); alternateSaved = SavedCursor()
        tabs = BooleanArray(columns) { it > 0 && it % 8 == 0 }
        damageAll(); flushSynchronizedOutput(); flushDamage()
    }

    /** Copy a range with an exclusive end, expanding partial wide cells to whole glyphs. */
    public fun selectedText(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        require(startLine in 0 until lineCount && endLine in startLine until lineCount)
        if (startLine == endLine && startColumn.coerceIn(0, columns) >= endColumn.coerceIn(0, columns)) return ""
        return buildString {
            for (y in startLine..endLine) {
                val line = line(y)
                var start = if (y == startLine) startColumn.coerceIn(0, columns) else 0
                var end = if (y == endLine) endColumn.coerceIn(0, columns) else columns
                if (start < columns && line.width(start) == 0) start--
                if (end < columns && line.width(end) == 0) end++
                val part = buildString { for (x in start until end) if (line.width(x) != 0 && !line.isSpacerHead(x)) append(line.text(x)) }
                append(if (end == columns && !line.wrapped) part.trimEnd(' ') else part)
                if (y != endLine && !line.wrapped) append('\n')
            }
        }
    }

    private fun accept(code: Int) {
        if (!parser.rawByteState || code < 128) { parser.accept(code); return }
        // String writes represent UTF-8 on the wire too. Preserve the byte transitions
        // inside a header instead of passing a decoded non-ASCII codepoint to that table.
        when {
            code < 0x800 -> { acceptByte(0xc0 or (code shr 6)); acceptByte(0x80 or (code and 63)) }
            code < 0x10000 -> { acceptByte(0xe0 or (code shr 12)); acceptByte(0x80 or (code shr 6 and 63)); acceptByte(0x80 or (code and 63)) }
            else -> { acceptByte(0xf0 or (code shr 18)); acceptByte(0x80 or (code shr 12 and 63)); acceptByte(0x80 or (code shr 6 and 63)); acceptByte(0x80 or (code and 63)) }
        }
    }

    private fun executeControl(code: Int) {
        if (code in 0x80..0x9f) { escapeSequence(code - 0x40, 0); return }
        when (code) {
            7 -> bellCount++
            8 -> { cursorColumn = (cursorColumn - 1).coerceAtLeast(0); pendingWrap = false }
            9 -> { tab(1); pendingWrap = false }
            10, 11, 12 -> { lineFeed(); if (newlineMode) cursorColumn = 0 }
            13 -> { cursorColumn = 0; pendingWrap = false }
            14 -> useG1 = true
            15 -> useG1 = false
        }
    }

    private fun escapeSequence(final: Int, intermediates: Int) {
        when (intermediates) {
            '('.code -> graphics = final == '0'.code
            ')'.code -> g1Graphics = final == '0'.code
            '#'.code -> if (final == '8'.code) {
                for (line in screen) {
                    line.wrapped = false
                    for (x in 0 until columns) line.put(x, 'E'.code, 1, -1, -1, 0)
                }
                damageAll()
            }
            0 -> when (final.toChar()) {
                '7' -> saved = saveCursor()
                '8' -> restoreCursor(saved)
                'D' -> lineFeed()
                'E' -> { cursorColumn = 0; lineFeed() }
                'M' -> reverseIndex()
                'H' -> tabs[cursorColumn] = true
                'V' -> { protectedCell = true; protectionMode = PROTECTION_ISO }
                'W' -> protectedCell = false
                '=' -> applicationKeypad = true
                '>' -> applicationKeypad = false
                'Z' -> sendInput(PRIMARY_DEVICE_ATTRIBUTES)
                'c' -> reset()
            }
        }
    }

    private fun dispatchCsi(final: Char, prefix: Int, intermediates: Int) {
        if (final == 'q' && prefix == '>'.code && intermediates == 0) {
            sendInput("\u001bP>|Kinetica\u001b\\")
            return
        }
        if (final == 'c' && intermediates == 0) {
            // A conservative VT100/advanced-video identity; the zero version/unit
            // fields are stable and disclose no host identity. Follow Ghostty's
            // parameter-tolerant DA requests; DEC-private primary replies are not queries.
            when (prefix) {
                0 -> sendInput(PRIMARY_DEVICE_ATTRIBUTES)
                '>'.code -> sendInput("\u001b[>0;0;0c")
                '='.code -> sendInput("\u001bP!|00000000\u001b\\")
            }
            return
        }
        if (prefix == 0 && intermediates == ' '.code && final == 'q' && paramIndex == 0) {
            when (params[0]) {
                0 -> { cursorStyle = defaultCursorStyle; cursorBlink = defaultCursorBlink }
                in 1..6 -> {
                    cursorStyle = when (params[0]) {
                        1, 2 -> TerminalCursorStyle.BLOCK
                        3, 4 -> TerminalCursorStyle.UNDERLINE
                        else -> TerminalCursorStyle.BAR
                    }
                    cursorBlink = params[0] % 2 == 1
                }
            }
            damage(cursorRow)
            return
        }
        if (final == 'p' && intermediates == '$'.code && paramIndex == 0 && (prefix == 0 || prefix == '?'.code)) {
            reportMode(params[0], prefix == '?'.code)
            return
        }
        if (prefix == 0 && intermediates == '"'.code && final == 'q' && paramIndex == 0) {
            when (params[0]) {
                0, 2 -> protectedCell = false
                1 -> { protectedCell = true; protectionMode = PROTECTION_DEC }
            }
            return
        }
        if (intermediates != 0 || prefix != 0 && prefix != '?'.code) return
        // DEC private commands are distinct from ECMA-48 commands with the same final.
        if (privateCsi && final !in "hlnJK") return
        csi(final)
    }

    private fun print(input: Int) {
        val code = if ((if (useG1) g1Graphics else graphics) && input in 0x5f..0x7e)
            if (input == 0x5f) 32 else DEC_GRAPHICS[input - 0x60].code else input
        if (code > 255 && graphemeClustering && cursorColumn > 0 && appendGrapheme(code)) return
        val width = terminalCharacterWidth(code)
        if (width == 0) {
            if (graphemeClustering) return
            var x = if (wrap && pendingWrap) cursorColumn else cursorColumn - 1
            if (x >= 0) {
                if (screen[cursorRow].width(x) == 0) x--
                if (x >= 0) screen[cursorRow].combine(x, code)
            }
            damage(cursorRow); return
        }
        if (pendingWrap || width == 2 && cursorColumn == columns - 1) {
            if (wrap) {
                if (!pendingWrap && width == 2) {
                    eraseWideAt(screen[cursorRow], cursorColumn)
                    screen[cursorRow].putSpacerHead(cursorColumn, fg, bg, style, attributes, protectedCell)
                }
                screen[cursorRow].wrapped = true; cursorColumn = 0; lineFeed()
                screen[cursorRow].wrapContinuation = true
            }
            pendingWrap = false
        }
        if (width == 2 && columns < 2) return
        if (width == 2 && cursorColumn == columns - 1) return
        if (insertMode) insertCharacters(width)
        val line = screen[cursorRow]
        eraseWideAt(line, cursorColumn)
        if (width == 2) eraseWideAt(line, cursorColumn + 1)
        line.put(cursorColumn, code, width, fg, bg, style, attributes, protectedCell)
        if (width == 2) line.put(cursorColumn + 1, 0, 0, fg, bg, style, attributes, protectedCell)
        damage(cursorRow)
        cursorColumn += width
        if (cursorColumn >= columns) { cursorColumn = columns - 1; pendingWrap = true }
    }

    private fun appendGrapheme(code: Int): Boolean {
        var line = screen[cursorRow]
        var x = when {
            wrap -> cursorColumn - if (pendingWrap) 0 else 1
            cursorColumn == columns - 1 && line.hasText(cursorColumn) -> cursorColumn
            else -> cursorColumn - 1
        }
        if (x >= 0 && line.width(x) == 0) x--
        if (x < 0 || !line.hasText(x)) return false
        var previous = line.codePoint(x)
        var state = 0
        if (line.isCombined(x)) {
            val text = line.text(x)
            var offset = if (previous > 0xffff) 2 else 1
            while (offset < text.length) {
                val first = text[offset++].code
                val next = if (first in 0xd800..0xdbff && offset < text.length)
                    0x10000 + ((first - 0xd800) shl 10) + text[offset++].code - 0xdc00 else first
                state = graphemeStep(previous, next, state) ushr 1
                previous = next
            }
        }
        if (graphemeStep(previous, code, state) and 1 != 0) return false
        when (graphemeWidthEffect(previous, code)) {
            -1 -> return true
            2 -> if (line.width(x) != 2) {
                cursorColumn = x
                if (x == columns - 1) {
                    if (!wrap) return true
                    // A wrap can recycle the source row; retain the bounded cluster first.
                    val cluster = TerminalLine(1, colors, limits.hyperlinkBytesPerLine).also { it.copyCell(line, x, 0) }
                    if (line.isCombined(x)) line.replaceWithSpacerHead(x)
                    else line.putSpacerHead(x, fg, bg, style, attributes, protectedCell)
                    line.wrapped = true; damage(cursorRow)
                    cursorColumn = 0; lineFeed(); x = 0; line = screen[cursorRow]
                    line.wrapContinuation = true
                    eraseWideAt(line, x)
                    line.put(x, cluster.codePoint(0), 2, fg, bg, style, attributes, protectedCell)
                    line.copyGrapheme(cluster, 0, x)
                } else line.setWidth(x, 2)
                eraseWideAt(line, x + 1)
                line.put(x + 1, 0, 0, fg, bg, style, attributes, protectedCell)
                cursorColumn = minOf(x + 2, columns - 1)
                pendingWrap = x + 2 >= columns
            }
            1 -> if (line.width(x) == 2) {
                line.setWidth(x, 1)
                line.setWidth(x + 1, 1)
                pendingWrap = false; cursorColumn = minOf(x + 1, columns - 1)
            }
        }
        line.combine(x, code)
        damage(cursorRow)
        return true
    }

    private fun eraseWideAt(line: TerminalLine, x: Int) {
        when (line.width(x)) {
            0 -> if (x > 0) line.clear(x - 1, x, -1, bg, 0)
            2 -> if (x + 1 < columns) line.clear(x + 1, x + 2, -1, bg, 0)
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow == bottom) scrollUp(1) else cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == top) scrollDown(1) else cursorRow = (cursorRow - 1).coerceAtLeast(0)
    }

    private fun scrollUp(count: Int) {
        if (top != 0 || bottom != rows - 1 || alternateScreen) lineOrderEpoch++
        repeat(count.coerceAtMost(bottom - top + 1)) {
            val old = screen[top]
            val spare = if (top == 0 && bottom == rows - 1 && !alternateScreen) {
                scrolledLines++; history.add(old)
            } else old
            for (y in top until bottom) screen[y] = screen[y + 1]
            screen[bottom] = (spare ?: TerminalLine(columns, colors, limits.hyperlinkBytesPerLine)).also { it.recycleEpoch++; it.clear(0, columns, -1, bg, 0); it.wrapped = false; it.wrapContinuation = false }
        }
        damage(top, bottom)
    }

    private fun scrollDown(count: Int) {
        lineOrderEpoch++
        repeat(count.coerceAtMost(bottom - top + 1)) {
            val spare = screen[bottom]
            for (y in bottom downTo top + 1) screen[y] = screen[y - 1]
            screen[top] = spare.also { it.recycleEpoch++; it.clear(0, columns, -1, bg, 0); it.wrapped = false; it.wrapContinuation = false }
        }
        damage(top, bottom)
    }

    private fun csi(final: Char) {
        fun p(i: Int = 0, default: Int = 1): Int = if (i > paramIndex || params[i] == 0) default else params[i]
        val minY = if (origin) top else 0
        val maxY = if (origin) bottom else rows - 1
        val selective = privateCsi || protectionMode == PROTECTION_ISO
        when (final) {
            'A' -> cursorRow = (cursorRow - p()).coerceAtLeast(minY)
            'B', 'e' -> cursorRow = (cursorRow + p()).coerceAtMost(maxY)
            'C', 'a' -> cursorColumn = (cursorColumn + p()).coerceAtMost(columns - 1)
            'D' -> cursorColumn = (cursorColumn - p()).coerceAtLeast(0)
            'E' -> { cursorRow = (cursorRow + p()).coerceAtMost(maxY); cursorColumn = 0 }
            'F' -> { cursorRow = (cursorRow - p()).coerceAtLeast(minY); cursorColumn = 0 }
            'G', '`' -> cursorColumn = (p() - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (p() - 1 + minY).coerceIn(minY, maxY)
            'H', 'f' -> { cursorRow = (p() - 1 + minY).coerceIn(minY, maxY); cursorColumn = (p(1) - 1).coerceIn(0, columns - 1) }
            'J' -> when (params[0]) {
                0 -> { eraseLine(0, selective); eraseRows(cursorRow + 1, rows, selective) }
                1 -> { eraseRows(0, cursorRow, selective); eraseLine(1, selective) }
                2 -> { eraseRows(0, rows, selective); pendingWrap = false }
                3 -> { history.clear(); damageAll() }
            }
            'K' -> eraseLine(params[0], selective)
            'X' -> {
                eraseCells(cursorRow, cursorColumn, (cursorColumn + p()).coerceAtMost(columns),
                    protectionMode == PROTECTION_ISO, splitBoundary = true)
                resetCursorWrap()
            }
            '@' -> insertCharacters(p())
            'P' -> {
                val n = p().coerceAtMost(columns - cursorColumn)
                val line = screen[cursorRow]
                for (x in cursorColumn until columns - n) line.copyCell(line, x + n, x)
                line.clear(columns - n, columns, -1, bg, 0); line.repairWideCells(); damage(cursorRow)
            }
            'L', 'M' -> if (cursorRow in top..bottom) {
                val oldTop = top; top = cursorRow
                if (final == 'L') scrollDown(p()) else {
                    // Deleting lines never adds to scrollback, even at the top margin.
                    repeat(p().coerceAtMost(bottom - top + 1)) {
                        val spare = screen[top]
                        for (y in top until bottom) screen[y] = screen[y + 1]
                        screen[bottom] = spare.also { it.clear(0, columns, -1, bg, 0); it.wrapped = false; it.wrapContinuation = false }
                    }
                    damage(top, bottom)
                }
                top = oldTop
            }
            'S' -> scrollUp(p())
            'T' -> scrollDown(p())
            'r' -> if (!privateCsi) {
                val first = p() - 1; val last = p(1, rows) - 1
                if (first in 0 until rows && last in 0 until rows && first < last) {
                    top = first; bottom = last; cursorRow = if (origin) top else 0; cursorColumn = 0
                }
            }
            's' -> saved = saveCursor()
            'u' -> restoreCursor(saved)
            'm' -> sgr()
            'h', 'l' -> for (i in 0..paramIndex) mode(params[i], final == 'h')
            'g' -> if (params[0] == 3) tabs.fill(false) else if (params[0] == 0) tabs[cursorColumn] = false
            'I' -> tab(p())
            'Z' -> repeat(p().coerceAtMost(columns)) { do { cursorColumn-- } while (cursorColumn > 0 && !tabs[cursorColumn]); cursorColumn = cursorColumn.coerceAtLeast(0) }
            'n' -> when (params[0]) {
                5 -> sendInput("\u001b[0n")
                6 -> sendInput("\u001b[${if (privateCsi) "?" else ""}${cursorRow - minY + 1};${cursorColumn + 1}R")
            }
        }
        if (final !in "mhsncluJK") pendingWrap = false
    }

    private fun eraseLine(mode: Int, selective: Boolean) {
        when (mode) {
            0 -> { resetCursorWrap(); eraseCells(cursorRow, cursorColumn, columns, selective) }
            1 -> eraseCells(cursorRow, 0, cursorColumn + 1, selective)
            2 -> { resetCursorWrap(); eraseCells(cursorRow, 0, columns, selective) }
            else -> return
        }
        pendingWrap = false
    }

    private fun eraseRows(first: Int, end: Int, selective: Boolean) {
        for (y in first until end) {
            eraseCells(y, 0, columns, selective)
            if (!selective) { screen[y].wrapped = false; screen[y].wrapContinuation = false }
        }
    }

    private fun resetCursorWrap() {
        pendingWrap = false
        val line = screen[cursorRow]
        if (!line.wrapped) return
        line.wrapped = false
        screen.getOrNull(cursorRow + 1)?.wrapContinuation = false
        if (line.isSpacerHead(columns - 1)) line.clear(columns - 1, columns, -1, bg, 0)
        damage(cursorRow)
    }

    private fun eraseCells(y: Int, start: Int, end: Int, selective: Boolean, splitBoundary: Boolean = false) {
        val line = screen[y]
        if (start >= end) return
        val first = if (line.width(start) == 0) start - 1 else start
        val last = if (line.width(end - 1) == 2) minOf(end + 1, columns) else end
        for (x in first until last) if (!selective || !line.isProtected(x)) line.clear(x, x + 1, -1, bg, 0)
        if (splitBoundary && first == 0 && y > 0 && screen[y - 1].isSpacerHead(columns - 1) && !line.isProtected(0)) {
            screen[y - 1].clear(columns - 1, columns, -1, bg, 0)
            damage(y - 1)
        }
        damage(y)
    }

    private fun insertCharacters(count: Int) {
        val n = count.coerceAtMost(columns - cursorColumn)
        val line = screen[cursorRow]
        for (x in columns - 1 downTo cursorColumn + n) line.copyCell(line, x - n, x)
        line.clear(cursorColumn, cursorColumn + n, -1, bg, 0); line.repairWideCells(); damage(cursorRow)
    }

    private fun tab(count: Int) {
        repeat(count.coerceAtMost(columns)) {
            do { cursorColumn++ } while (cursorColumn < columns - 1 && !tabs[cursorColumn])
            cursorColumn = cursorColumn.coerceAtMost(columns - 1)
        }
    }

    private fun mode(number: Int, enabled: Boolean) {
        if (!privateCsi) {
            when (number) { 4 -> insertMode = enabled; 20 -> newlineMode = enabled }
            return
        }
        when (number) {
            1 -> applicationCursor = enabled
            3 -> {
                top = 0; bottom = rows - 1; cursorColumn = 0; cursorRow = 0; pendingWrap = false
                for (line in screen) { line.clear(0, columns, -1, -1, 0); line.wrapped = false; line.wrapContinuation = false }
                damageAll()
            }
            6 -> { origin = enabled; cursorRow = if (enabled) top else 0; cursorColumn = 0; pendingWrap = false }
            7 -> wrap = enabled
            12 -> cursorBlink = enabled
            25 -> cursorVisible = enabled
            66 -> applicationKeypad = enabled
            1035 -> keypadNumericOverride = enabled
            47, 1047, 1049 -> switchScreen(number, enabled)
            1048 -> if (enabled) saved = saveCursor() else restoreCursor(saved)
            1000, 1002, 1003 -> { mouseTracking = if (enabled) number else 0; mouseEpoch++ }
            1004 -> focusReporting = enabled
            1006 -> { sgrMouse = enabled; mouseEpoch++ }
            1007 -> alternateScroll = enabled
            2004 -> bracketedPaste = enabled
            2026 -> setSynchronizedOutput(enabled)
            2027 -> graphemeClustering = enabled
        }
    }

    // DECRPSS uses a reset followed by the active pen's attributes. Preserve indexed
    // colors, rather than resolving the palette. Formatting follows Ghostty printAttributes.
    private fun sgrSettings(): String = buildString {
        append('0')
        if (style and TerminalStyle.BOLD != 0) append(";1")
        if (style and TerminalStyle.FAINT != 0) append(";2")
        if (style and TerminalStyle.ITALIC != 0) append(";3")
        when {
            style and TerminalStyle.DOUBLE_UNDERLINE != 0 -> append(";4:2")
            style and TerminalStyle.UNDERCURL != 0 -> append(";4:3")
            style and TerminalStyle.DOTTED_UNDERLINE != 0 -> append(";4:4")
            style and TerminalStyle.DASHED_UNDERLINE != 0 -> append(";4:5")
            style and TerminalStyle.UNDERLINE != 0 -> append(";4")
        }
        if (style and TerminalStyle.INVERSE != 0) append(";7")
        if (style and TerminalStyle.HIDDEN != 0) append(";8")
        if (style and TerminalStyle.STRIKE != 0) append(";9")
        fun color(value: Int, background: Boolean) {
            if (value < 0) return
            val base = if (background) 40 else 30
            if (value >= INDEXED_COLOR) {
                val index = value and 255
                when (index) {
                    in 0..7 -> append(";${base + index}")
                    in 8..15 -> append(";${base + 60 + index - 8}")
                    else -> append(";${base + 8}:5:$index")
                }
            } else append(";${base + 8}:2::${value shr 16 and 255}:${value shr 8 and 255}:${value and 255}")
        }
        color(fg, false); color(bg, true)
    }

    private fun reportMode(number: Int, privateMode: Boolean) {
        val enabled = if (privateMode) when (number) {
            1 -> applicationCursor
            6 -> origin
            7 -> wrap
            12 -> cursorBlink
            25 -> cursorVisible
            66 -> applicationKeypad
            1035 -> keypadNumericOverride
            1000, 1002, 1003 -> mouseTracking == number
            1004 -> focusReporting
            1006 -> sgrMouse
            1007 -> alternateScroll
            2004 -> bracketedPaste
            2026 -> synchronizedOutput
            2027 -> graphemeClustering
            else -> null
        } else when (number) {
            4 -> insertMode
            20 -> newlineMode
            else -> null
        }
        val state = when (enabled) { true -> 1; false -> 2; null -> 0 }
        sendInput("\u001b[${if (privateMode) "?" else ""}$number;$state${'$'}y")
    }

    // Ghostty Terminal.switchScreenMode follows xterm's distinct 47/1047/1049 semantics.
    private fun switchScreen(mode: Int, enabled: Boolean) {
        val switched = enabled != alternateScreen
        // 1049 erases using the destination's pen, then copies the source cursor/pen.
        val clearBackground = if (mode == 1049 && enabled && switched) alternateCursor.bg else bg
        if (mode == 1049 && enabled) saved = saveCursor()
        if (mode == 1047 && !enabled && alternateScreen) eraseScreen()
        if (switched) {
            selectionEpoch++; screenEpoch++
            if (enabled) {
                primaryCursorStyle = cursorStyle
                primaryCursor = saveCursor(); primarySaved = saved; primary = screen
                primaryProtectionMode = protectionMode; protectionMode = alternateProtectionMode
                screen = alternate ?: Array(rows) { TerminalLine(columns, colors, limits.hyperlinkBytesPerLine) }; alternate = null
                saved = alternateSaved
            } else {
                if (mode == 1049) cursorStyle = primaryCursorStyle
                alternateCursor = saveCursor(); alternateSaved = saved; alternate = screen
                alternateProtectionMode = protectionMode; protectionMode = primaryProtectionMode
                screen = primary!!; primary = null; saved = primarySaved
            }
            // The current cursor/pen is copied to the destination for 47/1047,
            // and on entry for 1049. Saved cursor slots belong to each screen.
            alternateScreen = enabled
        }
        if (mode == 1049) {
            if (enabled) eraseScreen(clearBackground, resetWrap = !switched) else restoreCursor(saved)
        }
        damageAll()
    }

    private fun eraseScreen(background: Int = bg, resetWrap: Boolean = true) {
        val penBackground = bg
        bg = background
        eraseRows(0, rows, protectionMode == PROTECTION_ISO)
        bg = penBackground
        if (resetWrap) pendingWrap = false
    }

    private fun sgr() {
        var i = 0
        while (i <= paramIndex) {
            var end = i
            while (end < paramIndex && separators[end]) end++
            val subparameters = end > i
            when (val p = params[i]) {
                0 -> { fg = -1; bg = -1; style = 0; setAttributes(-1, attributes?.hyperlink) }
                1 -> style = style or TerminalStyle.BOLD
                2 -> style = style or TerminalStyle.FAINT
                3 -> style = style or TerminalStyle.ITALIC
                4 -> {
                    val underline = if (subparameters) params[i + 1] else 1
                    val flag = when (underline) {
                        0 -> 0
                        1 -> TerminalStyle.UNDERLINE
                        2 -> TerminalStyle.DOUBLE_UNDERLINE
                        3 -> TerminalStyle.UNDERCURL
                        4 -> TerminalStyle.DOTTED_UNDERLINE
                        5 -> TerminalStyle.DASHED_UNDERLINE
                        else -> style and TerminalStyle.UNDERLINES
                    }
                    style = (style and TerminalStyle.UNDERLINES.inv()) or flag
                }
                7 -> style = style or TerminalStyle.INVERSE
                8 -> style = style or TerminalStyle.HIDDEN
                9 -> style = style or TerminalStyle.STRIKE
                21 -> style = (style and TerminalStyle.UNDERLINES.inv()) or TerminalStyle.DOUBLE_UNDERLINE
                22 -> style = style and (TerminalStyle.BOLD or TerminalStyle.FAINT).inv()
                23 -> style = style and TerminalStyle.ITALIC.inv()
                24 -> style = style and TerminalStyle.UNDERLINES.inv()
                27 -> style = style and TerminalStyle.INVERSE.inv()
                28 -> style = style and TerminalStyle.HIDDEN.inv()
                29 -> style = style and TerminalStyle.STRIKE.inv()
                in 30..37 -> fg = INDEXED_COLOR or (p - 30)
                in 40..47 -> bg = INDEXED_COLOR or (p - 40)
                in 90..97 -> fg = INDEXED_COLOR or (p - 90 + 8)
                in 100..107 -> bg = INDEXED_COLOR or (p - 100 + 8)
                39 -> fg = -1
                49 -> bg = -1
                59 -> setAttributes(-1, attributes?.hyperlink)
                38, 48, 58 -> {
                    val available = if (subparameters) end else paramIndex
                    var consumed = 0
                    val color = when {
                        i + 2 <= available && params[i + 1] == 5 -> {
                            consumed = 2
                            params[i + 2].takeIf { it in 0..255 }?.let { INDEXED_COLOR or it }
                        }
                        i + 4 <= available && params[i + 1] == 2 -> {
                            // Colon form optionally carries a colorspace slot (including an omitted slot).
                            val first = i + if (subparameters && end - i >= 5) 3 else 2
                            consumed = first - i + 2
                            if ((first..first + 2).all { params[it] in 0..255 })
                                (params[first] shl 16) or (params[first + 1] shl 8) or params[first + 2]
                            else null
                        }
                        else -> null
                    }
                    if (color != null) when (p) {
                        38 -> fg = color
                        48 -> bg = color
                        58 -> setAttributes(color, attributes?.hyperlink)
                    }
                    if (!subparameters) end = i + consumed
                }
            }
            i = end + 1
        }
    }

    private fun setAttributes(underlineColor: Int, hyperlink: TerminalLink?) {
        attributes = if (underlineColor == -1 && hyperlink == null) null else CellAttributes(underlineColor, hyperlink)
    }

    private fun finishOsc(value: String, terminator: Int) {
        val separator = value.indexOf(';')
        val command = if (separator < 0) value else value.substring(0, separator)
        val payload = if (separator < 0) "" else value.substring(separator + 1)
        val operation = command.toIntOrNull()?.takeIf { it.toString() == command }
        if (operation != null && (operation == 4 || operation == 104 || operation in 10..19 || operation in 110..112)) {
            if (colors.operation(operation, payload, terminator, ::sendInput)) damageAll()
            return
        }
        if (separator < 1) return
        when (command) {
            "0", "2" -> title = payload
            "8" -> {
                val split = payload.indexOf(';')
                if (split < 0) return
                val uri = payload.substring(split + 1)
                val id = payload.substring(0, split).split(':').firstOrNull { it.startsWith("id=") }?.substring(3) ?: ""
                setAttributes(attributes?.underlineColor ?: -1, if (uri.isEmpty()) null else TerminalLink(TerminalHyperlink(id, uri)))
            }
        }
    }

    private fun saveCursor() = SavedCursor(cursorColumn, cursorRow, fg, bg, style, origin, pendingWrap, graphics, g1Graphics, useG1, attributes, protectedCell, valid = true)
    private fun restoreCursor(value: SavedCursor) {
        if (!value.valid) return
        cursorColumn = value.x.coerceIn(0, columns - 1); cursorRow = value.y.coerceIn(0, rows - 1)
        fg = value.fg; bg = value.bg; style = value.style; origin = value.origin; pendingWrap = value.wrap; graphics = value.graphics
        g1Graphics = value.g1Graphics; useG1 = value.useG1
        protectedCell = value.protectedCell
        setAttributes(value.attributes?.underlineColor ?: -1, attributes?.hyperlink)
    }
    private fun damage(first: Int, last: Int = first) { damageStart = minOf(damageStart, first); damageEnd = maxOf(damageEnd, last) }
    private fun damageAll() { fullDamage = true; damage(0, rows - 1) }
    private fun flushDamage() {
        if (synchronizedOutput || damageEnd < damageStart) return
        val event = TerminalChange(damageStart.coerceIn(0, rows - 1), damageEnd.coerceIn(0, rows - 1), fullDamage)
        damageStart = rows; damageEnd = -1; fullDamage = false
        observers.toList().forEach { it(event) }
    }

    private data class SavedCursor(val x: Int = 0, val y: Int = 0, val fg: Int = -1, val bg: Int = -1,
        val style: Int = 0, val origin: Boolean = false, val wrap: Boolean = false, val graphics: Boolean = false,
        val g1Graphics: Boolean = false, val useG1: Boolean = false, val attributes: CellAttributes? = null,
        val protectedCell: Boolean = false,
        val valid: Boolean = false)

    private companion object {
        const val PRIMARY_DEVICE_ATTRIBUTES = "\u001b[?1;2c"
        const val PROTECTION_ISO = 1
        const val PROTECTION_DEC = 2
        const val DEC_GRAPHICS = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
        fun validateSize(columns: Int, rows: Int) {
            require(columns in 1..4096 && rows in 1..4096 && columns.toLong() * rows <= 1_000_000) { "Invalid terminal dimensions: $columns x $rows" }
        }
    }
}

public fun terminalPalette(index: Int): Int {
    require(index in 0..255)
    if (index < 16) return ANSI_COLORS[index]
    if (index >= 232) { val v = 8 + (index - 232) * 10; return (v shl 16) or (v shl 8) or v }
    val n = index - 16
    fun component(v: Int): Int = if (v == 0) 0 else 55 + v * 40
    return (component(n / 36) shl 16) or (component(n / 6 % 6) shl 8) or component(n % 6)
}

private val ANSI_COLORS = intArrayOf(0x000000, 0xcd3131, 0x0dbc79, 0xe5e510, 0x2472c8, 0xbc3fbc, 0x11a8cd, 0xe5e5e5,
    0x666666, 0xf14c4c, 0x23d18b, 0xf5f543, 0x3b8eea, 0xd670d6, 0x29b8db, 0xffffff)
