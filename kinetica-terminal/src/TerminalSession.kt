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
    public val limits: TerminalLimits = TerminalLimits(),
    colorScheme: TerminalColorScheme = theme.inferredColorScheme()) {
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
    /** Raw OSC 7 URI, or null after clearing/reset. Hosts must validate it before using a local path. */
    public var workingDirectoryUri: String? = null
        private set
    private val primarySemantic = TerminalSemanticState()
    private val alternateSemantic = TerminalSemanticState()
    private val semantic: TerminalSemanticState get() = if (alternateScreen) alternateSemantic else primarySemantic
    public val cursorSemanticContent: TerminalSemanticContent get() = semantic.content
    public val hasSemanticPrompts: Boolean get() = semantic.seen
    public val promptClickMode: TerminalPromptClick? get() = semantic.click
    // Like libghostty-vt, an embedded engine cannot assume shell redraw support until negotiated.
    public var promptRedraw: TerminalPromptRedraw = TerminalPromptRedraw.NONE
        private set
    public val cursorAtPrompt: Boolean get() = !alternateScreen &&
        (screen[cursorRow].promptMarker != TerminalPromptMarker.NONE || semantic.content != TerminalSemanticContent.OUTPUT)
    public var applicationCursor: Boolean = false
        private set
    public var applicationKeypad: Boolean = false
        private set
    public var keyboardDisabled: Boolean = false
        private set
    /** Host opt-in to ANSI KAM, disabled by default as in Ghostty. Survives terminal resets. */
    public var keyboardLockAllowed: Boolean = false
    public val keyboardInputBlocked: Boolean get() = keyboardLockAllowed && keyboardDisabled
    public var reverseWrap: Boolean = false
        private set
    public var reverseWrapExtended: Boolean = false
        private set
    private val primaryKeyboard = TerminalKeyboardStack()
    private val alternateKeyboard = TerminalKeyboardStack()
    private val keyboard: TerminalKeyboardStack get() = if (alternateScreen) alternateKeyboard else primaryKeyboard
    /** Active screen's Kitty progressive enhancement flags. */
    public val kittyKeyboardFlags: Int get() = keyboard.current
    /** xterm numeric modifyOtherKeys mode, matching the pinned Ghostty's mode-2 support. */
    public var modifyOtherKeysMode: Int = 0
        private set
    /** DEC mode 67: swap legacy Backspace and Ctrl+Backspace between DEL and BS. */
    public var backarrowKeyMode: Boolean = false
        private set
    /** DEC mode 1035: force numeric encoding, matching Ghostty's default keypad policy. */
    public var keypadNumericOverride: Boolean = true
        private set
    public var bracketedPaste: Boolean = false
        private set
    public var focusReporting: Boolean = false
        private set
    public var colorSchemeReporting: Boolean = false
        private set
    public var visibilityReporting: Boolean = false
        private set
    /** Unknown headless visibility defaults to true. Attached host surfaces supply actual state. */
    public var potentiallyVisible: Boolean = true
        private set
    private val visibilitySources = mutableMapOf<Any,Boolean>()
    public var pointerShape: TerminalPointerShape = TerminalPointerShape.TEXT
        private set
    public var inBandResizeReporting: Boolean = false
        private set
    public var leftRightMarginMode: Boolean = false
        private set
    /** Last committed host geometry; null until the first explicit resize. */
    public var cellSize: TerminalCellSize? = null
        private set
    /** Host theme classification; OSC palette overrides do not change it. */
    public var colorScheme: TerminalColorScheme = colorScheme
        private set
    public var mouseTracking: Int = 0
        private set
    public var sgrMouse: Boolean = false
        private set
    /** Active wire format; format mode bits remain independently queryable. */
    public var mouseFormat: TerminalMouseFormat = TerminalMouseFormat.X10
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
    internal fun graphicsAnchor(column: Int = cursorColumn, row: Int = cursorRow): TerminalGraphicsAnchor {
        require(row in screen.indices)
        return TerminalGraphicsAnchor(screen[row], column, alternateScreen)
    }
    /** Negative rows refer to retained history; an inactive screen has no viewport position. */
    internal fun graphicsAnchorPosition(anchor: TerminalGraphicsAnchor): TerminalPosition? {
        if (anchor.alternateScreen != alternateScreen) return null
        val line = anchor.line ?: return null
        val index = indexOfLine(line)
        return if (index < 0) null else TerminalPosition(anchor.column, index - historySize, false)
    }
    /** Accounted primary scrollback storage, including while the alternate screen is active. */
    public val historyStorageBytes: Long get() = history.storageBytes
    internal val cursorPendingWrap: Boolean get() = pendingWrap
    internal fun inspectedModes(): BooleanArray = booleanArrayOf(applicationCursor, origin, wrap,
        insertMode, newlineMode, bracketedPaste, focusReporting, sgrMouse, graphemeClustering, synchronizedOutput, cursorBlink, leftRightMarginMode)

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
    private var charset = TerminalCharsetState()
    private var previousCharacter: Int? = null
    private var statusLineSelected = false
    private var checksumReporting = false
    private var defaultChecksumFlags = 0
    private var checksumFlags = 0
    private var left = 0
    private var right = columns - 1
    // One saved bit per supported DEC mode; repeated saves overwrite rather than stack.
    private val savedModes = mutableMapOf<Int, Boolean>()
    private val sideEffectModes = mutableMapOf<Int, Boolean>()
    private var tabs = BooleanArray(columns) { it > 0 && it % 8 == 0 }
    private val params = IntArray(64)
    private val separators = BooleanArray(64)
    private var paramIndex = 0
    private var parameterCount = 0
    private var privateCsi = false
    // Transport budget checkpoints: ordinary text is checked every small byte slice, while
    // potentially expensive control operations are followed by an immediate checkpoint.
    private var controlEpoch = 0
    private var graphicsMediaBinding: Pair<Any, TerminalGraphicsMedia>? = null
    internal fun readGraphicsMedia(request: TerminalGraphicsMediaRequest): TerminalGraphicsMediaResult =
        graphicsMediaBinding?.second?.read(request) ?: TerminalGraphicsMediaResult.UNSUPPORTED

    /**
     * Attach the one host authorized to resolve Kitty file/shared-memory payloads on both
     * screens. Replacing a binding takes effect on the next command. Disposing an older
     * binding cannot detach its replacement; disposal does not remove already decoded images.
     */
    public fun attachGraphicsMedia(media: TerminalGraphicsMedia): TerminalDisposable {
        val binding = Any() to media
        graphicsMediaBinding = binding
        return TerminalDisposable { if (graphicsMediaBinding === binding) graphicsMediaBinding = null }
    }

    private val primaryGraphics = TerminalGraphicsState(this, ::damageAll)
    private val alternateGraphics = TerminalGraphicsState(this, ::damageAll)
    internal val graphicsState: TerminalGraphicsState get() = if (alternateScreen) alternateGraphics else primaryGraphics

    /** Host monotonic clock; publish frame damage through the same hold boundary as PTY output. */
    internal fun advanceGraphicsAnimations(now: Long): Long? {
        val delay = graphicsState.animationTick(now)
        flushDamage()
        return delay
    }
    // Keep recognition disabled until a graphics-capable host explicitly enables storage.
    private val graphicsApc = TerminalGraphicsApc(1024 * 1024) { graphicsState.execute(it) }
    internal val retainedGraphicsCommandBytes: Int get() = graphicsApc.retainedPayloadBytes
    internal fun setGraphicsStorageLimit(bytes: Int) {
        require(bytes >= 0)
        if (primaryGraphics.storage.limit == bytes && alternateGraphics.storage.limit == bytes) return
        graphicsApc.reset()
        primaryGraphics.setLimit(bytes); alternateGraphics.setLimit(bytes)
        flushDamage()
    }
    private val parser = VtParser(object : VtParser.Handler {
        override fun print(code: Int) = this@TerminalSession.print(code)
        override fun execute(code: Int) { controlEpoch++; executeControl(code) }
        override fun escape(final: Int, intermediates: Int) { controlEpoch++; escapeSequence(final, intermediates) }
        override fun apcStart() { if (graphicsState.storage.enabled) graphicsApc.start() else graphicsApc.reset() }
        override fun apcPut(code: Int) = graphicsApc.feed(code)
        override fun apcEnd(terminator: Int) { controlEpoch++; graphicsApc.end(terminator) }
        override fun csi(final: Int, command: VtParser) {
            controlEpoch++
            params.fill(0)
            for (i in 0 until command.count) params[i] = command.parameters[i].coerceAtLeast(0)
            command.colon.copyInto(separators)
            paramIndex = (command.count - 1).coerceAtLeast(0)
            parameterCount = command.count
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
                    "\$qs" -> sendInput(if (leftRightMarginMode) "\u001bP1\$r${left + 1};${right + 1}s\u001b\\" else "\u001bP0\$r\u001b\\")
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
    private val pointerObservers = mutableListOf<(TerminalPointerShape) -> Unit>()
    private val notificationObservers = mutableListOf<(TerminalNotification) -> Unit>()
    private val workingDirectoryObservers = mutableListOf<(String?) -> Unit>()
    private val semanticPromptObservers = mutableListOf<(TerminalSemanticPrompt) -> Unit>()
    private val progressObservers = mutableListOf<(TerminalProgressReport) -> Unit>()
    private val programStatusRegistry = TerminalProgramStatusRegistry()
    private var clipboardBinding: TerminalClipboardBinding? = null
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

    /** Observe application pointer changes; hosts apply platform/hover overrides separately. */
    public fun onPointerShape(observer: (TerminalPointerShape) -> Unit): TerminalDisposable {
        val listener: (TerminalPointerShape) -> Unit = { observer(it) }
        pointerObservers += listener
        return TerminalDisposable { pointerObservers.remove(listener) }
    }

    /**
     * Observe accepted OSC 7 reports, including repeats and explicit clearing. Events are immediate
     * during synchronized output. Reset clears [workingDirectoryUri] without emitting a report.
     */
    public fun onWorkingDirectory(observer: (String?) -> Unit): TerminalDisposable {
        val listener: (String?) -> Unit = { observer(it) }
        workingDirectoryObservers += listener
        return TerminalDisposable { workingDirectoryObservers.remove(listener) }
    }

    /** Accepted OSC 133 commands, delivered after applying state, including during render holds. */
    public fun onSemanticPrompt(observer: (TerminalSemanticPrompt) -> Unit): TerminalDisposable {
        val listener: (TerminalSemanticPrompt) -> Unit = { observer(it) }
        semanticPromptObservers += listener
        return TerminalDisposable { semanticPromptObservers.remove(listener) }
    }

    /** Synchronous protocol events; the host controls permission and desktop delivery. */
    public fun onNotification(observer: (TerminalNotification) -> Unit): TerminalDisposable {
        val listener: (TerminalNotification) -> Unit = { observer(it) }
        notificationObservers += listener
        return TerminalDisposable { notificationObservers.remove(listener) }
    }

    /** Includes REMOVE on reset, even if no progress was previously reported. */
    public fun onProgress(observer: (TerminalProgressReport) -> Unit): TerminalDisposable {
        val listener: (TerminalProgressReport) -> Unit = { observer(it) }
        progressObservers += listener
        return TerminalDisposable { progressObservers.remove(listener) }
    }

    private fun reportProgress(report: TerminalProgressReport) { progressObservers.toList().forEach { it(report) } }

    /** Oldest-updated first, at most 256 records. An absent id addresses the root. */
    public val programStatuses: List<TerminalProgramStatus> get() = programStatusRegistry.snapshot

    /** Resolve the nearest ancestor's app when a record does not specify its own. */
    public fun programStatusApp(id: String? = null): String? = programStatusRegistry.app(id)

    /**
     * Enable OSC 7501 while a host observes it. Events follow state updates, including during
     * render holds. Reports are ignored and support queries unanswered without a listener.
     * Disposing the last listener clears retained records. Call on the session thread.
     */
    public fun onProgramStatus(observer: (TerminalProgramStatusChange) -> Unit): TerminalDisposable =
        programStatusRegistry.observe(observer)

    internal fun finishProgramStatuses() { programStatusRegistry.finish() }

    /**
     * Attach the one host responsible for OSC 52. Replacing or disposing a binding cancels
     * pending authorization and read callbacks. No host means no clipboard access or replies.
     */
    public fun attachClipboard(clipboard: TerminalClipboard, policy: TerminalClipboardPolicy = TerminalClipboardPolicy.DENY): TerminalDisposable {
        clipboardBinding?.dispose()
        val binding = TerminalClipboardBinding(clipboard, policy) { reply ->
            emitInput(TerminalInputData.ProtocolReply(reply.encodeToByteArray()))
        }
        clipboardBinding = binding
        return TerminalDisposable {
            binding.dispose()
            if (clipboardBinding === binding) clipboardBinding = null
        }
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
    public fun configureTheme(theme: TerminalTheme, colorScheme: TerminalColorScheme = theme.inferredColorScheme()) {
        colors.configure(theme)
        configureColorScheme(colorScheme)
        damageAll(); flushDamage()
    }

    /** Notify subscribed applications after a host theme/configuration change. */
    public fun configureColorScheme(colorScheme: TerminalColorScheme) {
        this.colorScheme = colorScheme
        if (colorSchemeReporting) reportColorScheme()
    }

    private fun reportColorScheme() { sendInput("\u001b[?997;${colorScheme.report}n") }

    /**
     * Register a host surface. A shared session is potentially visible if any registered
     * surface is visible. Disposing the last surface marks it hidden; disposed handles are inert.
     * Rendering-only/headless viewports do not register automatically.
     */
    public fun attachVisibility(initiallyVisible: Boolean): TerminalVisibilityBinding {
        val token = Any()
        visibilitySources[token] = initiallyVisible
        refreshVisibility()
        return object : TerminalVisibilityBinding {
            override fun setVisible(visible: Boolean) {
                if (token !in visibilitySources || visibilitySources[token] == visible) return
                visibilitySources[token] = visible
                refreshVisibility()
            }
            override fun dispose() {
                if (visibilitySources.remove(token) != null) refreshVisibility()
            }
        }
    }

    private fun refreshVisibility() {
        val visible = visibilitySources.values.any { it }
        if (visible == potentiallyVisible) return
        potentiallyVisible = visible
        if (visibilityReporting) reportVisibility()
    }

    private fun reportVisibility() { sendInput("\u001b[?999;${if (potentiallyVisible) 1 else 2}n") }

    /**
     * Opt in to DECRQCRA screen readback. Disabled by default, as in Ghostty: a program can
     * query individual cells to recover other programs' output. Also sets the current and
     * reset-default XTCHECKSUM bits: 1 positive, 2 omit attributes, 4 keep blanks, 8 count
     * undrawn cells, 16 full codepoints. The terminal program may override these until reset.
     */
    public fun configureChecksumReporting(enabled: Boolean, extension: Int = 0) {
        require(extension in 0..31)
        checksumReporting = enabled
        defaultChecksumFlags = extension; checksumFlags = extension
    }

    private fun reportChecksum() {
        if (!checksumReporting) return
        fun coordinate(index: Int, default: Int, margin: Int, max: Int): Int =
            ((if (index >= parameterCount || params[index] == 0) default else params[index]) + margin).coerceIn(1,max) - 1
        val yMargin = if (origin) top else 0
        val xMargin = if (origin) left else 0
        val y1 = coordinate(2,1,yMargin,rows); val x1 = coordinate(3,1,xMargin,columns)
        val y2 = coordinate(4,rows,yMargin,rows); val x2 = coordinate(5,columns,xMargin,columns)
        val sum = terminalChecksum(screen,x1,y1,x2,y2,checksumFlags)
        sendInput("\u001bP${if (parameterCount == 0) 0 else params[0]}!~${sum.toString(16).uppercase().padStart(4,'0')}\u001b\\")
    }

    private fun reportSize(style: Int = 48) {
        val cell = cellSize ?: return
        val dimensions = when (style) {
            48 -> "$rows;$columns;${rows.toLong() * cell.height};${columns.toLong() * cell.width}"
            4 -> "${rows.toLong() * cell.height};${columns.toLong() * cell.width}"
            6 -> "${cell.height};${cell.width}"
            8, 9 -> "$rows;$columns"
            else -> return
        }
        sendInput("\u001b[$style;${dimensions}t")
    }

    /** Clear retained primary history, including when a full-screen application is active. */
    public fun clearHistory() {
        flushSynchronizedOutput()
        history.clear(); primaryGraphics.reapRetired(); selectionEpoch++
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
            row.wrapped = false; row.wrapContinuation = false; row.promptMarker = TerminalPromptMarker.NONE
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

    /** Flush an incomplete character/render hold and retire running program statuses at EOF. */
    public fun finishInput() {
        if (utfNeeded > 0 || highSurrogate != null) {
            utfNeeded = 0; highSurrogate = null; accept(0xfffd); damage(cursorRow); flushDamage()
        }
        flushSynchronizedOutput()
        finishProgramStatuses()
    }

    /** Reflow primary text and history; full-screen alternate applications retain their physical grid. */
    public fun resize(columns: Int, rows: Int, cellSize: TerminalCellSize = this.cellSize ?: TerminalCellSize(0, 0)) {
        validateSize(columns, rows)
        flushSynchronizedOutput()
        this.cellSize = cellSize
        if (columns == this.columns && rows == this.rows) {
            if (inBandResizeReporting) reportSize()
            return
        }
        selectionEpoch++
        fun SavedCursor.position() = TerminalPosition(x, y, wrap)
        fun SavedCursor.at(position: TerminalPosition?) = if (position == null) this else
            copy(x = position.x, y = position.y, wrap = position.pendingWrap)
        fun resized(old: Array<TerminalLine>, cursor: SavedCursor, saved: SavedCursor, primary: Boolean): TerminalResizeResult {
            val result = resizeTerminal(old, if (primary) (0 until history.size).map { history[it] } else emptyList(),
                cursor.position(), saved.takeIf { it.valid }?.position(), columns, rows,
                if (primary) scrollback else 0, limits.historyBytes, reflow = primary)
            val state = if (primary) primarySemantic else alternateSemantic
            if (primary && state.content != TerminalSemanticContent.OUTPUT && promptRedraw != TerminalPromptRedraw.NONE) {
                val lines = result.history + result.screen
                val current = result.history.size + result.cursor.y
                if (promptRedraw == TerminalPromptRedraw.LAST) lines[current].clear(0, columns, -1, -1, 0)
                else {
                    var start = current
                    while (start >= 0 && lines[start].promptMarker == TerminalPromptMarker.NONE) start--
                    if (start >= 0) {
                        if (lines[start].promptMarker == TerminalPromptMarker.CONTINUATION) {
                            val continuation = start
                            while (start > 0 && lines[start].promptMarker == TerminalPromptMarker.CONTINUATION &&
                                lines[start - 1].promptMarker != TerminalPromptMarker.NONE) start--
                            // Match Ghostty's iterator when scrollback starts with an orphan continuation.
                            if (start == 0 && lines[start].promptMarker == TerminalPromptMarker.CONTINUATION) start = continuation
                        }
                        for (y in start until lines.size) lines[y].clear(0, columns, -1, -1, 0)
                    }
                }
            }
            if (primary) {
                scrolledLines += (result.history.size - history.size).coerceAtLeast(0)
                history.clear(preserveAnchors = true)
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
        top = 0; bottom = rows - 1; left = 0; right = columns - 1
        tabs = BooleanArray(columns) { if (it < tabs.size) tabs[it] else it % 8 == 0 }
        damageAll(); flushDamage()
        resizeObservers.toList().forEach { it(columns, rows) }
        if (inBandResizeReporting) reportSize()
    }

    public fun reset() {
        statusLineSelected = false; checksumFlags = defaultChecksumFlags
        colors.resetPalette()
        clipboardBinding?.reset()
        primaryGraphics.reset(); alternateGraphics.reset()
        previousCharacter = null
        savedModes.clear(); sideEffectModes.clear()
        modifyOtherKeysMode = 0
        backarrowKeyMode = false
        primaryKeyboard.reset(); alternateKeyboard.reset()
        primarySemantic.reset(); alternateSemantic.reset(); promptRedraw = TerminalPromptRedraw.ALL
        selectionEpoch++; screenEpoch++
        screen.forEach { it.invalidateGraphicsAnchors() }
        primary?.forEach { it.invalidateGraphicsAnchors() }
        alternate?.forEach { it.invalidateGraphicsAnchors() }
        history.clear(); primary = null; alternate = null; alternateScreen = false
        screen = Array(rows) { TerminalLine(columns, colors, limits.hyperlinkBytesPerLine) }
        cursorColumn = 0; cursorRow = 0; cursorVisible = true
        cursorStyle = defaultCursorStyle; primaryCursorStyle = defaultCursorStyle; cursorBlink = defaultCursorBlink
        top = 0; bottom = rows - 1; fg = -1; bg = -1; style = 0; attributes = null
        protectedCell = false; protectionMode = 0; primaryProtectionMode = 0; alternateProtectionMode = 0
        origin = false; wrap = true; pendingWrap = false; insertMode = false; charset = TerminalCharsetState()
        applicationCursor = false; bracketedPaste = false; focusReporting = false
        keyboardDisabled = false; reverseWrap = false; reverseWrapExtended = false
        colorSchemeReporting = false; visibilityReporting = false
        inBandResizeReporting = false
        leftRightMarginMode = false; left = 0; right = columns - 1
        applicationKeypad = false; keypadNumericOverride = true; alternateScroll = true
        mouseTracking = 0; sgrMouse = false; mouseFormat = TerminalMouseFormat.X10
        mouseEpoch++; newlineMode = false; graphemeClustering = defaultGraphemeClustering
        parser.reset(); utfNeeded = 0; highSurrogate = null; title = ""
        workingDirectoryUri = null
        saved = SavedCursor(); primaryCursor = SavedCursor(); alternateCursor = SavedCursor()
        primarySaved = SavedCursor(); alternateSaved = SavedCursor()
        tabs = BooleanArray(columns) { it > 0 && it % 8 == 0 }
        damageAll(); flushSynchronizedOutput(); flushDamage()
        reportProgress(TerminalProgressReport(TerminalProgressState.REMOVE))
        programStatusRegistry.reset()
    }

    /** DECSTR changes the active pen and selected modes without moving or clearing the screen. */
    private fun softReset() {
        statusLineSelected = false; checksumFlags = defaultChecksumFlags
        cursorVisible = true; insertMode = false; origin = false; wrap = true
        keyboardDisabled = false; reverseWrap = false; reverseWrapExtended = false
        applicationCursor = false; applicationKeypad = false; leftRightMarginMode = false
        top = 0; bottom = rows - 1; left = 0; right = columns - 1
        fg = -1; bg = -1; style = 0
        setAttributes(-1, attributes?.hyperlink)
        protectedCell = false; protectionMode = 0
        charset = TerminalCharsetState()
        // An unset Ghostty saved cursor restores home/defaults; leave the live cursor intact.
        saved = SavedCursor()
        modifyOtherKeysMode = 0
        cursorStyle = defaultCursorStyle; cursorBlink = defaultCursorBlink
        colors.resetPalette()
        damageAll()
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
            8 -> cursorLeft(1)
            9 -> tab(1)
            10, 11, 12 -> { lineFeed(); if (newlineMode) carriageReturn() }
            13 -> carriageReturn()
            14 -> charset = charset.copy(gl = 1)
            15 -> charset = charset.copy(gl = 0)
        }
    }

    private fun escapeSequence(final: Int, intermediates: Int) {
        when (intermediates) {
            '('.code, ')'.code, '*'.code, '+'.code -> {
                val set = when (final.toChar()) {
                    'B' -> TerminalCharset.ASCII
                    'A' -> TerminalCharset.BRITISH
                    '0' -> TerminalCharset.DEC_SPECIAL
                    else -> return
                }
                charset = charset.designate(intermediates - '('.code, set)
            }
            '#'.code -> if (final == '8'.code) {
                style = 0; setAttributes(-1, attributes?.hyperlink)
                top = 0; bottom = rows - 1; left = 0; right = columns - 1; origin = false
                cursorColumn = 0; cursorRow = 0; pendingWrap = false
                for (line in screen) {
                    line.wrapped = false; line.wrapContinuation = false; line.promptMarker = TerminalPromptMarker.NONE
                    for (x in 0 until columns) line.put(x, 'E'.code, 1, fg, bg, 0)
                }
                damageAll()
            }
            0 -> when (final.toChar()) {
                '7' -> saved = saveCursor()
                '8' -> restoreCursor(saved)
                'D' -> lineFeed()
                'E' -> { lineFeed(); carriageReturn() }
                'M' -> reverseIndex()
                'H' -> tabs[cursorColumn] = true
                'N' -> charset = charset.copy(singleShift = 2)
                'O' -> charset = charset.copy(singleShift = 3)
                'n' -> charset = charset.copy(gl = 2)
                'o' -> charset = charset.copy(gl = 3)
                '~' -> charset = charset.copy(gr = 1)
                '}' -> charset = charset.copy(gr = 2)
                '|' -> charset = charset.copy(gr = 3)
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
        if (prefix == 0 && final == 'y') {
            if (intermediates == '*'.code && parameterCount <= 6) reportChecksum()
            else if (intermediates == '#'.code && parameterCount <= 1 && checksumReporting)
                checksumFlags = (if (parameterCount == 0) 0 else params[0]) and 31
            return
        }
        if (prefix == 0 && final == '}' && intermediates == '$'.code && parameterCount == 1) {
            if (params[0] in 0..1) statusLineSelected = params[0] == 1
            return
        }
        if (prefix == 0 && intermediates == '!'.code && final == 'p') {
            if (parameterCount == 0) softReset()
            return
        }
        if (prefix == '?'.code && intermediates == 0 && final in "sr") {
            for (i in 0 until parameterCount) {
                val number = params[i]
                val current = privateModeState(number) ?: continue
                if (final == 's') savedModes[number] = current
                else mode(number, savedModes[number] ?: (number in listOf(7, 25, 1007, 1035)))
            }
            return
        }
        if (prefix == '>'.code && intermediates == 0 && final in "mn") {
            if (final == 'n') modifyOtherKeysMode = 0
            else if (parameterCount <= 2 && params[0] in listOf(0, 1, 2, 4)) {
                modifyOtherKeysMode = if (params[0] == 4 && parameterCount == 2 && params[1] == 2) 2 else 0
            }
            return
        }
        if (final == 'u' && intermediates == 0 && prefix != 0) {
            if ((0..paramIndex).any { separators[it] }) return
            when (prefix.toChar()) {
                '?' -> sendInput("\u001b[?$kittyKeyboardFlags" + "u")
                '>' -> if (parameterCount != 1) keyboard.push(0) else if (params[0] <= 31) keyboard.push(params[0])
                '<' -> keyboard.pop(if (parameterCount == 1) params[0] else 1)
                '=' -> if (params[0] <= 31) keyboard.set(params[0], if (parameterCount < 2) 1 else params[1])
            }
            return
        }
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
        if (statusLineSelected) return
        val activeCharset = charset.active
        // Consume even for a combining/zero-width codepoint that never occupies a new cell.
        if (charset.singleShift != null) charset = charset.copy(singleShift = null)
        val rightLimit = if (cursorColumn > right) columns else right + 1
        // Width and grapheme decisions precede legacy charset mapping in Ghostty.
        val code = input
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
        previousCharacter = input
        if (pendingWrap && wrap) softWrap()
        pendingWrap = false
        if (insertMode && cursorColumn + width < columns) insertCharacters(width)
        if (width == 2 && rightLimit - left < 2) {
            // Ghostty consumes a wide character as a styled blank when the
            // scrolling region cannot hold its two cells, without wrapping first.
            eraseWideAt(screen[cursorRow], cursorColumn)
            screen[cursorRow].putBlank(cursorColumn, fg, bg, style, attributes, protectedCell, semantic.content)
            damage(cursorRow)
            if (cursorColumn == rightLimit - 1) pendingWrap = true else cursorColumn++
            return
        }
        if (width == 2 && cursorColumn == rightLimit - 1 && wrap) {
            eraseWideAt(screen[cursorRow], cursorColumn)
            if (rightLimit == columns) screen[cursorRow].putSpacerHead(cursorColumn, fg, bg, style, attributes, protectedCell, semantic.content)
            else screen[cursorRow].putBlank(cursorColumn, fg, bg, style, attributes, protectedCell, semantic.content)
            softWrap()
        }
        if (width == 2 && cursorColumn == rightLimit - 1) return
        val line = screen[cursorRow]
        eraseWideAt(line, cursorColumn)
        if (width == 2) eraseWideAt(line, cursorColumn + 1)
        line.put(cursorColumn, activeCharset.map(code), width, fg, bg, style, attributes, protectedCell, semantic.content)
        if (width == 2) line.put(cursorColumn + 1, 0, 0, fg, bg, style, attributes, protectedCell, semantic.content)
        damage(cursorRow)
        cursorColumn += width
        if (cursorColumn >= rightLimit) { cursorColumn = rightLimit - 1; pendingWrap = true }
    }

    private fun appendGrapheme(code: Int): Boolean {
        val rightLimit = if (cursorColumn > right) columns else right + 1
        var line = screen[cursorRow]
        var x = when {
            wrap -> cursorColumn - if (pendingWrap) 0 else 1
            cursorColumn == rightLimit - 1 && line.hasText(cursorColumn) -> cursorColumn
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
                if (x == rightLimit - 1) {
                    if (!wrap) return true
                    // A wrap can recycle the source row; retain the bounded cluster first.
                    val cluster = TerminalLine(1, colors, limits.hyperlinkBytesPerLine).also { it.copyCell(line, x, 0) }
                    if (rightLimit == columns) line.putSpacerHead(x, fg, bg, style, attributes, protectedCell, semantic.content)
                    else line.putBlank(x, fg, bg, style, attributes, protectedCell, semantic.content)
                    damage(cursorRow)
                    softWrap(); x = left; line = screen[cursorRow]
                    eraseWideAt(line, x)
                    line.put(x, cluster.codePoint(0), 2, fg, bg, style, attributes, protectedCell, semantic.content)
                    line.copyGrapheme(cluster, 0, x)
                } else line.setWidth(x, 2)
                eraseWideAt(line, x + 1)
                line.put(x + 1, 0, 0, fg, bg, style, attributes, protectedCell, semantic.content)
                cursorColumn = minOf(x + 2, rightLimit - 1)
                pendingWrap = x + 2 >= rightLimit
            }
            1 -> if (line.width(x) == 2) {
                line.setWidth(x, 1)
                line.setWidth(x + 1, 1)
                pendingWrap = false; cursorColumn = minOf(x + 1, rightLimit - 1)
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
        if (cursorRow == bottom) {
            if (cursorColumn in left..right) scrollUp(1)
        } else cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
        if (semantic.content != TerminalSemanticContent.OUTPUT) {
            if (semantic.clearEol) { semantic.content = TerminalSemanticContent.OUTPUT; semantic.clearEol = false }
            else screen[cursorRow].promptMarker = TerminalPromptMarker.CONTINUATION
        }
    }

    internal fun advanceGraphicsCursor(grid: TerminalGraphicsSize) {
        val beforeScroll = if (cursorRow in top..bottom && cursorColumn in left..right) bottom - cursorRow else 0
        val advance = graphicsCursorAdvance(grid, cursorColumn, columns, rows, beforeScroll)
        repeat(advance.indexRows) { lineFeed() }
        cursorColumn = advance.column
        pendingWrap = false
    }

    private fun softWrap() {
        val fullRow = cursorColumn == columns - 1
        if (fullRow) screen[cursorRow].wrapped = true
        val content = semantic.content
        val clearEol = semantic.clearEol
        lineFeed(); cursorColumn = left
        semantic.content = content; semantic.clearEol = clearEol
        if (content == TerminalSemanticContent.PROMPT) screen[cursorRow].promptMarker = TerminalPromptMarker.CONTINUATION
        if (fullRow) screen[cursorRow].wrapContinuation = true
    }

    private fun carriageReturn() {
        cursorColumn = if (origin || cursorColumn >= left) left else 0
        pendingWrap = false
    }

    private fun position(row: Int, column: Int) {
        cursorRow = (row.coerceAtLeast(1) - 1 + if (origin) top else 0).coerceAtMost(if (origin) bottom else rows - 1)
        cursorColumn = (column.coerceAtLeast(1) - 1 + if (origin) left else 0).coerceAtMost(if (origin) right else columns - 1)
        pendingWrap = false
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == top && cursorColumn in left..right) scrollDown(1)
        else cursorRow = (cursorRow - 1).coerceAtLeast(if (cursorRow >= top) top else 0)
    }

    private fun scrollUp(count: Int, retainHistory: Boolean = true, adjustGraphics: Boolean = true) {
        val fullScreen = top == 0 && bottom == rows - 1 && left == 0 && right == columns - 1
        val restore = if (!adjustGraphics) ({})
            else if (fullScreen && (alternateScreen || scrollback == 0 || limits.historyBytes == 0L))
                graphicsState.scrollWithoutHistory(count.coerceAtMost(rows))
            else graphicsState.scrollMargins(top, bottom, left, right, -count.coerceAtMost(bottom - top + 1))
        if (left != 0 || right != columns - 1) { scrollRectangle(count, down = false); restore(); return }
        if (top != 0 || bottom != rows - 1 || alternateScreen) lineOrderEpoch++
        repeat(count.coerceAtMost(bottom - top + 1)) {
            val old = screen[top]
            val spare = if (retainHistory && top == 0 && !alternateScreen) {
                scrolledLines++; history.add(old)
            } else old
            for (y in top until bottom) screen[y] = screen[y + 1]
            screen[bottom] = (spare ?: TerminalLine(columns, colors, limits.hyperlinkBytesPerLine)).also { it.recycleEpoch++; it.clear(0, columns, -1, bg, 0); it.wrapped = false; it.wrapContinuation = false; it.promptMarker = TerminalPromptMarker.NONE }
        }
        restore()
        damage(top, bottom)
    }

    private fun scrollDown(count: Int, adjustGraphics: Boolean = true) {
        // Ghostty's full-screen SD/RI uses IL's stable pin coordinates; margin scrolls adjust them.
        val restore = if (!adjustGraphics) ({})
            else if (top == 0 && bottom == rows - 1 && left == 0 && right == columns - 1) graphicsState.preservePositions()
            else graphicsState.scrollMargins(top, bottom, left, right, count.coerceAtMost(bottom - top + 1))
        if (left != 0 || right != columns - 1) { scrollRectangle(count, down = true); restore(); return }
        lineOrderEpoch++
        repeat(count.coerceAtMost(bottom - top + 1)) {
            val spare = screen[bottom]
            for (y in bottom downTo top + 1) screen[y] = screen[y - 1]
            screen[top] = spare.also { it.recycleEpoch++; it.clear(0, columns, -1, bg, 0); it.wrapped = false; it.wrapContinuation = false; it.promptMarker = TerminalPromptMarker.NONE }
        }
        restore()
        damage(top, bottom)
    }

    private fun scrollRectangle(count: Int, down: Boolean) {
        val n = count.coerceAtMost(bottom - top + 1)
        lineOrderEpoch++
        for (y in top..bottom) {
            splitCellBoundary(y, left, keepAttributes = true); splitCellBoundary(y, right + 1, keepAttributes = true)
            if ((right == columns - 1 || left < 2) && screen[y].isSpacerHead(columns - 1)) screen[y].replaceWithBlank(columns - 1)
        }
        val order = if (down) bottom downTo top else top..bottom
        for (y in order) {
            val source = y + if (down) -n else n
            if (source in top..bottom) for (x in left..right) screen[y].copyCell(screen[source], x, x)
            else screen[y].clear(left, right + 1, -1, bg, 0)
        }
        damage(top, bottom)
    }

    private fun splitCellBoundary(row: Int, column: Int, keepAttributes: Boolean = false) {
        val line = screen[row]
        if (column in 1 until columns && line.width(column - 1) == 2) {
            if (keepAttributes) { line.replaceWithBlank(column - 1); line.replaceWithBlank(column) }
            else line.clear(column - 1, column + 1, -1, bg, 0)
            damage(row)
        }
    }

    private fun csi(final: Char) {
        fun p(i: Int = 0, default: Int = 1): Int = if (i > paramIndex || params[i] == 0) default else params[i]
        val minY = if (origin) top else 0
        val minX = if (origin) left else 0
        val positionRow = (cursorRow - minY).coerceAtLeast(0) + 1
        val positionColumn = (cursorColumn - minX).coerceAtLeast(0) + 1
        val upLimit = if (cursorRow >= top) top else 0
        val downLimit = if (cursorRow <= bottom) bottom else rows - 1
        val selective = privateCsi || protectionMode == PROTECTION_ISO
        when (final) {
            'A' -> cursorRow = (cursorRow - p()).coerceAtLeast(upLimit)
            'B' -> cursorRow = (cursorRow + p()).coerceAtMost(downLimit)
            'e' -> position(positionRow + p(), positionColumn)
            'C' -> cursorColumn = (cursorColumn + p()).coerceAtMost(if (cursorColumn <= right) right else columns - 1)
            'a' -> position(positionRow, positionColumn + p())
            'D' -> cursorLeft(p())
            'E' -> { cursorRow = (cursorRow + p()).coerceAtMost(downLimit); carriageReturn() }
            'F' -> { cursorRow = (cursorRow - p()).coerceAtLeast(upLimit); carriageReturn() }
            'G', '`' -> position(positionRow, p())
            'd' -> position(p(), positionColumn)
            'H', 'f' -> position(p(), p(1))
            'J' -> when (params[0]) {
                0 -> { eraseLine(0, selective); eraseRows(cursorRow + 1, rows, selective) }
                1 -> { eraseRows(0, cursorRow, selective); eraseLine(1, selective) }
                2 -> {
                    val lastContent = screen.indexOfLast { it.promptMarker != TerminalPromptMarker.NONE || it.hasText() }
                    if (!alternateScreen && lastContent >= 0 && screen[lastContent].promptMarker != TerminalPromptMarker.NONE) scrollAndClear()
                    eraseRows(0, rows, selective); pendingWrap = false
                    graphicsState.clearScreen()
                }
                3 -> { history.clear(); primaryGraphics.reapRetired(); damageAll() }
                22 -> if (parameterCount == 1) scrollAndClear()
            }
            'K' -> eraseLine(params[0], selective)
            'X' -> {
                eraseCells(cursorRow, cursorColumn, (cursorColumn + p()).coerceAtMost(columns),
                    protectionMode == PROTECTION_ISO, splitBoundary = true)
                resetCursorWrap()
            }
            '@' -> insertCharacters(p())
            'P' -> if (cursorColumn in left..right) {
                val n = p().coerceAtMost(right + 1 - cursorColumn)
                val line = screen[cursorRow]
                splitCellBoundary(cursorRow, cursorColumn); splitCellBoundary(cursorRow, cursorColumn + n)
                splitCellBoundary(cursorRow, right + 1)
                for (x in cursorColumn until right + 1 - n) line.copyCell(line, x + n, x)
                line.clear(right + 1 - n, right + 1, -1, bg, 0)
                resetCursorWrap(); damage(cursorRow)
            }
            'L', 'M' -> if (cursorRow in top..bottom && cursorColumn in left..right) {
                val restoreGraphics = graphicsState.preservePositions()
                val oldTop = top; top = cursorRow
                if (final == 'L') scrollDown(p(), adjustGraphics = false) else scrollUp(p(), retainHistory = false, adjustGraphics = false)
                restoreGraphics()
                if (left == 0 && right == columns - 1) for (y in top..bottom) {
                    screen[y].wrapped = false; screen[y].wrapContinuation = false
                }
                top = oldTop
                cursorColumn = left; pendingWrap = false
            }
            'S' -> scrollUp(p())
            'T' -> scrollDown(p())
            'b' -> if (parameterCount <= 1) previousCharacter?.let { code -> repeat(p()) { print(code) } }
            'r' -> if (!privateCsi && parameterCount <= 2) {
                val first = p() - 1; val last = p(1, rows).coerceAtMost(rows) - 1
                if (first in 0 until rows && last in 0 until rows && first < last) {
                    top = first; bottom = last; position(1, 1)
                }
            }
            's' -> if (parameterCount == 0 && !leftRightMarginMode) saved = saveCursor()
                else if (leftRightMarginMode && parameterCount <= 2) {
                    val first = p() - 1; val last = p(1, columns).coerceAtMost(columns) - 1
                    if (first < last) { left = first; right = last; position(1, 1) }
                }
            'u' -> restoreCursor(saved)
            'm' -> sgr()
            'h', 'l' -> for (i in 0..paramIndex) mode(params[i], final == 'h')
            'g' -> if (parameterCount <= 1) {
                if (params[0] == 3) tabs.fill(false) else if (params[0] == 0) tabs[cursorColumn] = false
            }
            'I' -> tab(p())
            'Z' -> repeat(p().coerceAtMost(columns)) {
                if (cursorColumn > minX) do { cursorColumn-- } while (cursorColumn > minX && !tabs[cursorColumn])
            }
            'n' -> if (parameterCount == 1) when (params[0]) {
                5 -> if (!privateCsi) sendInput("\u001b[0n")
                6 -> sendInput("\u001b[${if (privateCsi) "?" else ""}$positionRow;$positionColumn${if (privateCsi) ";1" else ""}R")
                996 -> if (privateCsi) reportColorScheme()
                998 -> if (privateCsi) reportVisibility()
            }
            't' -> if (parameterCount == 1) when (params[0]) {
                14 -> reportSize(4)
                16 -> reportSize(6)
                18 -> reportSize(8)
                19 -> reportSize(9)
            }
        }
        // Tab movement/clearing preserves pending wrap, even after leaving the margin.
        if (final !in "mhsncluJKbtrLMSTPIZg") pendingWrap = false
    }

    private fun scrollAndClear() {
        val count = screen.indexOfLast { line -> (0 until columns).any { !line.isEmpty(it) } } + 1
        val oldTop = top; val oldBottom = bottom; val oldBackground = bg; val oldLeft = left; val oldRight = right
        top = 0; bottom = rows - 1; left = 0; right = columns - 1; bg = -1
        scrollUp(count)
        top = oldTop; bottom = oldBottom; bg = oldBackground; left = oldLeft; right = oldRight
        if (cursorRow < count) { cursorRow = 0; cursorColumn = 0 }
        else cursorRow -= count
        pendingWrap = false
        graphicsState.clearScreen()
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
            if (!selective) { screen[y].wrapped = false; screen[y].wrapContinuation = false; screen[y].promptMarker = TerminalPromptMarker.NONE }
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
        pendingWrap = false
        if (cursorColumn !in left..right) return
        val n = count.coerceAtMost(right + 1 - cursorColumn)
        val line = screen[cursorRow]
        splitCellBoundary(cursorRow, cursorColumn); splitCellBoundary(cursorRow, right + 1)
        splitCellBoundary(cursorRow, right + 1 - n)
        for (x in right downTo cursorColumn + n) line.copyCell(line, x - n, x)
        line.clear(cursorColumn, cursorColumn + n, -1, bg, 0); line.repairWideCells(); damage(cursorRow)
    }

    // Ghostty Terminal.cursorLeft: BS/CUB share reverse-wrap and pending-wrap semantics.
    private fun cursorLeft(requested: Int) {
        val leftMargin = if (cursorColumn < left) 0 else left
        if (!wrap || !reverseWrap && !reverseWrapExtended) {
            cursorColumn = (cursorColumn - requested).coerceAtLeast(leftMargin)
            pendingWrap = false
            return
        }
        var remaining = requested
        if (pendingWrap) {
            remaining--; pendingWrap = false
            if (remaining == 0) return
        }
        if (!reverseWrapExtended && cursorColumn == leftMargin && cursorRow <= top) {
            cursorRow = top
            return
        }
        while (true) {
            val moved = minOf(cursorColumn - leftMargin,remaining)
            cursorColumn -= moved; remaining -= moved
            if (remaining == 0) return
            if (cursorRow == top) {
                if (!reverseWrapExtended) return
                cursorColumn = right; cursorRow = bottom; remaining--
                continue
            }
            if (cursorRow == 0 || !reverseWrapExtended && !screen[cursorRow - 1].wrapped) return
            cursorColumn = right; cursorRow--; remaining--
        }
    }

    private fun tab(count: Int) {
        repeat(count.coerceAtMost(columns)) {
            if (cursorColumn < right) do { cursorColumn++ } while (cursorColumn < right && !tabs[cursorColumn])
        }
    }

    private fun mode(number: Int, enabled: Boolean) {
        if (!privateCsi) {
            when (number) { 2 -> keyboardDisabled = enabled; 4 -> insertMode = enabled; 20 -> newlineMode = enabled }
            return
        }
        if (number in listOf(3, 9, 47, 1000, 1002, 1003, 1005, 1006, 1015, 1016, 1047, 1048, 1049)) sideEffectModes[number] = enabled
        when (number) {
            1 -> applicationCursor = enabled
            3 -> {
                top = 0; bottom = rows - 1; left = 0; right = columns - 1; cursorColumn = 0; cursorRow = 0; pendingWrap = false
                for (line in screen) { line.clear(0, columns, -1, -1, 0); line.wrapped = false; line.wrapContinuation = false }
                damageAll()
            }
            6 -> { origin = enabled; position(1, 1) }
            69 -> { leftRightMarginMode = enabled; if (!enabled) { left = 0; right = columns - 1 } }
            7 -> wrap = enabled
            12 -> cursorBlink = enabled
            25 -> cursorVisible = enabled
            45 -> reverseWrap = enabled
            66 -> applicationKeypad = enabled
            67 -> backarrowKeyMode = enabled
            1035 -> keypadNumericOverride = enabled
            1045 -> reverseWrapExtended = enabled
            47, 1047, 1049 -> switchScreen(number, enabled)
            1048 -> if (enabled) saved = saveCursor() else restoreCursor(saved)
            9, 1000, 1002, 1003 -> { mouseTracking = if (enabled) number else 0; mouseEpoch++ }
            1004 -> focusReporting = enabled
            1005, 1006, 1015, 1016 -> {
                if (number == 1006) sgrMouse = enabled
                mouseFormat = if (!enabled) TerminalMouseFormat.X10 else when (number) {
                    1005 -> TerminalMouseFormat.UTF8
                    1006 -> TerminalMouseFormat.SGR
                    1015 -> TerminalMouseFormat.URXVT
                    else -> TerminalMouseFormat.SGR_PIXELS
                }
                mouseEpoch++
            }
            1007 -> alternateScroll = enabled
            2004 -> bracketedPaste = enabled
            2026 -> setSynchronizedOutput(enabled)
            2027 -> graphemeClustering = enabled
            2031 -> colorSchemeReporting = enabled
            2033 -> { visibilityReporting = enabled; if (enabled) reportVisibility() }
            2048 -> { inBandResizeReporting = enabled; if (enabled) reportSize() }
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
        if (style and TerminalStyle.BLINK != 0) append(";5")
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

    private fun privateModeState(number: Int): Boolean? = when (number) {
        3, 9, 47, 1000, 1002, 1003, 1005, 1015, 1016, 1047, 1048, 1049 -> sideEffectModes[number] ?: false
        1 -> applicationCursor
        6 -> origin
        7 -> wrap
        12 -> cursorBlink
        25 -> cursorVisible
        45 -> reverseWrap
        66 -> applicationKeypad
        67 -> backarrowKeyMode
        69 -> leftRightMarginMode
        1035 -> keypadNumericOverride
        1045 -> reverseWrapExtended
        1004 -> focusReporting
        1006 -> sgrMouse
        1007 -> alternateScroll
        2004 -> bracketedPaste
        2026 -> synchronizedOutput
        2027 -> graphemeClustering
        2031 -> colorSchemeReporting
        2033 -> visibilityReporting
        2048 -> inBandResizeReporting
        else -> null
    }

    private fun reportMode(number: Int, privateMode: Boolean) {
        val enabled = if (privateMode) privateModeState(number) else when (number) {
            2 -> keyboardDisabled
            4 -> insertMode
            20 -> newlineMode
            else -> null
        }
        // Ghostty recognizes these unsupported modes as fixed reset, rather than unknown.
        val fixedReset = if (privateMode) number == 117 else when (number) {
            1, 5, 7, 10, 11, 13, 14, 15, 16, 17, 18, 19 -> true
            else -> false
        }
        val state = if (fixedReset) 4 else when (enabled) { true -> 1; false -> 2; null -> 0 }
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
            if (mode != 1049 || enabled) {
                (if (enabled) alternateSemantic else primarySemantic).copyCursor(semantic)
            }
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
        graphicsState.clearScreen()
    }

    private fun sgr() {
        var i = 0
        while (i <= paramIndex) {
            var end = i
            while (end < paramIndex && separators[end]) end++
            // A trailing colon has no subparameter; Ghostty ignores that incomplete group.
            if (separators[i] && end == i) { i++; continue }
            val subparameters = end > i
            when (val p = params[i]) {
                0 -> { fg = -1; bg = -1; style = 0; setAttributes(-1, attributes?.hyperlink) }
                1 -> style = style or TerminalStyle.BOLD
                2 -> style = style or TerminalStyle.FAINT
                3 -> style = style or TerminalStyle.ITALIC
                5, 6 -> style = style or TerminalStyle.BLINK
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
                25 -> style = style and TerminalStyle.BLINK.inv()
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
        if (operation == 21) {
            if (colors.kittyOperation(payload, terminator, ::sendInput)) damageAll()
            return
        }
        if (operation != null && (operation == 4 || operation == 104 || operation in 10..19 || operation in 110..112)) {
            if (colors.operation(operation, payload, terminator, ::sendInput)) damageAll()
            return
        }
        if (separator < 1) return
        when (command) {
            "0", "2" -> title = payload
            "133" -> parseSemanticPrompt(payload)?.let(::applySemanticPrompt)
            "7" -> {
                // Ghostty's fixed OSC capture needs one byte for the terminating NUL.
                if (payload.encodeToByteArray().size >= 2048) return
                val uri = payload.takeIf { it.isNotEmpty() }
                workingDirectoryUri = uri
                workingDirectoryObservers.toList().forEach { it(uri) }
            }
            "52" -> clipboardBinding?.accept(payload, terminator)
            "7501" -> programStatusRegistry.accept(payload, terminator, ::sendInput)
            "9", "777" -> parseTerminalEffect(command.toInt(), payload,
                { notification -> notificationObservers.toList().forEach { it(notification) } }, ::reportProgress)
            "22" -> TerminalPointerShape.parse(payload)?.let { shape ->
                pointerShape = shape
                pointerObservers.toList().forEach { it(shape) }
            }
            "8" -> {
                val split = payload.indexOf(';')
                if (split < 0) return
                val uri = payload.substring(split + 1)
                val id = payload.substring(0, split).split(':').firstOrNull { it.startsWith("id=") }?.substring(3) ?: ""
                setAttributes(attributes?.underlineColor ?: -1, if (uri.isEmpty()) null else TerminalLink(TerminalHyperlink(id, uri)))
            }
        }
    }

    private fun applySemanticPrompt(command: TerminalSemanticPrompt) {
        val action = command.action
        if (action == TerminalPromptAction.NEW_PROMPT) finishProgramStatuses()
        if (action == TerminalPromptAction.FRESH_LINE || action == TerminalPromptAction.NEW_PROMPT || action == TerminalPromptAction.NEW_COMMAND) {
            val initialColumn = if (cursorColumn < left) 0 else left
            if (cursorColumn != initialColumn) { carriageReturn(); lineFeed() }
        }
        when (action) {
            TerminalPromptAction.FRESH_LINE -> Unit
            TerminalPromptAction.NEW_PROMPT, TerminalPromptAction.NEW_COMMAND, TerminalPromptAction.PROMPT -> {
                semantic.seen = true; semantic.content = TerminalSemanticContent.PROMPT; semantic.clearEol = false
                screen[cursorRow].promptMarker = when (command.promptKind) {
                    TerminalPromptKind.CONTINUATION, TerminalPromptKind.SECONDARY -> TerminalPromptMarker.CONTINUATION
                    else -> TerminalPromptMarker.PROMPT
                }
                if (action != TerminalPromptAction.PROMPT) {
                    command.redraw?.let { promptRedraw = it }
                    command.click?.let { semantic.click = it }
                }
            }
            TerminalPromptAction.INPUT, TerminalPromptAction.INPUT_UNTIL_EOL -> {
                semantic.content = TerminalSemanticContent.INPUT
                semantic.clearEol = action == TerminalPromptAction.INPUT_UNTIL_EOL
            }
            TerminalPromptAction.OUTPUT, TerminalPromptAction.END_COMMAND -> {
                semantic.content = TerminalSemanticContent.OUTPUT; semantic.clearEol = false
                if (action == TerminalPromptAction.OUTPUT && cursorColumn == 0) screen[cursorRow].promptMarker = TerminalPromptMarker.NONE
            }
        }
        damage(cursorRow)
        semanticPromptObservers.toList().forEach { it(command) }
    }

    private fun saveCursor() = SavedCursor(cursorColumn, cursorRow, fg, bg, style, origin, pendingWrap, charset, attributes, protectedCell, valid = true)
    private fun restoreCursor(value: SavedCursor) {
        // An unset saved slot restores defaults; valid only controls resize/reflow tracking.
        cursorColumn = value.x.coerceIn(0, columns - 1); cursorRow = value.y.coerceIn(0, rows - 1)
        fg = value.fg; bg = value.bg; style = value.style; origin = value.origin; pendingWrap = value.wrap; charset = value.charset
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
        val style: Int = 0, val origin: Boolean = false, val wrap: Boolean = false, val charset: TerminalCharsetState = TerminalCharsetState(),
        val attributes: CellAttributes? = null,
        val protectedCell: Boolean = false,
        val valid: Boolean = false)

    private companion object {
        const val PRIMARY_DEVICE_ATTRIBUTES = "\u001b[?1;2c"
        const val PROTECTION_ISO = 1
        const val PROTECTION_DEC = 2
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
