@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import io.heapy.kinetica.*
import io.heapy.kinetica.appkit.AppKitWindow
import io.heapy.kinetica.terminal.*
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.Foundation.*

internal data class SearchState(val visible: Boolean = false, val query: String = "", val matchCase: Boolean = false,
    val index: Int = -1, val count: Int = 0, val truncated: Boolean = false)

/** A shell's state and resources; the framework owns its UI and window. */
internal class TerminalWindow(private val number: Int, private val settings: TerminalSettingsStore, private val directory: String) {
    lateinit var handle: AppKitWindow
    val window get() = handle.nativeWindow
    val session = TerminalSession(theme = settings.settings.colorTheme.colors)
    val font = store(settings.settings)
    val search = store(SearchState())
    private var surface: AppKitTerminalView? = null
    val view get() = checkNotNull(surface)
    private var transport: MacOsPty? = null
    private var disposed = false
    private var zoomOffset = 0.0
    private var configuredTheme = settings.settings.colorTheme
    private var timer: NSTimer? = null
    private var results = TerminalSearchResults(emptyList(), false)
    private val observation = session.observe {
        if (!disposed) {
            if (::handle.isInitialized) handle.title = session.title.ifEmpty { "Shell $number" }
            if (search.value.visible && search.value.query.isNotEmpty()) scheduleSearch(false)
        }
    }
    val isSearchFocused: Boolean get() = search.value.visible && ::handle.isInitialized &&
        ((window.firstResponder as? NSTextView)?.delegate as? NSTextField)?.identifier == "find"

    fun attached(view: AppKitTerminalView?) { surface = view }
    fun start() {
        check(!disposed && transport == null)
        transport = MacOsPty(session, workingDirectory = directory, onExit = {
            if (!disposed) handle.close()
        })
    }
    fun closeWindowIfMounted() { if (::handle.isInitialized) handle.close() }
    fun currentWorkingDirectory(): String? = transport?.currentWorkingDirectory()
    fun close(done: () -> Unit) {
        if (!disposed) {
            disposed = true
            observation.dispose(); timer?.invalidate(); timer = null
        }
        transport?.close(done) ?: done()
    }
    fun applySettings() {
        val value = settings.settings
        font.value = value.copy(fontSize = (value.fontSize + zoomOffset).coerceIn(6.0, 96.0))
        if (configuredTheme != value.colorTheme) { configuredTheme = value.colorTheme; session.configureTheme(value.colorTheme.colors) }
        if (::handle.isInitialized) handle.renderer?.renderUntilSettled()
    }
    fun zoom(delta: Double) { zoomOffset = (font.value.fontSize + delta).coerceIn(6.0, 96.0) - settings.settings.fontSize; applySettings() }
    fun resetZoom() { zoomOffset = 0.0; applySettings() }
    fun showFind() {
        search.value = search.value.copy(visible = true)
        handle.renderer?.renderUntilSettled()
        handle.focus("find")
        refreshSearch(true)
    }
    fun hideFind() {
        timer?.invalidate(); timer = null
        search.value = search.value.copy(visible = false)
        surface?.clearSelection()
        handle.renderer?.renderUntilSettled()
        handle.focus("terminal")
    }
    fun find(query: String, matchCase: Boolean = search.value.matchCase) {
        search.value = search.value.copy(query = query, matchCase = matchCase)
        scheduleSearch(true)
    }
    private fun scheduleSearch(reset: Boolean) {
        if (disposed) return
        if (reset) { timer?.invalidate(); timer = null }
        if (timer != null) return
        timer = NSTimer.timerWithTimeInterval(0.15, repeats = false) { timer = null; refreshSearch(reset) }
            .also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
    }
    private fun refreshSearch(reset: Boolean) {
        timer?.invalidate(); timer = null
        val state = search.value
        results = session.search(state.query, state.matchCase)
        val index = if (results.matches.isEmpty()) -1 else if (reset) results.matches.lastIndex else state.index.coerceIn(0, results.matches.lastIndex)
        search.value = state.copy(index = index, count = results.matches.size, truncated = results.truncated)
        reveal()
    }
    fun navigate(direction: Int) {
        if (!search.value.visible) { showFind(); return }
        if (timer != null) refreshSearch(false)
        if (results.matches.isEmpty()) return
        search.value = search.value.copy(index = (search.value.index + direction + results.matches.size) % results.matches.size)
        reveal()
    }
    private fun reveal() {
        results.matches.getOrNull(search.value.index)?.let { surface?.reveal(it) } ?: surface?.clearSelection()
    }
}

@UiComponent
internal fun ComponentScope.TerminalContent(terminal: TerminalWindow) {
    val settings = terminal.font.value
    val search = terminal.search.value
    host("column", props = mapOf("spacing" to "0")) {
        if (search.visible) {
            host("row", props = mapOf("padding" to "6")) {
                textInput(search.query, onInput = { terminal.find(it) }, placeholder = "Find in terminal",
                    semantics = Semantics(label = "Find in terminal", testTag = "find", focusable = true))
                text(if (search.query.isEmpty()) "" else "${search.index + 1} / ${search.count}${if (search.truncated) "+" else ""}")
                checkbox(search.matchCase, onToggle = { terminal.find(search.query, !search.matchCase) }, semantics = Semantics(label = "Match case"))
                text("Aa")
                button(onClick = { terminal.navigate(-1) }, enabled = search.count > 0, semantics = Semantics(label = "Previous match")) { text("‹") }
                button(onClick = { terminal.navigate(1) }, enabled = search.count > 0, semantics = Semantics(label = "Next match")) { text("›") }
                button(onClick = terminal::hideFind, semantics = Semantics(label = "Close search")) { text("Done") }
            }
        }
        terminal("shell", fontSize = settings.fontSize, fontFamily = settings.fontName,
            semantics = Semantics(label = "Terminal", testTag = "terminal", focusable = true))
    }
}
