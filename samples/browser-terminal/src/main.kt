package app.browser.terminal

import io.heapy.kinetica.terminal.*
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.BrowserKineticaApp
import kotlinx.browser.document

fun main() {
    val session = TerminalSession()
    // Explicit diagnostics only: ordinary demo mounts create no renderer timers or samples.
    var renderBridge: dynamic = null
    val renderObserver = if (js("new URLSearchParams(globalThis.location.search).get('profile')") == "1") object : TerminalRenderObserver {
        override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {
            renderBridge?.started(frameId.toDouble(), { viewport.visibleText() })
        }
        override fun onFrameSubmitted(frame: TerminalFrameMetrics) {
            val value: dynamic = js("({})")
            value.id = frame.frameId.toDouble(); value.backend = frame.backend
            value.acquireMillis = frame.acquireMillis; value.layoutMillis = frame.layoutMillis
            value.atlasMillis = frame.atlasMillis; value.batchMillis = frame.batchMillis
            value.submitMillis = frame.submitMillis; value.cpuMillis = frame.cpuMillis; value.drawCalls = frame.drawCalls
            renderBridge?.submitted(value)
        }
        override fun onGpuTime(frameId: Long, milliseconds: Double?) { renderBridge?.gpu(frameId.toDouble(), milliseconds) }
    } else null
    val rendering = when (js("new URLSearchParams(globalThis.location.search).get('renderer')") as? String) {
        "gpu" -> TerminalRendering.GPU
        "software" -> TerminalRendering.SOFTWARE
        else -> TerminalRendering.AUTO
    }
    var fontSize = 14.0
    var fontFamily: String? = null
    var clipboardPolicy = TerminalClipboardPolicy.WRITE_ONLY
    var mimeClipboardPolicy = TerminalMimeClipboardPolicy.WRITE_AND_PASTE
    var notificationAllowed = true
    var windowResizeAllowed = false
    var surface: BrowserTerminalView? = null
    val app = BrowserKineticaApp(checkNotNull(document.querySelector("#app")),
        hostWidgets = browserTerminalHosts(mapOf("demo" to session), rendering, renderObserver,
            TerminalClipboardPolicy { request, done -> clipboardPolicy.authorize(request, done) },
            notificationPolicy = { notificationAllowed },
            windowResizePolicy = { windowResizeAllowed },
            resizeContainer = { _, size ->
                val container = checkNotNull(document.querySelector("#app")).asDynamic()
                size.width?.let { container.style.width = "${it.coerceAtMost((document.body!!.clientWidth - 24).coerceAtLeast(80).toDouble())}px" }
                size.height?.let {
                    val available = (document.body!!.clientHeight - checkNotNull(document.querySelector("header")).clientHeight - 24).coerceAtLeast(40)
                    container.style.flex = "0 0 auto"
                    container.style.height = "${it.coerceAtMost(available.toDouble())}px"
                }
            },
            mimeClipboardPolicy = TerminalMimeClipboardPolicy { request, done -> mimeClipboardPolicy.authorize(request, done) },
            onView = { _, view -> surface = view }),
    ) { terminal("demo", fontSize = fontSize, fontFamily = fontFamily,
        semantics = Semantics(label = "Kinetica terminal", testTag = "terminal", focusable = true)) }.also { it.render() }
    session.write("\u001b[1;36mKinetica — shared Kotlin terminal\u001b[0m\r\n")
    session.write("WebGL2 / Metal surfaces · shared glyph atlas · common VT engine\r\n\r\n")
    for (i in 0..15) session.write("\u001b[48;5;${i}m  ")
    session.write("\u001b[0m\r\nUnicode: 世界  λ  é  😀\r\n\r\n")
    session.write("Local echo demo. Type help, colors, clear, or flood.\r\n$ ")
    val command = StringBuilder()
    var localEcho = true
    val input = session.onInput { data ->
        if (!localEcho) return@onInput
        // The local echo command demo accepts text only; a connected transport handles bytes.
        if (data !is TerminalInputData.Text) return@onInput
        for (c in data.text) when (c) {
            '\r' -> {
                val value = command.toString(); command.clear(); session.write("\r\n")
                when (value) {
                    "help" -> session.write("Commands: help, colors, clear, flood. Connect BrowserTerminalConnection to a PTY server for a real shell.\r\n")
                    "clear" -> session.write("\u001b[2J\u001b[H")
                    "colors" -> { for (i in 0..255) { session.write("\u001b[48;5;${i}m  "); if (i % 32 == 31) session.write("\u001b[0m\r\n") }; session.write("\u001b[0m") }
                    "flood" -> { repeat(2000) { session.write("line $it — bounded scrollback\r\n") } }
                    "" -> Unit
                    else -> session.write("You typed: $value\r\n")
                }
                session.write("$ ")
            }
            '\u007f' -> if (command.isNotEmpty()) { command.deleteAt(command.lastIndex); session.write("\b \b") }
            '\u0003' -> { command.clear(); session.write("^C\r\n$ ") }
            else -> if (c >= ' ') { command.append(c); session.write(c.toString()) }
        }
    }
    // Small deterministic controls used by the browser integration check.
    val api: dynamic = js("({})")
    api.windowResizeEnabled = { enabled: Boolean -> windowResizeAllowed = enabled }
    api.titleReporting = { enabled: Boolean -> session.configureTitleReporting(enabled) }
    val resizeCheckbox = document.querySelector("#window-resize")?.asDynamic()
    resizeCheckbox?.onchange = { windowResizeAllowed = resizeCheckbox.checked == true }
    api.notificationsEnabled = { enabled: Boolean -> notificationAllowed = enabled }
    val notificationButton = document.querySelector("#notifications")?.asDynamic()
    notificationButton?.onclick = {
        surface?.requestNotificationPermission { granted ->
            notificationButton.textContent = if (granted) "Notifications enabled" else "Notifications blocked"
            notificationButton.disabled = granted
        }
    }
    api.clipboardPolicy = { mode: String ->
        clipboardPolicy = when (mode) {
            "deny" -> TerminalClipboardPolicy.DENY
            "write" -> TerminalClipboardPolicy.WRITE_ONLY
            "allow" -> TerminalClipboardPolicy.ALLOW
            else -> error("Unknown clipboard policy")
        }
    }
    api.mimeClipboardPolicy = { mode: String ->
        mimeClipboardPolicy = when (mode) {
            "deny" -> TerminalMimeClipboardPolicy.DENY
            "write" -> TerminalMimeClipboardPolicy.WRITE_ONLY
            "paste" -> TerminalMimeClipboardPolicy.WRITE_AND_PASTE
            "allow" -> TerminalMimeClipboardPolicy.ALLOW
            else -> error("Unknown MIME clipboard policy")
        }
    }
    api.setRenderObserver = { bridge: dynamic ->
        check(renderObserver != null) { "Load with profile=1 to enable renderer diagnostics" }
        renderBridge = bridge
    }
    var connection: TerminalStreamConnection? = null
    // The transport verifier supplies a real WebSocket peer with a bounded credit window.
    api.connectTransport = { bridge: dynamic ->
        connection?.dispose(); localEcho = false; command.clear(); session.reset()
        connection = BrowserTerminalConnection(session, object : TerminalByteTransport {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                bridge.write(bytes.asDynamic().subarray(offset, offset + length)) as Int
            override fun resize(columns: Int, rows: Int) { bridge.resize(columns, rows) }
            override fun consumed(bytes: Int) { bridge.consumed(bytes) }
        }, onInputRejected = { bridge.rejected() }, onFailure = { bridge.failed(it.message ?: "Transport failed") })
    }
    api.receiveBytes = { bytes: ByteArray -> connection?.receive(bytes) ?: false }
    api.finishOutput = { connection?.finishOutput() }
    api.disposeTransport = { connection?.dispose() }
    api.transportState = {
        val state: dynamic = js("({})")
        state.queuedOutputBytes = connection?.queuedOutputBytes ?: 0
        state.pendingInputBytes = connection?.pendingInputBytes ?: 0
        state.paused = connection?.outputPausedForInput ?: false
        state.disposed = connection?.disposed ?: true
        state.outputFinished = connection?.outputFinished ?: false
        state.rejectedInputCount = connection?.rejectedInputCount?.toDouble() ?: 0.0
        state
    }
    api.sendInput = { text: String -> session.sendInput(text) }
    api.terminfoName = { name: String? -> session.terminfoName = name }
    api.allowKeyboardLock = { allowed: Boolean -> session.keyboardLockAllowed = allowed }
    api.linkPreviewMode = { mode: String -> surface?.linkPreviewMode = TerminalLinkPreviewMode.valueOf(mode) }
    api.detectedLinksEnabled = { enabled: Boolean -> session.detectedLinksEnabled = enabled }
    api.osc8LinksEnabled = { enabled: Boolean -> session.osc8LinksEnabled = enabled }
    api.openLink = { handler: (String) -> Boolean -> surface?.openLink = handler }
    api.selectionWordChars = { value: String -> session.selectionWordChars = value }
    api.copyTrimTrailingSpaces = { enabled: Boolean -> surface?.copyTrimTrailingSpaces = enabled }
    api.copyFormat = { format: String -> surface?.copyFormat = TerminalCopyFormat.valueOf(format) }
    api.copyOnSelect = { policy: String -> surface?.copyOnSelect = TerminalCopyOnSelect.valueOf(policy) }
    api.selectionClearOnCopy = { enabled: Boolean -> surface?.selectionClearOnCopy = enabled }
    api.selectionClearOnTyping = { enabled: Boolean -> surface?.selectionClearOnTyping = enabled }
    api.copyCodepointMap = { values: dynamic ->
        surface?.copyCodepointMap = (0 until (values.length as Number).toInt()).map { index ->
            val item = values[index]
            TerminalClipboardCodepointMap((item.first as Number).toInt()..(item.last as Number).toInt(), item.replacement as String)
        }
    }
    api.write = { text: String -> session.write(text) }
    api.configureTheme = { background: Int -> session.configureTheme(TerminalTheme(background = background)) }
    api.screen = { (0 until session.rows).joinToString("\n") { session.screenLine(it).text() } }
    api.columns = { session.columns }; api.rows = { session.rows }; api.history = { session.historySize }
    api.historyStorageBytes = { session.historyStorageBytes.toDouble() }
    api.historyResidentStorageBytes = { session.historyResidentStorageBytes.toDouble() }
    api.jumpToPrompt = { direction: Int -> surface?.jumpToPrompt(direction) ?: false }
    api.scrollOffset = { surface?.scrollOffset ?: 0 }
    api.historyByteLimit = { session.limits.historyBytes.toDouble() }
    api.reset = { session.reset(); command.clear() }
    api.synchronizedOutput = { session.synchronizedOutput }
    api.alternateScreen = { session.alternateScreen }
    api.cursorRow = { session.cursorRow }; api.cursorColumn = { session.cursorColumn }
    api.mouseTracking = { session.mouseTracking }
    api.rerender = { app.render() }; api.dispose = { connection?.dispose(); input.dispose(); app.dispose() }
    api.fontSize = { value: Double -> fontSize = value; app.render() }
    api.fontFamily = { value: String? -> fontFamily = value; app.render() }
    api.shapingFonts = { moduleUrl: String, values: dynamic ->
        val fonts = (0 until (values.length as Number).toInt()).map { index ->
            val value = values[index]
            val bytes = value.data
            BrowserTerminalFont(value.family as String,
                ByteArray((bytes.length as Number).toInt()) { (bytes[it] as Number).toByte() },
                (value.style as? Number)?.toInt() ?: 0,
                if (value.emoji == true) BrowserTerminalFontPresentation.EMOJI else BrowserTerminalFontPresentation.TEXT)
        }
        checkNotNull(surface).configureShapingFonts(moduleUrl, fonts)
    }
    api.clearShapingFonts = { surface?.clearShapingFonts() }
    api.cellMetrics = {
        val metrics: dynamic = js("({})")
        metrics.width = surface?.cellWidth; metrics.height = surface?.cellHeight
        metrics
    }
    val fontStatus = document.getElementById("font-status")?.asDynamic()
    if (js("new URLSearchParams(globalThis.location.search).get('fonts')") == "system") {
        api.fontMode = "system"; fontStatus?.hidden = true
        js("globalThis").kineticaTerminalDemo = api
    } else {
        configureDemoFonts(api).then { _: dynamic ->
            api.fontMode = "bundled"; fontStatus?.hidden = true
            js("globalThis").kineticaTerminalDemo = api
        }.catch { error: dynamic ->
            api.fontMode = "system"; api.fontError = error.toString()
            fontStatus?.textContent = "Fonts unavailable; using system fonts."
            js("globalThis").kineticaTerminalDemo = api
        }
    }
}

private fun configureDemoFonts(api: dynamic): dynamic =
    js("import(new URL('fonts.mjs', globalThis.document.baseURI).href).then(module => module.configureDemoFonts(api))")
