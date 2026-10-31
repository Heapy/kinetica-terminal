package io.heapy.kinetica.terminal

import org.w3c.dom.Element
import kotlin.math.floor
import kotlin.js.Promise

/** A framework-independent terminal surface. Append [view] to a sized DOM container.
 * Dispose the surface before removing its element; the application owns the session/transport. */
public class BrowserTerminalView(
    public val session: TerminalSession,
    private val sessionId: String = "terminal",
    private val rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
    clipboardPolicy: TerminalClipboardPolicy = TerminalClipboardPolicy.DENY,
    clipboard: TerminalClipboard? = null,
    notificationPolicy: (TerminalNotification) -> Boolean = { false },
    notifications: TerminalNotificationDelivery? = null,
    graphicsStorageBytes: Int = 64 * 1024 * 1024,
    mimeClipboardPolicy: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy.DENY,
    mimeClipboard: TerminalMimeClipboard? = null,
    windowResizePolicy: (TerminalWindowResizeRequest) -> Boolean = { false },
    resizeContainer: (TerminalWindowSize) -> Unit = {},
) {
    private val defaultOpenLink: (String) -> Boolean = { openBrowserTerminalLink(it) { !disposed } }
    /** Override only with a host policy suitable for untrusted terminal-output targets.
     * The default opens absolute web/mail URLs and confirms external application schemes. */
    public var openLink: (String) -> Boolean = defaultOpenLink
    public var linkPreviewMode: TerminalLinkPreviewMode = TerminalLinkPreviewMode.ALL
        set(value) { field = value; refreshLinkHover() }
    public val scrollOffset: Int get() = viewport.scrollOffset
    public fun jumpToPrompt(direction: Int): Boolean = viewport.jumpToPrompt(direction)
    public fun scrollToSelection(): Boolean = viewport.scrollToSelection()
    private val document: dynamic = js("globalThis.document")
    private val window: dynamic = js("globalThis.window")
    public val view: Element = document.createElement("div").unsafeCast<Element>()
    private val root: dynamic = view.asDynamic()
    private val renderProbe = TerminalRenderProbe(renderObserver)
    private var drawing = createBrowserTerminalDrawing(rendering, ::schedule, renderProbe)
    private val canvas: dynamic get() = drawing.canvas
    private val input: dynamic = document.createElement("textarea")
    private val accessible: dynamic = document.createElement("pre")
    private val accessibleText: dynamic = document.createTextNode("")
    private val composition: dynamic = document.createElement("div")
    private val linkPreview: dynamic = document.createElement("div")
    private val progressElement: dynamic = document.createElement("progress")
    private val measureContext: dynamic = document.createElement("canvas").getContext("2d")
    private var fontSize = 14.0
    private var fontFamily: String? = null
    private var shapingFonts: BrowserTerminalFontSet? = null
    private var fontLoadGeneration = 0
    /** Current grid dimensions in CSS pixels, after host/font measurement. */
    public var cellWidth: Double = 8.0
        private set
    public var cellHeight: Double = 18.0
        private set
    private var pixelRatio = 0.0
    private var measuredPixelWidth = 0; private var measuredPixelHeight = 0
    private var frame: Int? = null
    private var accessibilityTimer: Int? = null
    private var disposed = false
    public var pastePolicy: TerminalPastePolicy = TerminalPastePolicy()
    public var copyTrimTrailingSpaces: Boolean = true
    public var copyFormat: TerminalCopyFormat = TerminalCopyFormat.MIXED
    public var selectionClearOnCopy: Boolean = false
    public var selectionClearOnTyping: Boolean = true
    /** Zero uses Ghostty's non-native fallback of 500 ms. */
    public var clickRepeatIntervalMillis: Long = 0
        set(value) { require(value in 0..0xffff_ffffL); field = value }
    public var rightClickAction: TerminalRightClickAction = TerminalRightClickAction.CONTEXT_MENU
    public var middleClickAction: TerminalMiddleClickAction = TerminalMiddleClickAction.PRIMARY_PASTE
    /** Enable only when the custom MIME backend serves the distinct SELECTION clipboard. */
    public var selectionClipboardSupported: Boolean = false
    public var copyOnSelect: TerminalCopyOnSelect = if (window.navigator.platform.startsWith("Linux"))
        TerminalCopyOnSelect.PRIMARY else TerminalCopyOnSelect.NONE
    public var copyCodepointMap: List<TerminalClipboardCodepointMap> = emptyList()
        set(value) { field = value.toList() }
    public var pasteConfirmation: TerminalPasteConfirmation = TerminalPasteConfirmation { text, complete ->
        presentBrowserPaste(root, text, complete)
    }
    private val clipboardPaste = TerminalClipboardPaste(session, { !disposed && root.isConnected == true },
        { text, complete -> pasteConfirmation.confirm(text, complete) },
        { viewport.followOutput(); viewport.clearSelection() })
    private var notifications = notifications
    private fun notificationDelivery(): TerminalNotificationDelivery = notifications ?: BrowserTerminalNotifications().also { notifications = it }
    public fun requestNotificationPermission(complete: (Boolean) -> Unit) {
        if (disposed) complete(false) else notificationDelivery().requestPermission(complete)
    }
    private val notificationObservation = session.onNotification { if (!disposed && notificationPolicy(it)) notificationDelivery().show(it) }
    private val pressedKeys = mutableSetOf<String>()
    private val keyFocus = TerminalKeyFocus()
    private val layoutKeys = mutableMapOf<String, Int>()
    private var composing = false
    private var suppressComposition: String? = null
    private var selecting = false
    private var continuingSelection = false
    private var mouseButtons = 0
    private val localMouseButtons = mutableSetOf<Int>()
    private var activePointer: Int? = null
    private var focused = false
    private var hoverClientPoint: Pair<Double, Double>? = null
    private var hoverModifiers = TerminalLinkModifiers()
    private var refreshingLinkHover = false
    private val mouseReporter = TerminalMouseReporter(session)
    private val listeners = mutableListOf<() -> Unit>()
    private val viewport = TerminalViewport(session, ::schedule, TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    })
    private val resizeObserver: dynamic = newResizeObserver { resize() }
    private val sizeRefreshObservation = session.onSizeRefreshRequested { resize(force = true) }
    private val dragAndDrop = BrowserTerminalDnd(session, root, { event, operations ->
        if (disposed || root.isConnected != true || cellWidth <= 0 || cellHeight <= 0) null else {
            val rect = canvas.getBoundingClientRect()
            val x = (event.clientX as Number).toDouble() - (rect.left as Number).toDouble()
            val y = (event.clientY as Number).toDouble() - (rect.top as Number).toDouble()
            val scale = (window.devicePixelRatio as Number).toDouble()
            TerminalDndPosition(floor(x / cellWidth).toInt().coerceIn(0, session.columns - 1).toLong(),
                floor(y / cellHeight).toInt().coerceIn(0, session.rows - 1).toLong(),
                floor(x * scale).toInt(), floor(y * scale).toInt(), operations)
        }
    }) { viewport.followOutput(); viewport.clearSelection(); input.focus(js("({preventScroll:true})")) }
    private val windowResizeBinding = TerminalWindowResizeBinding(session, TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    }, { if (!disposed && root.isConnected == true) view.parentElement else null }, windowResizePolicy, { request ->
        resizeContainer(request.windowSize(session.columns, session.rows, cellWidth, cellHeight))
    })
    private var measuredColumns = 0
    private var measuredRows = 0
    private var intersecting = false
    private var graphicsVisible = false
    private val visibilityBinding = session.attachVisibility(false)
    private val intersectionObserver: dynamic = newTerminalIntersectionObserver { value ->
        intersecting = value; updateVisibility()
    }
    private val visibilityObserver: dynamic = newTerminalVisibilityObserver(root, {
        windowResizeBinding.cancelPending(); dragAndDrop.cancel(); clipboardPaste.cancel()
    }) { updateVisibility() }
    private val pointerObservation = session.onPointerShape { refreshLinkHover() }
    private val linkPolicyObservation = session.onLinkPolicyChanged { refreshLinkHover() }
    private val progressPresentation = TerminalProgressPresentation(session, TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    }, ::updateProgress)
    private val clipboardBinding = session.attachClipboard(clipboard ?: BrowserTerminalClipboard(), clipboardPolicy)
    private val ownedMimeClipboard = if (mimeClipboard == null) BrowserTerminalMimeClipboard() else null
    private val copyClipboard = mimeClipboard ?: checkNotNull(ownedMimeClipboard)
    private val mimeClipboardBinding = session.attachMimeClipboard(copyClipboard, mimeClipboardPolicy)

    init {
        drawing.setVisible(false)
        viewport.setVisible(false)
        session.setGraphicsStorageLimit(graphicsStorageBytes)
        root.style.cssText = "position:relative;width:100%;height:100%;min-width:80px;min-height:40px;overflow:hidden;background:#1e1e1e;flex:1;user-select:none;touch-action:none;"
        root.style.cursor = session.pointerShape.cssName
        root.setAttribute("data-terminal", sessionId)
        root.setAttribute("tabindex", "0")
        root.setAttribute("aria-label", "Terminal")
        configureCanvas()
        viewport.setFocused(false)
        input.style.cssText = "position:absolute;opacity:0;width:1px;height:1em;padding:0;border:0;resize:none;overflow:hidden;"
        input.setAttribute("aria-label", "Terminal input")
        input.setAttribute("aria-keyshortcuts", if (window.navigator.platform.startsWith("Mac"))
            "Meta+a Meta+c Meta+v Meta+Home Meta+End Meta+PageUp Meta+PageDown Meta+ArrowUp Meta+ArrowDown Meta+Shift+ArrowUp Meta+Shift+ArrowDown Meta+j"
        else "Control+Shift+a Control+Shift+c Control+Shift+v Shift+Home Shift+End Shift+PageUp Shift+PageDown Control+Shift+ArrowUp Control+Shift+ArrowDown")
        input.setAttribute("autocomplete", "off"); input.setAttribute("autocapitalize", "off")
        input.setAttribute("spellcheck", "false")
        accessible.style.cssText = "position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:pre;"
        accessible.setAttribute("role", "log"); accessible.setAttribute("aria-label", "Terminal output")
        accessible.setAttribute("aria-live", "off")
        accessible.appendChild(accessibleText)
        composition.style.cssText = "position:absolute;display:none;background:#1e1e1e;color:#d4d4d4;text-decoration:underline;white-space:pre;pointer-events:none;"
        composition.setAttribute("aria-hidden", "true")
        root.appendChild(canvas); root.appendChild(input); root.appendChild(accessible); root.appendChild(composition)
        linkPreview.style.cssText = "position:absolute;bottom:0;left:0;max-width:95%;box-sizing:border-box;padding:2px 6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;direction:ltr;unicode-bidi:plaintext;pointer-events:none;font:12px monospace;z-index:3;"
        linkPreview.setAttribute("data-terminal-link-preview", "")
        linkPreview.setAttribute("aria-label", "Link destination")
        linkPreview.hidden = true
        root.appendChild(linkPreview)
        for (type in listOf("keydown", "keyup")) listen(window, type, capture = true) { event ->
            hoverModifiers = linkEventModifiers(event); refreshLinkHover()
        }
        listen(root, "pointerenter") { event -> updateLinkPointer(event) }
        listen(root, "pointerleave") { clearLinkPointer() }
        progressElement.max = 100
        progressElement.style.cssText = "position:absolute;top:0;left:0;width:100%;height:4px;margin:0;border:0;pointer-events:none;z-index:2;"
        progressElement.hidden = true
        progressElement.setAttribute("data-terminal-progress", "")
        root.appendChild(progressElement)
        // Chromium can provide the actual layout before the first shifted key is pressed.
        // Other browsers use observed unshifted keys and the fallback in key().
        val browserKeyboard = window.navigator.keyboard
        if (browserKeyboard != null && browserKeyboard.getLayoutMap != null) {
            browserKeyboard.getLayoutMap().then { layout: dynamic ->
                if (!disposed) layout.forEach { value: String, code: String ->
                    value.lowercase().terminalCodepoints().singleOrNull()?.let { layoutKeys[code] = it }
                }
            }.catch { _: dynamic -> Unit }
        }
        listen(input, "keydown") { event ->
            viewport.selectionModifiersChanged(rectangleSelection(event))
            event.stopPropagation()
            if (event.isComposing == true || composing || event.keyCode == 229) return@listen
            val key = key(event)
            val clipboardShortcut = !key.alt && if (window.navigator.platform.startsWith("Mac"))
                key.meta && !key.control && !key.shift else key.control && key.shift && !key.meta
            if (clipboardShortcut && key.key.lowercase() == "v") return@listen
            val copyRequested = clipboardShortcut && key.key.lowercase() == "c" && viewport.hasSelection
            if (clipboardShortcut && key.key.lowercase() == "a") {
                if (viewport.trySelectAll()) copySelectionAutomatically()
                event.preventDefault()
            } else if (copyRequested) {
                copySelection(); event.preventDefault()
            } else if (navigateViewport(key)) {
                event.preventDefault()
            } else if (sendTypedKey(key)) {
                pressedKeys.add((event.code as? String) ?: key.key)
                event.preventDefault()
            } else {
                pressedKeys.add((event.code as? String) ?: key.key)
            }
        }
        listen(input, "keyup") { event ->
            viewport.selectionModifiersChanged(rectangleSelection(event))
            event.stopPropagation()
            val identity = (event.code as? String) ?: event.key as String
            if (pressedKeys.remove(identity) && !composing && event.isComposing != true) {
                sendTypedKey(key(event).copy(action = TerminalKeyAction.RELEASE))
            }
        }
        listen(input, "input") { event ->
            event.stopPropagation()
            if (!composing && event.isComposing != true) {
                val value = input.value as String
                input.value = ""
                val duplicateComposition = value == suppressComposition &&
                    (event.inputType == "insertCompositionText" || event.inputType == "insertFromComposition")
                if (!duplicateComposition) sendTypedText(value)
                suppressComposition = null
            }
        }
        listen(input, "compositionstart") { composing = true; pressedKeys.clear(); clearSelectionForPreedit() }
        listen(input, "compositionupdate") { event ->
            if (composing || !(event.data as? String).isNullOrEmpty()) clearSelectionForPreedit()
            composition.textContent = (event.data as? String).orEmpty()
            composition.style.display = "block"; composition.style.font = font()
            composition.style.left = input.style.left; composition.style.top = input.style.top
        }
        listen(input, "compositionend") { event ->
            if (composing) clearSelectionForPreedit()
            composing = false
            composition.style.display = "none"; composition.textContent = ""
            val text = (event.data as? String).orEmpty()
            sendTypedText(text)
            input.value = ""; suppressComposition = text
        }
        listen(input, "paste") { event ->
            event.preventDefault(); event.stopPropagation()
            clipboardPaste.cancel()
            if (event.isTrusted == true && session.pasteClipboard()) {
                viewport.followOutput(); viewport.clearSelection()
            } else {
                val text = event.clipboardData?.getData("text/plain") as? String
                if (text != null) clipboardPaste.paste(text, pastePolicy)
            }
        }
        listen(root, "copy") { event ->
            if (viewport.hasSelection) {
                viewport.copyData(copyFormat, copyTrimTrailingSpaces, copyCodepointMap)?.let { data ->
                    event.clipboardData?.clearData()
                    data.text?.let { event.clipboardData?.setData("text/plain", it) }
                    data.html?.let { event.clipboardData?.setData("text/html", it) }
                    if (selectionClearOnCopy) viewport.clearSelection()
                }
                event.preventDefault(); event.stopPropagation()
            }
        }
        listen(input, "focus") { restoreWindowFocus() }
        listen(input, "blur") { event ->
            if (root.contains(event.relatedTarget) != true) { cancelMouse(); focusChanged(false) }
        }
        // AppKit-like focus ownership for the whole widget, including its focusable host root.
        listen(root, "focus") { event -> if (event.target === root) input.focus(js("({preventScroll:true})")) }
        listen(window, "blur") { cancelMouse(); focusChanged(false) }
        // Returning to a window often preserves activeElement and emits no input focus event.
        listen(window, "focus") { restoreWindowFocus() }
        listen(root, "pointerdown") { event ->
            val id = (event.pointerId as Number).toInt()
            if (activePointer != null && activePointer != id) return@listen
            activePointer = id
            input.focus(js("({preventScroll:true})")); event.stopPropagation()
            // Cancel the following compatibility mousedown; actual presses own multi-click state.
            if (event.pointerType != "mouse") event.preventDefault()
            updateMouseButtons(event)
            root.setPointerCapture(event.pointerId)
            updateLinkPointer(event)
        }
        // Cancel compatibility mouse events that would start native selection/autoscroll.
        listen(root, "mousedown") { event ->
            event.preventDefault(); event.stopPropagation()
        }
        listen(root, "pointermove") { event ->
            val id = (event.pointerId as Number).toInt()
            if (activePointer != null && activePointer != id || activePointer == null && event.buttons != 0) return@listen
            val previous = mouseButtons
            updateMouseButtons(event)
            if (selecting) viewport.extendPointerSelection(selectionPointer(event), rectangleSelection(event))
            else if (previous == mouseButtons) mouse(event, pressedMouseButton(), motion = true)
            updateLinkPointer(event)
        }
        listen(root, "pointerup") { event ->
            if (activePointer != (event.pointerId as Number).toInt()) return@listen
            updateMouseButtons(event)
            activePointer = null
            if (root.hasPointerCapture(event.pointerId) == true) root.releasePointerCapture(event.pointerId)
            updateLinkPointer(event)
        }
        listen(root, "pointercancel") { event -> if (activePointer == (event.pointerId as Number).toInt()) cancelMouse() }
        listen(root, "lostpointercapture") { event ->
            if (activePointer == (event.pointerId as Number).toInt() && root.hasPointerCapture(event.pointerId) != true) cancelMouse()
        }
        listen(root, "contextmenu") { event ->
            if (event.button == 2 && rightClickAction != TerminalRightClickAction.CONTEXT_MENU ||
                viewport.scrollOffset == 0 && session.mouseTracking != 0 &&
                (event.shiftKey != true || session.mouseShiftCapture)) event.preventDefault()
            else if (event.button == 2) {
                val pointer = selectionPointer(event)
                if (viewport.selectForContextMenu(pointer.column, pointer.row, linkModifiers(event))) copySelectionAutomatically()
            }
        }
        listen(root, "wheel", passive = false) { event ->
            val dy = (event.deltaY as Number).toDouble()
            val dx = (event.deltaX as Number).toDouble()
            if (dy != 0.0 || dx != 0.0) {
                event.preventDefault(); event.stopPropagation()
                if (dy != 0.0 && !mouse(event, if (dy < 0) 64 else 65)) {
                    val lines = if (dy < 0) 3 else -3
                    if (!session.sendAlternateScroll(lines, shift = event.shiftKey == true)) viewport.scroll(lines)
                }
                if (dx != 0.0) mouse(event, if (dx < 0) 66 else 67)
            }
        }
        // Prevent surrounding Kinetica event handlers from treating terminal interaction as a
        // control submission/click. Raw listeners above own this entire subtree.
        for (type in listOf("click", "dblclick", "change", "keyup")) listen(root, type) { it.stopPropagation() }
        listen(window, "resize") { resize() }
        listen(document, "visibilitychange") {
            updateVisibility()
            if (document.visibilityState == "hidden") { cancelMouse(); focusChanged(false) }
            else restoreWindowFocus()
        }
        listen(document, "transitionend") { updateVisibility() }
        resizeObserver.observe(root)
        intersectionObserver.observe(root)
        visibilityObserver.observe(document.documentElement, js("({subtree:true,childList:true,attributes:true,attributeFilter:['style','class','hidden']})"))
        schedule()
    }

    public fun configureFont(family: String?, size: Double) {
        require(size.isFinite() && size in 6.0..96.0)
        require(family == null || family.isNotBlank() && family.length <= 256 && family.none { it.code < 32 })
        if (fontSize != size || fontFamily != family) { fontSize = size; fontFamily = family; pixelRatio = 0.0; resize() }
    }

    /** Load a self-hosted terminal-harfbuzz.mjs and SFNT faces for WebGL shaping.
     * A failed load leaves the previous set active. A newer load, clear or dispose
     * supersedes this request; its promise rejects and its resources are released.
     * The view owns the loaded resources. CSS-only system fonts retain legacy rendering. */
    public fun configureShapingFonts(moduleUrl: String, fonts: List<BrowserTerminalFont>): Promise<Unit> {
        check(!disposed)
        val generation = ++fontLoadGeneration
        return BrowserTerminalFontSet.load(moduleUrl, fonts) { !disposed && generation == fontLoadGeneration }.then { loaded ->
            if (disposed || generation != fontLoadGeneration) {
                loaded.dispose()
                error("Terminal font load was superseded")
            }
            val previous = shapingFonts
            drawing.configureFonts(loaded)
            shapingFonts = loaded
            previous?.dispose()
            pixelRatio = 0.0; viewport.markAll(); resize(); schedule()
        }
    }

    /** Cancel pending loads and return to CSS/system-font rendering. */
    public fun clearShapingFonts() {
        fontLoadGeneration++
        drawing.configureFonts(null)
        shapingFonts?.dispose(); shapingFonts = null
        if (!disposed) { pixelRatio = 0.0; viewport.markAll(); resize(); schedule() }
    }

    private fun font(style: Int = 0): String = shapingFonts?.cssFont(fontSize, style, fontFamily)
        ?: browserFont(fontSize, style, fontFamily)

    private fun configureCanvas() {
        canvas.style.cssText = "position:absolute;inset:0;"
        canvas.setAttribute("aria-hidden", "true")
        root.setAttribute("data-terminal-renderer", drawing.name)
    }

    private fun resize(force: Boolean = false) {
        if (disposed) return
        updateVisibility()
        val width = (root.clientWidth as Number).toDouble()
        val height = (root.clientHeight as Number).toDouble()
        if (width <= 0 || height <= 0) return
        measureContext.font = font()
        cellWidth = (measureContext.measureText("M").width as Number).toDouble()
        cellHeight = kotlin.math.ceil(fontSize * 1.3)
        val ratio = (window.devicePixelRatio as Number).toDouble()
        val w = (width * ratio).toInt(); val h = (height * ratio).toInt()
        // Renderer backing extents may be capped by the device; compare host geometry
        // with its previous measurement instead of invalidating every capped frame.
        if (measuredPixelWidth != w || measuredPixelHeight != h || pixelRatio != ratio) {
            measuredPixelWidth = w; measuredPixelHeight = h
            pixelRatio = ratio
            viewport.markAll()
        }
        drawing.resize(width, height, ratio, fontSize, cellWidth, cellHeight, fontFamily)
        val columns = floor(width / cellWidth).toInt().coerceIn(1, 4096)
        val rows = floor(height / cellHeight).toInt().coerceIn(1, 4096)
        val cellSize = TerminalCellSize((cellWidth * ratio).toInt().coerceAtLeast(1), (cellHeight * ratio).toInt().coerceAtLeast(1))
        // Painting also measures the canvas. Only an actual host geometry change may
        // replace a protocol-selected DECCOLM grid; mode 40 explicitly forces a refresh.
        if (force || columns != measuredColumns || rows != measuredRows || cellSize != session.cellSize) {
            measuredColumns = columns; measuredRows = rows
            session.resize(columns, rows, cellSize)
        }
    }

    private fun updateVisibility() {
        if (disposed) return
        val cssVisibility = window.getComputedStyle(root).visibility as String
        val value = intersecting && root.isConnected == true && document.visibilityState != "hidden" &&
            cssVisibility != "hidden" && cssVisibility != "collapse" &&
            (root.clientWidth as Number).toInt() > 0 && (root.clientHeight as Number).toInt() > 0
        if (graphicsVisible == value) return
        graphicsVisible = value
        drawing.setVisible(value)
        updateDrawingDiagnostics()
        visibilityBinding.setVisible(value)
        if (!value) { clearLinkPointer(); frame?.let { window.cancelAnimationFrame(it) }; frame = null }
        viewport.setVisible(value)
    }

    private fun schedule() {
        if (disposed || frame != null) return
        updateVisibility()
        if (!graphicsVisible || frame != null) return
        frame = window.requestAnimationFrame { _: Double ->
            refreshLinkHover()
            frame = null
            updateVisibility()
            if (!disposed && graphicsVisible) {
                resize()
                root.style.background = terminalCssColor(viewport.theme.background)
                composition.style.background = terminalCssColor(viewport.theme.background)
                composition.style.color = terminalCssColor(viewport.theme.foreground)
                try { drawing.draw(viewport) } catch (error: Exception) {
                    if (rendering != TerminalRendering.AUTO || drawing.name == "canvas2d") throw error
                    root.setAttribute("data-terminal-rendering-error", error.message.orEmpty())
                    val previous = canvas
                    drawing.dispose(); drawing = CanvasTerminalDrawing(renderProbe)
                    drawing.configureFonts(shapingFonts); configureCanvas()
                    root.replaceChild(canvas, previous)
                    viewport.markAll(); resize(); drawing.draw(viewport)
                }
                updateDrawingDiagnostics()
                if (!composing) { input.style.left = "${viewport.cursorColumn * cellWidth}px"; input.style.top = "${viewport.cursorRow * cellHeight}px" }
                if (accessibilityTimer == null) accessibilityTimer = window.setTimeout({
                    accessibilityTimer = null
                    if (!disposed) updateAccessibleText()
                }, 200)
            }
        } as Int
    }

    private fun updateAccessibleText() {
        val next = viewport.visibleText()
        val previous = accessibleText.data as String
        if (next == previous) return
        // Keep the DOM text node and unaffected reading ranges stable across cursor
        // blinks and output updates. Replacing textContent detaches every existing range.
        var start = 0
        while (start < previous.length && start < next.length && previous[start] == next[start]) start++
        if (start > 0 && previous[start - 1].isHighSurrogate()) start--
        var suffix = 0
        while (suffix < previous.length - start && suffix < next.length - start &&
            previous[previous.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        if (suffix > 0 && previous[previous.length - suffix].isLowSurrogate()) suffix--
        accessibleText.replaceData(start, previous.length - start - suffix, next.substring(start, next.length - suffix))
    }

    private fun updateDrawingDiagnostics() {
        root.setAttribute("data-terminal-glyphs", drawing.glyphCount.toString())
        root.setAttribute("data-terminal-rasterizations", drawing.rasterizations.toString())
        root.setAttribute("data-terminal-draw-calls", drawing.drawCalls.toString())
        root.setAttribute("data-terminal-image-bytes", drawing.imageCacheBytes.toString())
        root.setAttribute("data-terminal-image-tiles", drawing.imageTileCount.toString())
        root.setAttribute("data-terminal-image-uploads", drawing.imageUploads.toString())
        root.setAttribute("data-terminal-shaping", drawing.shaping.toString())
        root.setAttribute("data-terminal-fonts", (shapingFonts?.fontCount ?: 0).toString())
        root.setAttribute("data-terminal-font-bytes", (shapingFonts?.fontBytes ?: 0).toString())
        root.setAttribute("data-terminal-font-memory", (shapingFonts?.memoryBytes ?: 0).toString())
    }

    /** Ghostty's platform defaults consume navigation even at a history boundary.
     * Match physical keys and exact modifiers so keypad/TUI bindings still reach the PTY. */
    private fun navigateViewport(key: TerminalKey): Boolean {
        if (key.alt) return false
        val mac = window.navigator.platform.startsWith("Mac") as Boolean
        val scroll = if (mac) key.meta && !key.control && !key.shift
            else key.shift && !key.control && !key.meta
        val prompt = if (mac) key.meta && !key.control
            else key.control && key.shift && !key.meta
        if (mac && scroll && key.key.lowercase() == "j") return viewport.scrollToSelection()
        when (key.physicalCode?.takeIf { it.isNotEmpty() } ?: key.key) {
            "Home" -> if (scroll) viewport.scrollTo(session.historySize) else return false
            "End" -> if (scroll) viewport.scrollTo(0) else return false
            "PageUp" -> if (scroll) viewport.scroll(session.rows) else return false
            "PageDown" -> if (scroll) viewport.scroll(-session.rows) else return false
            "ArrowUp" -> if (prompt) viewport.jumpToPrompt(-1) else return false
            "ArrowDown" -> if (prompt) viewport.jumpToPrompt(1) else return false
            else -> return false
        }
        return true
    }

    private fun key(event: dynamic): TerminalKey {
        val code = event.code as? String
        val keypad = when (code) {
            "Numpad0" -> TerminalKeypadKey.ZERO; "Numpad1" -> TerminalKeypadKey.ONE
            "Numpad2" -> TerminalKeypadKey.TWO; "Numpad3" -> TerminalKeypadKey.THREE
            "Numpad4" -> TerminalKeypadKey.FOUR; "Numpad5" -> TerminalKeypadKey.FIVE
            "Numpad6" -> TerminalKeypadKey.SIX; "Numpad7" -> TerminalKeypadKey.SEVEN
            "Numpad8" -> TerminalKeypadKey.EIGHT; "Numpad9" -> TerminalKeypadKey.NINE
            "NumpadDecimal" -> TerminalKeypadKey.DECIMAL; "NumpadDivide" -> TerminalKeypadKey.DIVIDE
            "NumpadMultiply" -> TerminalKeypadKey.MULTIPLY; "NumpadSubtract" -> TerminalKeypadKey.SUBTRACT
            "NumpadAdd" -> TerminalKeypadKey.ADD; "NumpadEnter" -> TerminalKeypadKey.ENTER
            "NumpadEqual" -> TerminalKeypadKey.EQUAL; "NumpadComma" -> TerminalKeypadKey.SEPARATOR
            else -> null
        }
        // With NumLock off the center key is a navigation key, not the digit five.
        val logical = if (code == "Numpad5" && event.key == "Clear") "Begin" else event.key as String
        val base = terminalPhysicalCodepoint(code)
        val text = logical.takeIf { it.terminalCodepoints().size == 1 }.orEmpty()
        val point = text.lowercase().terminalCodepoints().singleOrNull()
        if (code != null && point != null && event.shiftKey != true && event.altKey != true) layoutKeys[code] = point
        val unshifted = if (event.shiftKey == true || event.altKey == true)
            layoutKeys[code] ?: terminalUnshiftedCodepoint(point, base) else point
        return TerminalKey(logical, event.ctrlKey == true, event.altKey == true, event.shiftKey == true,
            event.metaKey == true, keypad,
            action = if (event.repeat == true) TerminalKeyAction.REPEAT else TerminalKeyAction.PRESS,
            text = text, unshiftedCodepoint = unshifted, baseCodepoint = base, physicalCode = code,
            capsLock = event.getModifierState("CapsLock") == true, numLock = event.getModifierState("NumLock") == true,
            consumedModifiers = if (text.isNotEmpty() && event.shiftKey == true) 1 else 0)
    }
    private fun rectangleSelection(event: dynamic): Boolean = event.altKey == true &&
        (window.navigator.platform.startsWith("Mac") || event.ctrlKey == true)
    private fun linkEventModifiers(event: dynamic) = TerminalLinkModifiers(event.shiftKey == true,
        event.ctrlKey == true, event.altKey == true, event.metaKey == true)
    private fun linkModifiers(event: dynamic): Boolean = linkEventModifiers(event).binding(window.navigator.platform.startsWith("Mac"), session)
    private fun updateLinkPointer(event: dynamic) {
        if (event.pointerType == "touch") { clearLinkPointer(); return }
        hoverClientPoint = (event.clientX as Number).toDouble() to (event.clientY as Number).toDouble()
        hoverModifiers = linkEventModifiers(event)
        refreshLinkHover()
    }
    private fun clearLinkPointer() { hoverClientPoint = null; refreshLinkHover() }
    private fun refreshLinkHover() {
        if (refreshingLinkHover) return
        refreshingLinkHover = true
        try {
            val point = hoverClientPoint
            val pointer = if (point == null || disposed || !graphicsVisible ||
                !hoverModifiers.hoverAllowed(window.navigator.platform.startsWith("Mac"), session, viewport.scrollOffset)) null else {
                val rect = canvas.getBoundingClientRect()
                terminalSelectionPointer(point.first - (rect.left as Number).toDouble(), point.second - (rect.top as Number).toDouble(),
                    cellWidth, cellHeight, (window.devicePixelRatio as Number).toDouble())
            }
            val target = if (pointer != null && (mouseButtons and 1 == 0 || viewport.linkHoverAllowedWhileDragging(pointer)))
                viewport.setLinkHover(pointer.column, pointer.row) else viewport.setLinkHover()
            root.style.cursor = if (target != null) "pointer" else session.pointerShape.cssName
            val preview = terminalLinkPreview(target, linkPreviewMode)
            linkPreview.hidden = preview == null
            linkPreview.textContent = preview.orEmpty()
            linkPreview.title = preview.orEmpty()
            linkPreview.style.background = terminalCssColor(viewport.theme.background)
            linkPreview.style.color = terminalCssColor(viewport.theme.foreground)
        } finally { refreshingLinkHover = false }
    }
    private fun selectionPointer(event: dynamic): TerminalSelectionPointer {
        val rect = canvas.getBoundingClientRect()
        return terminalSelectionPointer((event.clientX as Number).toDouble() - (rect.left as Number).toDouble(),
            (event.clientY as Number).toDouble() - (rect.top as Number).toDouble(), cellWidth, cellHeight,
            (window.devicePixelRatio as Number).toDouble())
    }
    private fun mouse(event: dynamic, button: Int, release: Boolean = false, motion: Boolean = false): Boolean {
        if ((release || motion) && button in localMouseButtons) {
            if (release) localMouseButtons.remove(button)
            return false
        }
        if (viewport.scrollOffset != 0 || canvas.width <= 0 || canvas.height <= 0) return false
        val rect = canvas.getBoundingClientRect()
        val scale = (window.devicePixelRatio as Number).toDouble()
        val geometry = TerminalMouseGeometry((canvas.width as Number).toDouble(), (canvas.height as Number).toDouble(),
            cellWidth * scale, cellHeight * scale)
        return mouseReporter.sendPixels(((event.clientX as Number).toDouble() - (rect.left as Number).toDouble()) * scale,
            ((event.clientY as Number).toDouble() - (rect.top as Number).toDouble()) * scale, geometry,
            button, release, motion, event.shiftKey == true, event.altKey == true, event.ctrlKey == true)
    }
    private fun updateMouseButtons(event: dynamic) {
        val next = (event.buttons as Number).toInt() and 31
        for ((mask, button) in mouseButtonMasks) {
            if ((mouseButtons and mask) == (next and mask)) continue
            if (next and mask != 0) {
                localMouseButtons.remove(button)
                val interval = if (clickRepeatIntervalMillis == 0L) 500.0 else clickRepeatIntervalMillis.toDouble()
                if (button == 0) continuingSelection = event.shiftKey == true && !session.mouseShiftCapture &&
                    viewport.continuePointerSelection(selectionPointer(event), (event.timeStamp as Number).toDouble(), interval,
                        rectangleSelection(event))
                val reported = if (button == 0 && continuingSelection) false else mouse(event, button)
                if (reported) { viewport.resetPointerClicks(); viewport.clearSelection() }
                if (button == 0) {
                    selecting = !reported
                    if (selecting && !continuingSelection) viewport.pressPointerSelection(selectionPointer(event),
                        (event.timeStamp as Number).toDouble(), interval,
                        if (window.navigator.platform.startsWith("Mac")) event.metaKey == true else event.ctrlKey == true)
                } else if (!reported && button in 1..2) {
                    localMouseButtons.add(button)
                    terminalMouseClick(button, rightClickAction, middleClickAction, selectionClipboardSupported,
                        viewport.hasSelection, { copySelection(TerminalCopyFormat.MIXED, clearAfter = false) },
                        { viewport.clearSelection() }, { pasteMouseClipboard(it, event.isTrusted == true) })
                }
            } else {
                if (button == 0 && selecting) {
                    val pointer = selectionPointer(event)
                    val link = if (linkModifiers(event)) viewport.pointerLinkAt(pointer) else null
                    if (link != null && (if (openLink !== defaultOpenLink) openLink(link.uri) else
                        openBrowserTerminalLink(link.uri) { !disposed && link.enabled(session) })) viewport.cancelSelectionDrag()
                    else viewport.endPointerSelection(pointer)
                    selecting = false; continuingSelection = false
                    copySelectionAutomatically()
                }
                else mouse(event, button, release = true)
            }
        }
        mouseButtons = next
    }
    private fun pressedMouseButton(): Int = mouseButtonMasks.firstOrNull { mouseButtons and it.first != 0 }?.second ?: 3
    private fun cancelMouse() {
        val pointer = activePointer
        activePointer = null; selecting = false; continuingSelection = false; mouseButtons = 0
        localMouseButtons.clear()
        viewport.cancelSelectionDrag()
        clearLinkPointer()
        mouseReporter.cancel()
        if (pointer != null && root.hasPointerCapture(pointer) == true) root.releasePointerCapture(pointer)
    }
    private fun restoreWindowFocus() {
        if (disposed) return
        // Render intersection is deliberately not a focus condition: a focused terminal
        // scrolled outside the viewport can still own keyboard input. A shadow-root embed
        // must inspect its own activeElement, rather than the document's shadow host.
        val visible = root.isConnected == true && document.visibilityState != "hidden" &&
            (root.clientWidth as Number).toInt() > 0 && (root.clientHeight as Number).toInt() > 0
        val style = if (visible) window.getComputedStyle(root).visibility as String else "hidden"
        focusChanged(visible && style != "hidden" && style != "collapse" && document.hasFocus() == true &&
            root.contains(root.getRootNode().activeElement) == true)
    }
    private fun focusChanged(value: Boolean) {
        if (disposed || focused == value) return
        if (!value) { keyFocus.release { session.sendKey(it) }; pressedKeys.clear() }
        focused = value; viewport.setFocused(value); session.sendFocus(value)
        if (value) notifications?.clear()
    }
    private fun beforeTypedInput(key: TerminalKey? = null) {
        if (key?.selectionModifier == true) return
        viewport.followOutput()
        if (selectionClearOnTyping || key?.key == "Escape") viewport.clearSelection()
    }
    private fun sendTypedKey(key: TerminalKey): Boolean {
        if (!session.keyboardInputBlocked) keyFocus.record(key)
        return session.sendKey(key) { beforeTypedInput(key) }
    }
    private fun sendTypedText(text: String) {
        if (disposed || session.keyboardInputBlocked || text.isEmpty()) return
        beforeTypedInput(); session.sendText(text)
    }
    private fun clearSelectionForPreedit() { if (selectionClearOnTyping) viewport.clearSelection() }
    private fun copySelectionAutomatically() = copySelection(TerminalCopyFormat.MIXED, copyOnSelect.locations, clearAfter = false)
    private fun pasteMouseClipboard(location: TerminalClipboardLocation, trusted: Boolean) {
        if (disposed || root.isConnected != true) return
        clipboardPaste.cancel()
        if (trusted && session.pasteClipboard(location)) { viewport.followOutput(); return }
        runCatching {
            clipboardPaste.read(pastePolicy, { viewport.followOutput() }) { complete ->
                val clipboard = window.navigator.clipboard
                if (ownedMimeClipboard != null && location == TerminalClipboardLocation.STANDARD &&
                    clipboard?.read == null && clipboard?.readText != null) {
                    clipboard.readText().then({ text: String ->
                        complete(if (text.length <= 8 * 1024 * 1024 && text.encodeToByteArray().size <= 8 * 1024 * 1024) text else null)
                    }, { _: dynamic -> complete(null) })
                } else copyClipboard.readPasteText(location, complete)
            }
        }
    }
    private fun copySelection(format: TerminalCopyFormat = copyFormat,
        locations: List<TerminalClipboardLocation> = listOf(TerminalClipboardLocation.STANDARD),
        clearAfter: Boolean = selectionClearOnCopy) {
        if (locations.isEmpty()) return
        val data = viewport.copyData(format, copyTrimTrailingSpaces, copyCodepointMap) ?: return
        val contents = data.contents()
        for (location in locations) runCatching {
            copyClipboard.write(location, contents) { result ->
                if (!disposed && location == TerminalClipboardLocation.STANDARD && ownedMimeClipboard != null &&
                    result == TerminalClipboardResult.UNSUPPORTED && data.text != null) {
                    runCatching { window.navigator.clipboard?.writeText(data.text)?.catch { _: dynamic -> Unit } }
                }
            }
        }
        if (clearAfter) viewport.clearSelection()
    }
    private fun updateProgress() {
        val report = progressPresentation.report
        progressElement.hidden = report == null
        progressElement.setAttribute("aria-label", progressPresentation.label)
        progressElement.setAttribute("data-state", report?.state?.name?.lowercase().orEmpty())
        val percentage = progressPresentation.percentage
        if (percentage == null) progressElement.removeAttribute("value") else progressElement.value = percentage
        progressElement.style.accentColor = when (report?.state) {
            TerminalProgressState.ERROR -> "#ef4444"
            TerminalProgressState.PAUSE -> "#f59e0b"
            else -> "#3b82f6"
        }
    }
    private fun listen(target: dynamic, name: String, passive: Boolean = true, capture: Boolean = false, action: (dynamic) -> Unit) {
        val options: dynamic = js("({})"); options.passive = passive; options.capture = capture
        // Only wheel needs an explicit passive option; other listeners may cancel native input.
        val actualOptions: dynamic = if (name == "wheel") options else capture
        target.addEventListener(name, action, actualOptions)
        listeners += { target.removeEventListener(name, action, actualOptions) }
    }
    public fun dispose() {
        if (disposed) return
        disposed = true; mouseReporter.reset(); cancelMouse(); viewport.dispose(); resizeObserver.disconnect()
        clipboardPaste.dispose()
        sizeRefreshObservation.dispose()
        windowResizeBinding.dispose()
        dragAndDrop.dispose()
        visibilityBinding.dispose()
        intersectionObserver.disconnect(); visibilityObserver.disconnect()
        pointerObservation.dispose(); linkPolicyObservation.dispose()
        progressPresentation.dispose()
        notificationObservation.dispose(); notifications?.dispose(); notifications = null
        clipboardBinding.dispose()
        mimeClipboardBinding.dispose()
        ownedMimeClipboard?.dispose()
        frame?.let { window.cancelAnimationFrame(it) }; accessibilityTimer?.let { window.clearTimeout(it) }
        listeners.forEach { it() }; listeners.clear()
        drawing.dispose()
        fontLoadGeneration++
        shapingFonts?.dispose(); shapingFonts = null
    }
}

private fun newResizeObserver(callback: () -> Unit): dynamic = js("new ResizeObserver(callback)")
private fun newTerminalIntersectionObserver(callback: (Boolean) -> Unit): dynamic =
    js("new IntersectionObserver(entries => callback(entries.some(entry => entry.isIntersecting)))")
private fun newTerminalVisibilityObserver(root: dynamic, detached: () -> Unit, callback: () -> Unit): dynamic =
    js("new MutationObserver(records => { const removed = records.some(record => Array.from(record.removedNodes).some(node => node === root || node.contains(root))); if (removed) detached(); if (removed || records.some(record => record.target.contains(root))) callback(); })")

private val mouseButtonMasks = listOf(1 to 0, 4 to 1, 2 to 2, 8 to 128, 16 to 129)
