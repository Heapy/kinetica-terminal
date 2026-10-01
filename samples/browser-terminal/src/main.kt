package app.browser.terminal

import io.heapy.kinetica.terminal.*
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
    val surface = BrowserTerminalView(session, "demo", rendering, renderObserver)
    surface.view.setAttribute("data-testid", "terminal")
    surface.view.setAttribute("aria-label", "Kinetica terminal")
    checkNotNull(document.querySelector("#app")).appendChild(surface.view)
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
    api.write = { text: String -> session.write(text) }
    api.screen = { (0 until session.rows).joinToString("\n") { session.screenLine(it).text() } }
    api.columns = { session.columns }; api.rows = { session.rows }; api.history = { session.historySize }
    api.historyStorageBytes = { session.historyStorageBytes.toDouble() }
    api.historyByteLimit = { session.limits.historyBytes.toDouble() }
    api.reset = { session.reset(); command.clear() }
    api.synchronizedOutput = { session.synchronizedOutput }
    api.alternateScreen = { session.alternateScreen }
    api.cursorRow = { session.cursorRow }; api.cursorColumn = { session.cursorColumn }
    api.mouseTracking = { session.mouseTracking }
    api.rerender = { surface.configureFont(fontFamily, fontSize) }; api.dispose = { connection?.dispose(); input.dispose(); surface.dispose(); surface.view.parentNode?.removeChild(surface.view) }
    api.fontSize = { value: Double -> fontSize = value; surface.configureFont(fontFamily, fontSize) }
    api.fontFamily = { value: String? -> fontFamily = value; surface.configureFont(fontFamily, fontSize) }
    js("globalThis").kineticaTerminalDemo = api
}
