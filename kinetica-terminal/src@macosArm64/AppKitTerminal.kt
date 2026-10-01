@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.*
import platform.darwin.NSObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** A single custom-drawn native view; no NSTextField or NSView is allocated for terminal cells. */
public class AppKitTerminalView(
    public val session: TerminalSession,
    private val sessionId: String = "terminal",
    private val optionAsMeta: Boolean = false,
    private val rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
) : NSView(NSMakeRect(0.0, 0.0, 800.0, 480.0)), NSTextInputClientProtocol, CALayerDelegateProtocol {
    private var fontSize = 14.0
    private var fontFamily: String? = null
    private var font = terminalNativeFont(fontFamily, fontSize)
    public val currentFontSize: Double get() = fontSize
    public val currentFontFamily: String? get() = fontFamily
    private var cellWidth = 8.0
    private var cellHeight = 19.0
    private var disposed = false
    public var onViewportChanged: (() -> Unit)? = null
    internal var onScrollActivity: (() -> Unit)? = null
    public var openLink: (String) -> Boolean = { value ->
        val url = NSURL.URLWithString(value)
        url != null && url.scheme?.lowercase() in setOf("http", "https", "mailto", "file") && NSWorkspace.sharedWorkspace.openURL(url)
    }
    public val scrollOffset: Int get() = viewport.scrollOffset
    public val scrollbackLines: Int get() = session.historySize
    private var ready = false
    private var liveResizing = false
    private var pendingGridSize: Pair<Int, Int>? = null
    private var marked = ""
    private var markedSelection = NSMakeRange(0u, 0u)
    private var selecting = false
    private val mouseReporter = TerminalMouseReporter(session)
    private val verticalScroll = TerminalScrollAccumulator()
    private val horizontalScroll = TerminalScrollAccumulator()
    private var mouseTrackingArea: NSTrackingArea? = null
    private val colors = mutableMapOf<Int, NSColor>()
    private val attributes = mutableMapOf<Pair<Int, Int>, Map<Any?, *>>()
    private val viewport = TerminalViewport(session, ::invalidateRows, TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    })
    private val resizeHoldObservation = session.onRenderHold { held ->
        if (!held && ready && !disposed) applyPendingGridSize()
    }
    private var gpu: MetalTerminalDrawing? = null
    private val renderProbe = TerminalRenderProbe(renderObserver)
    private var compositionLayer: CATextLayer? = null
    public val renderingBackend: String get() = if (gpu != null) "metal" else "appkit"
    internal val submittedGpuFrames: Int get() = gpu?.submittedFrames ?: 0
    public var renderingFailure: String? = null
        private set

    init {
        setAccessibilityElement(true)
        setAccessibilityRole("AXTextArea")
        setAccessibilityLabel("Terminal")
        registerForDraggedTypes(listOf(NSPasteboardTypeFileURL))
        setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationHorizontal)
        setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationVertical)
        setContentCompressionResistancePriority(1f, NSUserInterfaceLayoutOrientationVertical)
        if (rendering != TerminalRendering.SOFTWARE) {
            try {
                val renderer = MetalTerminalDrawing(renderProbe) { if (!disposed) setNeedsDisplay(true) }
                gpu = renderer
                layerContentsPlacement = NSViewLayerContentsPlacementTopLeft
                wantsLayer = true
                layerContentsRedrawPolicy = NSViewLayerContentsRedrawDuringViewResize
                layer!!.delegate = this
                compositionLayer = CATextLayer().also { renderer.layer.addSublayer(it) }
            } catch (error: Exception) {
                renderingFailure = error.message
                releaseGpu()
                if (rendering == TerminalRendering.GPU) throw error
            }
        }
        ready = true
        viewport.setFocused(false)
        measure()
    }

    override fun isFlipped(): Boolean = true
    override fun makeBackingLayer(): CALayer = gpu?.layer ?: super.makeBackingLayer()
    override fun displayLayer(layer: CALayer) { if (!disposed && gpu != null) updateLayer() }
    override fun isOpaque(): Boolean = true
    override fun acceptsFirstResponder(): Boolean = true
    override fun becomeFirstResponder(): Boolean { viewport.setFocused(true); session.sendFocus(true); return true }
    override fun resignFirstResponder(): Boolean {
        if (!disposed) { mouseReporter.cancel(); selecting = false; viewport.setFocused(false); session.sendFocus(false) }
        return true
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        mouseTrackingArea?.let { removeTrackingArea(it) }; mouseTrackingArea = null
        if (ready && !disposed) {
            mouseTrackingArea = NSTrackingArea(NSMakeRect(0.0, 0.0, 0.0, 0.0),
                NSTrackingMouseMoved or NSTrackingActiveInKeyWindow or NSTrackingInVisibleRect, this, null)
                .also { addTrackingArea(it) }
        }
    }

    public fun configureFont(family: String?, size: Double) {
        require(family == null || family.isNotBlank() && family.length <= 256 && family.none { it.code < 32 })
        require(size.isFinite() && size in 6.0..96.0)
        if (fontSize == size && fontFamily == family) return
        fontSize = size; fontFamily = family; font = terminalNativeFont(family, size)
        attributes.clear(); measure()
    }

    override fun setFrameSize(newSize: CValue<NSSize>) {
        super.setFrameSize(newSize)
        if (ready && !disposed) measure()
    }

    override fun viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if (ready && !disposed) { viewport.markAll(); setNeedsDisplay(true) }
    }

    override fun viewWillStartLiveResize() {
        liveResizing = true
        super.viewWillStartLiveResize()
    }

    override fun viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        liveResizing = false
        if (ready && !disposed) { viewport.markAll(); setNeedsDisplay(true) }
    }

    private fun measure() {
        cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        cellHeight = ceil(font.ascender - font.descender + font.leading + 2.0)
        bounds.useContents {
            if (size.width > 0 && size.height > 0) {
                pendingGridSize = floor(size.width / cellWidth).toInt().coerceIn(1, 4096) to
                    floor(size.height / cellHeight).toInt().coerceIn(1, 4096)
                if (!session.synchronizedOutput) applyPendingGridSize()
            }
        }
        viewport.markAll()
        setNeedsDisplay(true)
    }

    private fun applyPendingGridSize() {
        val (columns, rows) = pendingGridSize ?: return
        pendingGridSize = null
        // Pixel-only changes must not end an application's synchronized-output hold.
        // For a real grid change, let the current atomic output finish before sending
        // SIGWINCH; the existing hold deadline still bounds an abandoned update.
        if (columns != session.columns || rows != session.rows) {
            if (liveResizing) viewport.resizeSession(columns, rows) else session.resize(columns, rows)
        }
    }

    private fun invalidateRows() {
        if (!ready || disposed) return
        onViewportChanged?.invoke()
        val first = viewport.firstDirtyRow
        val last = viewport.lastDirtyRow
        if (last >= first) setNeedsDisplayInRect(NSMakeRect(0.0, first * cellHeight, bounds.useContents { size.width }, (last - first + 1) * cellHeight))
    }

    override fun wantsUpdateLayer(): Boolean = gpu != null

    override fun updateLayer() {
        if (disposed) return
        gpu?.let { renderer ->
            try {
                // Metal is the view's backing layer, so there is no separate AppKit
                // bitmap to clear or swap while its geometry changes.
                val scale = window?.backingScaleFactor ?: 1.0
                bounds.useContents { renderer.resize(size.width, size.height, scale, fontSize, cellWidth, cellHeight,
                    managesLayerFrame = false, fontFamily = fontFamily) }
                updateCompositionLayer(scale)
                renderer.draw(viewport, synchronizePresentation = liveResizing)
                return
            } catch (error: Exception) {
                if (rendering == TerminalRendering.GPU) throw error
                renderingFailure = error.message
                releaseGpu()
                viewport.markAll(); setNeedsDisplay(true)
            }
        }
    }

    private fun releaseGpu() {
        val renderer = gpu
        gpu = null; compositionLayer = null
        renderer?.let {
            renderer.layer.delegate = null
            wantsLayer = false; layer = null
            renderer.dispose()
        }
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        if (disposed) return
        if (gpu != null) { updateLayer(); return }
        val measurement = renderProbe.begin(viewport, "appkit")
        color(viewport.theme.background).setFill(); NSRectFill(dirtyRect)
        val first = dirtyRect.useContents { floor(origin.y / cellHeight).toInt().coerceAtLeast(0) }
        val last = dirtyRect.useContents { floor((origin.y + size.height) / cellHeight).toInt().coerceAtMost(viewport.rows - 1) }
        val scale = window?.backingScaleFactor ?: 1.0
        viewport.paint(object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
                val left = round(x * scale) / scale
                val top = round(y * scale) / scale
                color(color).setFill()
                NSRectFill(NSMakeRect(left, top, round((x + width) * scale) / scale - left,
                    round((y + height) * scale) / scale - top))
            }
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {
                NSAttributedString.create(string = value, attributes = textAttributes(color, style)).drawAtPoint(NSMakePoint(x, y))
            }
        }, cellWidth, cellHeight, force = true, firstRow = first, lastRow = last)
        if (marked.isNotEmpty()) {
            val x = viewport.cursorColumn * cellWidth; val y = viewport.cursorRow * cellHeight
            color(viewport.theme.background).setFill()
            NSRectFill(NSMakeRect(x, y, marked.length * cellWidth, cellHeight))
            NSAttributedString.create(string = marked, attributes = textAttributes(viewport.theme.foreground, TerminalStyle.UNDERLINE)).drawAtPoint(NSMakePoint(x, y))
        }
        measurement?.submitted(0)
    }

    private fun updateCompositionLayer(scale: Double) {
        val overlay = compositionLayer ?: return
        CATransaction.begin(); CATransaction.setDisableActions(true)
        overlay.hidden = marked.isEmpty()
        overlay.contentsScale = scale
        val text = NSAttributedString.create(string = marked, attributes = textAttributes(viewport.theme.foreground, TerminalStyle.UNDERLINE))
        overlay.frame = NSMakeRect(viewport.cursorColumn * cellWidth, viewport.cursorRow * cellHeight,
            ceil(text.size().useContents { width }), cellHeight)
        overlay.backgroundColor = color(viewport.theme.background).CGColor
        overlay.string = text
        CATransaction.commit()
    }

    private fun color(rgb: Int): NSColor {
        if (colors.size > 512) colors.clear()
        return colors.getOrPut(rgb) { NSColor.colorWithSRGBRed((rgb shr 16 and 255) / 255.0, (rgb shr 8 and 255) / 255.0, (rgb and 255) / 255.0, 1.0) }
    }
    private fun textAttributes(rgb: Int, style: Int): Map<Any?, *> {
        if (attributes.size > 512) attributes.clear()
        return attributes.getOrPut(rgb to style) {
            val styled = terminalNativeFont(fontFamily, fontSize, style)
            mapOf(NSFontAttributeName to styled, NSForegroundColorAttributeName to color(rgb),
                NSUnderlineStyleAttributeName to if (style and TerminalStyle.UNDERLINE != 0) 1 else 0)
        }
    }

    override fun keyDown(event: NSEvent) {
        if (disposed) return
        val flags = event.modifierFlags
        val command = flags and NSEventModifierFlagCommand != 0uL
        val shift = flags and NSEventModifierFlagShift != 0uL
        val control = flags and NSEventModifierFlagControl != 0uL
        val raw = event.charactersIgnoringModifiers.orEmpty()
        val chars = if (command || control) terminalShortcutKey(raw, event.keyCode.toInt()) else raw
        if (command) {
            when (chars.lowercase()) {
                "c" -> copySelection(); "v" -> pasteClipboard(); "a" -> viewport.selectAll()
                "k" -> if (shift) clearHistory(null) else clearScreen(null)
                else -> super.keyDown(event)
            }
            return
        }
        val keypad = nativeKeypad(event.keyCode.toInt())
        val key = if (keypad == TerminalKeypadKey.ENTER) "Enter" else nativeKey(chars)
        if (!hasMarkedText() && shift && key in listOf("PageUp", "PageDown")) {
            viewport.scroll((session.rows - 1) * if (key == "PageUp") 1 else -1)
            onScrollActivity?.invoke(); return
        }
        if (!hasMarkedText() && session.sendKey(TerminalKey(key, control,
                optionAsMeta && flags and NSEventModifierFlagOption != 0uL, shift, keypad = keypad))) {
            viewport.followOutput(); viewport.clearSelection(); return
        }
        interpretKeyEvents(listOf(event))
    }

    override fun insertText(string: Any, replacementRange: CValue<NSRange>) {
        val value = if (string is NSAttributedString) string.string else string.toString()
        marked = ""; viewport.followOutput(); viewport.clearSelection(); session.sendInput(value); viewport.markAll()
    }
    override fun setMarkedText(string: Any, selectedRange: CValue<NSRange>, replacementRange: CValue<NSRange>) {
        marked = if (string is NSAttributedString) string.string else string.toString()
        markedSelection = selectedRange; viewport.markAll()
    }
    override fun unmarkText() { marked = ""; viewport.markAll() }
    override fun hasMarkedText(): Boolean = marked.isNotEmpty()
    override fun markedRange(): CValue<NSRange> = if (marked.isEmpty()) NSMakeRange(NSNotFound.toULong(), 0u) else NSMakeRange(0u, marked.length.toULong())
    override fun selectedRange(): CValue<NSRange> = markedSelection
    override fun validAttributesForMarkedText(): List<*> = listOf(NSUnderlineStyleAttributeName)
    override fun attributedSubstringForProposedRange(range: CValue<NSRange>, actualRange: CPointer<NSRange>?): NSAttributedString? = null
    override fun characterIndexForPoint(point: CValue<NSPoint>): ULong = 0u
    override fun firstRectForCharacterRange(range: CValue<NSRange>, actualRange: CPointer<NSRange>?): CValue<NSRect> {
        val rect = convertRect(NSMakeRect(viewport.cursorColumn * cellWidth, viewport.cursorRow * cellHeight, cellWidth, cellHeight), toView = null)
        return window?.convertRectToScreen(rect) ?: rect
    }
    override fun doCommandBySelector(selector: CPointer<out CPointed>?) {
        val key = when (NSStringFromSelector(selector)) {
            "insertNewline:" -> "Enter"; "insertTab:" -> "Tab"; "deleteBackward:" -> "Backspace"; "cancelOperation:" -> "Escape"
            else -> return
        }
        viewport.followOutput(); viewport.clearSelection(); session.sendKey(TerminalKey(key))
    }

    override fun mouseDown(event: NSEvent) {
        if (disposed) return
        window?.makeFirstResponder(this)
        val (x, y) = cell(event)
        if (event.modifierFlags and NSEventModifierFlagCommand != 0uL) {
            viewport.hyperlinkAt(x, y)?.let { link -> if (openLink(link)) { selecting = false; return } }
        }
        selecting = !mouse(event, x, y, 0)
        if (selecting) viewport.beginSelection(x, y, when (event.clickCount.toInt()) {
            0, 1 -> TerminalSelectionMode.CHARACTER
            2 -> TerminalSelectionMode.WORD
            else -> TerminalSelectionMode.LINE
        }) else viewport.clearSelection()
    }
    override fun mouseDragged(event: NSEvent) {
        if (disposed) return
        val (x, y) = cell(event)
        if (selecting) viewport.extendSelection(x, y) else mouse(event, x, y, 0, motion = true)
    }
    override fun mouseUp(event: NSEvent) {
        if (disposed) return
        val (x, y) = cell(event)
        if (selecting) viewport.endSelection(x, y) else mouse(event, x, y, 0, release = true)
        selecting = false
    }
    override fun rightMouseDown(event: NSEvent) {
        val (x, y) = cell(event)
        if (!mouse(event, x, y, 2)) super.rightMouseDown(event)
    }
    override fun rightMouseUp(event: NSEvent) { val (x, y) = cell(event); mouse(event, x, y, 2, release = true) }
    override fun rightMouseDragged(event: NSEvent) { val (x, y) = cell(event); mouse(event, x, y, 2, motion = true) }
    override fun otherMouseDown(event: NSEvent) { nativeMouseButton(event)?.let { val (x, y) = cell(event); mouse(event, x, y, it) } }
    override fun otherMouseUp(event: NSEvent) { nativeMouseButton(event)?.let { val (x, y) = cell(event); mouse(event, x, y, it, release = true) } }
    override fun otherMouseDragged(event: NSEvent) { nativeMouseButton(event)?.let { val (x, y) = cell(event); mouse(event, x, y, it, motion = true) } }
    override fun mouseMoved(event: NSEvent) { val (x, y) = cell(event); if (!selecting) mouse(event, x, y, 3, motion = true) }
    override fun scrollWheel(event: NSEvent) {
        if (disposed) return
        val (x, y) = cell(event)
        if (event.phase == NSEventPhaseBegan) { verticalScroll.reset(); horizontalScroll.reset() }
        val reporting = session.mouseTracking != 0 && viewport.scrollOffset == 0 && event.modifierFlags and NSEventModifierFlagShift == 0uL
        if (!reporting && event.scrollingDeltaY != 0.0) onScrollActivity?.invoke()
        val tick = if (reporting) 1.0 else 3.0
        val dy = verticalScroll.consume(event.scrollingDeltaY, event.hasPreciseScrollingDeltas, cellHeight, tick)
        val dx = horizontalScroll.consume(event.scrollingDeltaX, event.hasPreciseScrollingDeltas, cellWidth, tick)
        if (dy != 0) {
            val button = if (dy > 0) 64 else 65
            if (mouse(event, x, y, button)) repeat((kotlin.math.abs(dy) - 1).coerceAtMost(63)) { mouse(event, x, y, button) }
            else viewport.scroll(dy)
        }
        if (dx != 0) repeat(kotlin.math.abs(dx).coerceAtMost(64)) { mouse(event, x, y, if (dx > 0) 66 else 67) }
    }
    private fun cell(event: NSEvent): Pair<Int, Int> = convertPoint(event.locationInWindow, fromView = null).useContents {
        floor(x / cellWidth).toInt() to floor(y / cellHeight).toInt()
    }
    private fun mouse(event: NSEvent, x: Int, y: Int, button: Int, release: Boolean = false, motion: Boolean = false): Boolean =
        !disposed && viewport.scrollOffset == 0 && mouseReporter.send(x, y, button, release, motion,
            event.modifierFlags and NSEventModifierFlagShift != 0uL, event.modifierFlags and NSEventModifierFlagOption != 0uL,
            event.modifierFlags and NSEventModifierFlagControl != 0uL)
    private fun copySelection() {
        val text = viewport.selectedText()
        if (text.isNotEmpty()) { NSPasteboard.generalPasteboard.clearContents(); NSPasteboard.generalPasteboard.setString(text, NSPasteboardTypeString) }
    }
    private fun pasteClipboard() { NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString)?.let { viewport.followOutput(); viewport.clearSelection(); session.paste(it) } }
    public fun scrollTo(offset: Int) {
        val previous = viewport.scrollOffset
        viewport.scrollTo(offset)
        if (viewport.scrollOffset != previous) onScrollActivity?.invoke()
    }
    public fun reveal(match: TerminalSearchMatch): Boolean = viewport.selectMatch(match)
    public fun clearSelection() { viewport.clearSelection() }
    @ObjCAction public fun copy(sender: NSObject?) { copySelection() }
    @ObjCAction public fun paste(sender: NSObject?) { pasteClipboard() }
    @ObjCAction public fun selectAll(sender: NSObject?) { viewport.selectAll() }
    @ObjCAction public fun clearScreen(sender: NSObject?) { viewport.clearSelection(); session.clearScreen(); viewport.followOutput() }
    @ObjCAction public fun clearHistory(sender: NSObject?) { viewport.clearSelection(); session.clearHistory(); viewport.followOutput() }

    override fun menuForEvent(event: NSEvent): NSMenu = NSMenu().apply {
        fun item(title: String, action: String, enabled: Boolean = true) {
            addItem(NSMenuItem(title, NSSelectorFromString(action), "").also {
                it.target = this@AppKitTerminalView; it.enabled = enabled
            })
        }
        autoenablesItems = false
        item("Copy", "copy:", viewport.selectedText().isNotEmpty())
        item("Paste", "paste:", NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString) != null)
        item("Select All", "selectAll:")
        addItem(NSMenuItem.separatorItem())
        item("Clear Screen", "clearScreen:")
        item("Clear Scrollback", "clearHistory:", session.historySize > 0)
    }

    public fun pasteFilePaths(paths: List<String>) {
        if (paths.isEmpty() || disposed) return
        require(paths.all { it.startsWith('/') && '\u0000' !in it })
        viewport.followOutput(); viewport.clearSelection()
        session.paste(paths.joinToString(" ", postfix = " ", transform = ::terminalShellQuote))
    }

    private fun draggedPaths(sender: NSDraggingInfoProtocol): List<String> = terminalDroppedFilePaths(sender.draggingPasteboard)

    override fun draggingEntered(sender: NSDraggingInfoProtocol): NSDragOperation =
        if (draggedPaths(sender).isNotEmpty()) NSDragOperationCopy else NSDragOperationNone
    override fun prepareForDragOperation(sender: NSDraggingInfoProtocol): Boolean = draggedPaths(sender).isNotEmpty()
    override fun performDragOperation(sender: NSDraggingInfoProtocol): Boolean {
        val paths = draggedPaths(sender)
        if (paths.isEmpty()) return false
        pasteFilePaths(paths); window?.makeFirstResponder(this); return true
    }
    override fun accessibilityValue(): Any? = viewport.visibleText()
    override fun accessibilitySelectedText(): String? = viewport.selectedText()
    public fun dispose() { if (!disposed) {
        disposed = true; selecting = false; mouseReporter.reset()
        mouseTrackingArea?.let { removeTrackingArea(it) }; mouseTrackingArea = null
        resizeHoldObservation.dispose(); pendingGridSize = null
        onViewportChanged = null; onScrollActivity = null
        viewport.dispose(); releaseGpu(); inputContext()?.discardMarkedText()
    } }
}

