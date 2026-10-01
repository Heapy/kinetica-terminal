package io.heapy.kinetica.terminal

import org.w3c.dom.Element
import kotlin.math.floor

/** A framework-independent terminal surface. Append [view] to a sized DOM container.
 * Dispose the surface before removing its element; the application owns the session/transport. */
public class BrowserTerminalView(
    public val session: TerminalSession,
    private val sessionId: String = "terminal",
    private val rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
) {
    private val document: dynamic = js("globalThis.document")
    private val window: dynamic = js("globalThis.window")
    public val view: Element = document.createElement("div").unsafeCast<Element>()
    private val root: dynamic = view.asDynamic()
    private val renderProbe = TerminalRenderProbe(renderObserver)
    private var drawing = createBrowserTerminalDrawing(rendering, ::schedule, renderProbe)
    private val canvas: dynamic get() = drawing.canvas
    private val input: dynamic = document.createElement("textarea")
    private val accessible: dynamic = document.createElement("pre")
    private val composition: dynamic = document.createElement("div")
    private val measureContext: dynamic = document.createElement("canvas").getContext("2d")
    private var fontSize = 14.0
    private var fontFamily: String? = null
    private var cellWidth = 8.0
    private var cellHeight = 18.0
    private var pixelRatio = 0.0
    private var frame: Int? = null
    private var accessibilityTimer: Int? = null
    private var disposed = false
    private var composing = false
    private var suppressComposition: String? = null
    private var selecting = false
    private var mouseButtons = 0
    private var activePointer: Int? = null
    private var focused = false
    private val mouseReporter = TerminalMouseReporter(session)
    private val listeners = mutableListOf<() -> Unit>()
    private val viewport = TerminalViewport(session, ::schedule, TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    })
    private val resizeObserver: dynamic = newResizeObserver { resize() }

    init {
        root.style.cssText = "position:relative;width:100%;height:100%;min-width:80px;min-height:40px;overflow:hidden;background:#1e1e1e;flex:1;user-select:none;touch-action:none;"
        root.setAttribute("data-terminal", sessionId)
        root.setAttribute("tabindex", "0")
        root.setAttribute("aria-label", "Terminal")
        configureCanvas()
        viewport.setFocused(false)
        input.style.cssText = "position:absolute;opacity:0;width:1px;height:1em;padding:0;border:0;resize:none;overflow:hidden;"
        input.setAttribute("aria-label", "Terminal input")
        input.setAttribute("autocomplete", "off"); input.setAttribute("autocapitalize", "off")
        input.setAttribute("spellcheck", "false")
        accessible.style.cssText = "position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:pre;"
        accessible.setAttribute("role", "log"); accessible.setAttribute("aria-label", "Terminal output")
        accessible.setAttribute("aria-live", "off")
        composition.style.cssText = "position:absolute;display:none;background:#1e1e1e;color:#d4d4d4;text-decoration:underline;white-space:pre;pointer-events:none;"
        composition.setAttribute("aria-hidden", "true")
        root.appendChild(canvas); root.appendChild(input); root.appendChild(accessible); root.appendChild(composition)
        listen(input, "keydown") { event ->
            event.stopPropagation()
            if (event.isComposing == true || composing || event.keyCode == 229) return@listen
            val key = key(event)
            if ((key.meta || key.control && key.shift) && key.key.lowercase() == "c") {
                val text = viewport.selectedText()
                if (text.isNotEmpty()) { copy(text); event.preventDefault() }
            } else if (key.shift && key.key == "PageUp") {
                viewport.scroll(session.rows - 1); event.preventDefault()
            } else if (key.shift && key.key == "PageDown") {
                viewport.scroll(1 - session.rows); event.preventDefault()
            } else if (session.sendKey(key)) {
                viewport.followOutput(); viewport.clearSelection(); event.preventDefault()
            }
        }
        listen(input, "input") { event ->
            event.stopPropagation()
            if (!composing && event.isComposing != true) {
                val value = input.value as String
                input.value = ""
                val duplicateComposition = value == suppressComposition &&
                    (event.inputType == "insertCompositionText" || event.inputType == "insertFromComposition")
                if (value.isNotEmpty() && !duplicateComposition) { viewport.followOutput(); viewport.clearSelection(); session.sendInput(value) }
                suppressComposition = null
            }
        }
        listen(input, "compositionstart") { composing = true }
        listen(input, "compositionupdate") { event ->
            composition.textContent = (event.data as? String).orEmpty()
            composition.style.display = "block"; composition.style.font = font()
            composition.style.left = input.style.left; composition.style.top = input.style.top
        }
        listen(input, "compositionend") { event ->
            composing = false
            composition.style.display = "none"; composition.textContent = ""
            val text = (event.data as? String).orEmpty()
            if (text.isNotEmpty()) { viewport.followOutput(); viewport.clearSelection(); session.sendInput(text) }
            input.value = ""; suppressComposition = text
        }
        listen(input, "paste") { event ->
            event.preventDefault(); event.stopPropagation()
            val text = event.clipboardData?.getData("text/plain") as? String
            if (text != null) { viewport.followOutput(); viewport.clearSelection(); session.paste(text) }
        }
        listen(root, "copy") { event ->
            val text = viewport.selectedText()
            if (text.isNotEmpty()) { event.clipboardData?.setData("text/plain", text); event.preventDefault(); event.stopPropagation() }
        }
        listen(input, "focus") { focusChanged(true) }
        listen(input, "blur") { event ->
            if (root.contains(event.relatedTarget) != true) { cancelMouse(); focusChanged(false) }
        }
        // AppKit-like focus ownership for the whole widget, including its focusable host root.
        listen(root, "focus") { event -> if (event.target === root) input.focus(js("({preventScroll:true})")) }
        listen(window, "blur") { cancelMouse(); focusChanged(false) }
        listen(root, "pointerdown") { event ->
            val id = (event.pointerId as Number).toInt()
            if (activePointer != null && activePointer != id) return@listen
            activePointer = id
            input.focus(js("({preventScroll:true})")); event.stopPropagation()
            // Mouse compatibility events carry the OS click count; PointerEvent.detail is 0.
            // Cancel the following mousedown instead, retaining double/triple-click dragging.
            if (event.pointerType != "mouse") event.preventDefault()
            updateMouseButtons(event)
            root.setPointerCapture(event.pointerId)
        }
        // Cancel compatibility mouse events that would start native selection/autoscroll.
        listen(root, "mousedown") { event ->
            event.preventDefault(); event.stopPropagation()
            if (selecting && activePointer != null && event.button == 0 && (event.detail as Number).toInt() >= 2) {
                val (x, y) = coordinates(event)
                viewport.beginSelection(x, y, if ((event.detail as Number).toInt() == 2) TerminalSelectionMode.WORD else TerminalSelectionMode.LINE)
            }
        }
        listen(root, "pointermove") { event ->
            val id = (event.pointerId as Number).toInt()
            if (activePointer != null && activePointer != id || activePointer == null && event.buttons != 0) return@listen
            val previous = mouseButtons
            updateMouseButtons(event)
            val (x, y) = coordinates(event)
            if (selecting) viewport.extendSelection(x, y)
            else if (previous == mouseButtons) mouse(event, x, y, pressedMouseButton(), motion = true)
        }
        listen(root, "pointerup") { event ->
            if (activePointer != (event.pointerId as Number).toInt()) return@listen
            updateMouseButtons(event)
            activePointer = null
            if (root.hasPointerCapture(event.pointerId) == true) root.releasePointerCapture(event.pointerId)
        }
        listen(root, "pointercancel") { event -> if (activePointer == (event.pointerId as Number).toInt()) cancelMouse() }
        listen(root, "lostpointercapture") { event ->
            if (activePointer == (event.pointerId as Number).toInt() && root.hasPointerCapture(event.pointerId) != true) cancelMouse()
        }
        listen(root, "contextmenu") { event -> if (viewport.scrollOffset == 0 && session.mouseTracking != 0 && event.shiftKey != true) event.preventDefault() }
        listen(root, "wheel", passive = false) { event ->
            val dy = (event.deltaY as Number).toDouble()
            val dx = (event.deltaX as Number).toDouble()
            if (dy != 0.0 || dx != 0.0) {
                event.preventDefault(); event.stopPropagation()
                val (x, y) = coordinates(event)
                if (dy != 0.0 && !mouse(event, x, y, if (dy < 0) 64 else 65)) viewport.scroll(if (dy < 0) 3 else -3)
                if (dx != 0.0) mouse(event, x, y, if (dx < 0) 66 else 67)
            }
        }
        // Prevent surrounding Kinetica event handlers from treating terminal interaction as a
        // control submission/click. Raw listeners above own this entire subtree.
        for (type in listOf("click", "dblclick", "change", "keyup")) listen(root, type) { it.stopPropagation() }
        listen(window, "resize") { resize() }
        resizeObserver.observe(root)
        schedule()
    }

    public fun configureFont(family: String?, size: Double) {
        require(size.isFinite() && size in 6.0..96.0)
        require(family == null || family.isNotBlank() && family.length <= 256 && family.none { it.code < 32 })
        if (fontSize != size || fontFamily != family) { fontSize = size; fontFamily = family; pixelRatio = 0.0; resize() }
    }

    private fun font(style: Int = 0): String = browserFont(fontSize, style, fontFamily)

    private fun configureCanvas() {
        canvas.style.cssText = "position:absolute;inset:0;"
        canvas.setAttribute("aria-hidden", "true")
        root.setAttribute("data-terminal-renderer", drawing.name)
    }

    private fun resize() {
        if (disposed) return
        val width = (root.clientWidth as Number).toDouble()
        val height = (root.clientHeight as Number).toDouble()
        if (width <= 0 || height <= 0) return
        measureContext.font = font()
        cellWidth = (measureContext.measureText("M").width as Number).toDouble()
        cellHeight = kotlin.math.ceil(fontSize * 1.3)
        val ratio = (window.devicePixelRatio as Number).toDouble()
        val w = (width * ratio).toInt(); val h = (height * ratio).toInt()
        if (canvas.width != w || canvas.height != h || pixelRatio != ratio) {
            pixelRatio = ratio
            viewport.markAll()
        }
        drawing.resize(width, height, ratio, fontSize, cellWidth, cellHeight, fontFamily)
        val columns = floor(width / cellWidth).toInt().coerceIn(1, 4096)
        val rows = floor(height / cellHeight).toInt().coerceIn(1, 4096)
        if (columns != session.columns || rows != session.rows) session.resize(columns, rows)
    }

    private fun schedule() {
        if (disposed || frame != null) return
        frame = window.requestAnimationFrame { _: Double ->
            frame = null
            if (!disposed) {
                resize()
                root.style.background = terminalCssColor(viewport.theme.background)
                composition.style.background = terminalCssColor(viewport.theme.background)
                composition.style.color = terminalCssColor(viewport.theme.foreground)
                try { drawing.draw(viewport) } catch (error: Exception) {
                    if (rendering != TerminalRendering.AUTO || drawing.name == "canvas2d") throw error
                    root.setAttribute("data-terminal-rendering-error", error.message.orEmpty())
                    val previous = canvas
                    drawing.dispose(); drawing = CanvasTerminalDrawing(renderProbe); configureCanvas()
                    root.replaceChild(canvas, previous)
                    viewport.markAll(); resize(); drawing.draw(viewport)
                }
                root.setAttribute("data-terminal-glyphs", drawing.glyphCount.toString())
                root.setAttribute("data-terminal-rasterizations", drawing.rasterizations.toString())
                root.setAttribute("data-terminal-draw-calls", drawing.drawCalls.toString())
                if (!composing) { input.style.left = "${viewport.cursorColumn * cellWidth}px"; input.style.top = "${viewport.cursorRow * cellHeight}px" }
                if (accessibilityTimer == null) accessibilityTimer = window.setTimeout({
                    accessibilityTimer = null
                    if (!disposed) accessible.textContent = viewport.visibleText()
                }, 200)
            }
        } as Int
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
        return TerminalKey(logical, event.ctrlKey == true, event.altKey == true, event.shiftKey == true,
            event.metaKey == true, keypad)
    }
    private fun coordinates(event: dynamic): Pair<Int, Int> {
        val rect = canvas.getBoundingClientRect()
        return floor(((event.clientX as Number).toDouble() - (rect.left as Number).toDouble()) / cellWidth).toInt() to
            floor(((event.clientY as Number).toDouble() - (rect.top as Number).toDouble()) / cellHeight).toInt()
    }
    private fun mouse(event: dynamic, x: Int, y: Int, button: Int, release: Boolean = false, motion: Boolean = false): Boolean =
        viewport.scrollOffset == 0 && mouseReporter.send(x, y, button, release, motion, event.shiftKey == true, event.altKey == true, event.ctrlKey == true)
    private fun updateMouseButtons(event: dynamic) {
        val next = (event.buttons as Number).toInt() and 31
        val (x, y) = coordinates(event)
        for ((mask, button) in mouseButtonMasks) {
            if ((mouseButtons and mask) == (next and mask)) continue
            if (next and mask != 0) {
                val reported = mouse(event, x, y, button)
                if (button == 0) {
                    selecting = !reported
                    if (selecting) viewport.beginSelection(x, y) else viewport.clearSelection()
                }
            } else {
                if (button == 0 && selecting) { viewport.endSelection(x, y); selecting = false }
                else mouse(event, x, y, button, release = true)
            }
        }
        mouseButtons = next
    }
    private fun pressedMouseButton(): Int = mouseButtonMasks.firstOrNull { mouseButtons and it.first != 0 }?.second ?: 3
    private fun cancelMouse() {
        val pointer = activePointer
        activePointer = null; selecting = false; mouseButtons = 0
        viewport.cancelSelectionDrag()
        mouseReporter.cancel()
        if (pointer != null && root.hasPointerCapture(pointer) == true) root.releasePointerCapture(pointer)
    }
    private fun focusChanged(value: Boolean) {
        if (focused == value) return
        focused = value; viewport.setFocused(value); session.sendFocus(value)
    }
    private fun copy(text: String) { window.navigator.clipboard?.writeText(text)?.catch { _: dynamic -> Unit } }
    private fun listen(target: dynamic, name: String, passive: Boolean = true, action: (dynamic) -> Unit) {
        val options: dynamic = js("({})"); options.passive = passive
        // Only wheel needs an explicit passive option; other listeners may cancel native input.
        val actualOptions: dynamic = if (name == "wheel") options else false
        target.addEventListener(name, action, actualOptions)
        listeners += { target.removeEventListener(name, action, actualOptions) }
    }
    public fun dispose() {
        if (disposed) return
        disposed = true; mouseReporter.reset(); cancelMouse(); viewport.dispose(); resizeObserver.disconnect()
        frame?.let { window.cancelAnimationFrame(it) }; accessibilityTimer?.let { window.clearTimeout(it) }
        listeners.forEach { it() }; listeners.clear()
        drawing.dispose()
    }
}

private fun newResizeObserver(callback: () -> Unit): dynamic = js("new ResizeObserver(callback)")

private val mouseButtonMasks = listOf(1 to 0, 4 to 1, 2 to 2, 8 to 128, 16 to 129)
