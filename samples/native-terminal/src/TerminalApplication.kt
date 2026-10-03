@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import io.heapy.kinetica.appkit.*
import io.heapy.kinetica.application.*
import io.heapy.kinetica.terminal.*
import platform.AppKit.NSApplication
import platform.Foundation.NSHomeDirectory

/** Domain policy. Kinetica owns windows, tabs, focus, menus and asynchronous shutdown. */
internal class TerminalApplication(
    application: NSApplication,
    quit: () -> Unit = { application.terminate(null) },
    completeTermination: () -> Unit = { application.replyToApplicationShouldTerminate(true) },
    internal val settings: TerminalSettingsStore = TerminalSettingsStore(),
) {
    val shell = AppKitApplication("Kinetica Terminal", nativeApplication = application,
        requestNativeTermination = quit, replyToTermination = { if (it) completeTermination() })
    internal val terminals = mutableListOf<TerminalWindow>()
    internal val menu get() = shell.menu
    private var sequence = 0
    private var settingsWindow: AppKitWindow? = null
    private val active: TerminalWindow? get() = terminals.firstOrNull { it.handle == shell.activeWindow }

    init {
        fun global(id: String, title: String, key: String, action: () -> Unit) {
            shell.commands.register(ApplicationCommand(id, title, listOf(KeyShortcut(key))) { action() })
        }
        fun terminal(id: String, title: String, shortcuts: List<KeyShortcut>,
            enabled: (TerminalWindow) -> Boolean = { true }, action: (TerminalWindow) -> Unit) {
            shell.commands.register(ApplicationCommand(id, title, shortcuts, windowRequired = true,
                state = { context -> CommandState(enabled = terminals.firstOrNull { it.handle.id == context.windowId }?.let(enabled) == true) },
                action = { context -> terminals.firstOrNull { it.handle.id == context.windowId }?.let(action) }))
        }
        fun key(value: String, shift: Boolean = false) = KeyShortcut(value,
            if (shift) setOf(KeyModifier.PRIMARY, KeyModifier.SHIFT) else setOf(KeyModifier.PRIMARY))
        global("app.quit", "Quit Kinetica Terminal", "q", shell::requestQuit)
        global("app.settings", "Settings…", ",", ::showSettings)
        global("window.new", "New Window", "n") { open() }
        global("window.tab", "New Tab", "t") { open(tabbed = true) }
        shell.commands.register(ApplicationCommand("window.close", "Close", listOf(key("w")), windowRequired = true) { shell.activeWindow?.close() })
        terminal("tab.next", "Next Tab", listOf(key("]", true))) { shell.nextTab() }
        terminal("tab.previous", "Previous Tab", listOf(key("[", true))) { shell.nextTab(backwards = true) }
        for (number in 1..9) terminal("tab.$number", "Select Tab $number", listOf(key(number.toString()))) { shell.selectTab(number - 1) }
        for ((id, title, shortcut, edit) in listOf(
            Edit("undo", "Undo", "z", EditingCommand.UNDO), Edit("cut", "Cut", "x", EditingCommand.CUT),
            Edit("copy", "Copy", "c", EditingCommand.COPY), Edit("paste", "Paste", "v", EditingCommand.PASTE),
            Edit("selectAll", "Select All", "a", EditingCommand.SELECT_ALL),
        )) shell.editingCommand("edit.$id", title, key(shortcut), edit)
        terminal("find.show", "Find…", listOf(key("f"))) { it.showFind() }
        terminal("find.next", "Find Next", listOf(key("g"))) { it.navigate(1) }
        terminal("find.previous", "Find Previous", listOf(key("g", true))) { it.navigate(-1) }
        terminal("find.close", "Close Search", listOf(KeyShortcut("Escape", emptySet())), { it.search.value.visible }) { it.hideFind() }
        // Enter acts only while the Kinetica search field is focused; it must not swallow shell input.
        terminal("find.enter", "Next Match", listOf(KeyShortcut("Enter", emptySet())), { it.isSearchFocused }) { it.navigate(1) }
        terminal("find.shiftEnter", "Previous Match", listOf(KeyShortcut("Enter", setOf(KeyModifier.SHIFT))), { it.isSearchFocused }) { it.navigate(-1) }
        terminal("screen.clear", "Clear Screen", listOf(key("k"))) { it.view.clearScreen(null) }
        terminal("history.clear", "Clear Scrollback", listOf(key("k", true))) { it.view.clearHistory(null) }
        terminal("font.increase", "Increase Text Size", listOf(key("+"), key("="))) { it.zoom(1.0) }
        terminal("font.decrease", "Decrease Text Size", listOf(key("-"))) { it.zoom(-1.0) }
        terminal("font.reset", "Reset Text Size", listOf(key("0"))) { it.resetZoom() }
        fun items(vararg ids: String) = ids.map { MenuItem.Command(it) }
        shell.setMenus(listOf(
            ApplicationMenu("Kinetica Terminal", items("app.settings", "app.quit")),
            ApplicationMenu("Shell", items("window.new", "window.tab", "window.close")),
            ApplicationMenu("Window", items("tab.next", "tab.previous") + MenuItem.Separator + (1..9).map { MenuItem.Command("tab.$it") }),
            ApplicationMenu("Edit", items("edit.undo", "edit.cut", "edit.copy", "edit.paste", "edit.selectAll") + MenuItem.Separator +
                items("find.show", "find.next", "find.previous", "screen.clear", "history.clear")),
            ApplicationMenu("View", items("font.increase", "font.decrease", "font.reset")),
        ))
        shell.install()
    }

    fun open(tabbed: Boolean = false): TerminalWindow {
        val parent = if (tabbed) active else null
        val number = ++sequence
        val terminal = TerminalWindow(number, settings, parent?.currentWorkingDirectory() ?: NSHomeDirectory())
        try {
            terminal.handle = shell.openWindow(ApplicationWindow("shell-$number", "Shell $number",
                size = WindowSize(960.0, 600.0), minimumSize = WindowSize(520.0, 280.0),
                tabGroup = "io.heapy.kinetica.terminal", initialFocus = "terminal"),
                tabOf = parent?.handle?.id, restoredBounds = if (parent == null) settings.bounds else null,
                callbacks = WindowCallbacks(boundsChanged = settings::saveBounds,
                    closed = { terminals.remove(terminal) }, release = terminal::close),
                hostWidgets = appKitTerminalHosts(mapOf("shell" to terminal.session), controls = true,
                    onView = { _, view -> terminal.attached(view) }),
            ) { TerminalContent(terminal) }
            terminals += terminal
            // Establish the actual grid before spawning the PTY, including native tab chrome.
            terminal.window.contentView?.layoutSubtreeIfNeeded()
            terminal.start()
            return terminal
        } catch (failure: Throwable) {
            terminal.close {}
            terminal.closeWindowIfMounted()
            throw failure
        }
    }

    private fun showSettings() {
        settingsWindow?.let { it.show(); return }
        val returnTo = active?.handle?.id
        val editor = TerminalSettingsEditor(settings) {
            terminals.forEach { it.applySettings() }
            settingsWindow?.close()
        }
        settingsWindow = shell.openWindow(ApplicationWindow("settings", "Terminal Settings",
            size = WindowSize(500.0, 260.0), minimumSize = WindowSize(500.0, 260.0),
            resizable = false, minimizable = false, initialFocus = "font-size"),
            callbacks = WindowCallbacks(closed = {
                settingsWindow = null
                returnTo?.let { shell.window(it)?.show() }
            }),
        ) { TerminalSettingsContent(editor) }
    }

    fun run() { open(); shell.run() }
    fun dispose() { shell.dispose() }
}

private data class Edit(val id: String, val title: String, val shortcut: String, val command: EditingCommand)