private fun nativeMouseButton(event: NSEvent): Int? = when (event.buttonNumber.toInt()) {
    2 -> 1; 3 -> 128; 4 -> 129; else -> null
}

// NSEvent's numeric-pad modifier also marks navigation keys. Hardware virtual key codes
// distinguish the actual keypad and preserve its decimal identity on non-US layouts.
private fun nativeKeypad(code: Int): TerminalKeypadKey? = when (code) {
    82 -> TerminalKeypadKey.ZERO; 83 -> TerminalKeypadKey.ONE; 84 -> TerminalKeypadKey.TWO
    85 -> TerminalKeypadKey.THREE; 86 -> TerminalKeypadKey.FOUR; 87 -> TerminalKeypadKey.FIVE
    88 -> TerminalKeypadKey.SIX; 89 -> TerminalKeypadKey.SEVEN; 91 -> TerminalKeypadKey.EIGHT
    92 -> TerminalKeypadKey.NINE; 65 -> TerminalKeypadKey.DECIMAL; 75 -> TerminalKeypadKey.DIVIDE
    67 -> TerminalKeypadKey.MULTIPLY; 78 -> TerminalKeypadKey.SUBTRACT; 69 -> TerminalKeypadKey.ADD
    76 -> TerminalKeypadKey.ENTER; 81 -> TerminalKeypadKey.EQUAL; 95 -> TerminalKeypadKey.SEPARATOR
    else -> null
}

internal fun terminalDroppedFilePaths(pasteboard: NSPasteboard): List<String> =
    pasteboard.readObjectsForClasses(listOf(NSURL.`class`()),
        mapOf(NSPasteboardURLReadingFileURLsOnlyKey to true)).orEmpty()
        .filterIsInstance<NSURL>().mapNotNull { if (it.fileURL) it.path else null }

private fun nativeKey(chars: String): String = when (chars.firstOrNull()?.code) {
    13, 10 -> "Enter"; 9 -> "Tab"; 25 -> "Tab"; 27 -> "Escape"; 127, 8 -> "Backspace"
    0xf700 -> "ArrowUp"; 0xf701 -> "ArrowDown"; 0xf702 -> "ArrowLeft"; 0xf703 -> "ArrowRight"
    in 0xf704..0xf70f -> "F${chars[0].code - 0xf704 + 1}"
    0xf727 -> "Insert"; 0xf728 -> "Delete"; 0xf729 -> "Home"; 0xf72b -> "End"; 0xf72c -> "PageUp"; 0xf72d -> "PageDown"
    else -> chars
}
