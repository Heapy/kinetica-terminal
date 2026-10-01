@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Each native window/tab owns its view, session and child process for exactly its lifetime. */
internal class TerminalApplication(
    private val application: NSApplication,
    private val quit: () -> Unit = { application.terminate(null) },
    private val completeTermination: () -> Unit = { application.replyToApplicationShouldTerminate(true) },
    internal val settings: TerminalSettingsStore = TerminalSettingsStore(),
) : NSObject(), NSApplicationDelegateProtocol {
    internal val terminals = mutableListOf<TerminalWindow>()
    internal val menu = NSMenu()
    private var disposed = false
    private var sequence = 0
    private var terminating = false
    private val closing = mutableSetOf<TerminalWindow>()
    private var settingsWindow: TerminalSettingsWindow? = null
    private val active: TerminalWindow? get() = terminals.firstOrNull { it.window == application.keyWindow }
        ?: terminals.firstOrNull { it.window == application.mainWindow } ?: terminals.lastOrNull()

    init {
        fun submenu(title: String): NSMenu {
            val child = NSMenu(title)
            menu.addItem(NSMenuItem().also { it.title = title; it.submenu = child })
            return child
        }
        fun NSMenu.command(title: String, selector: String, key: String, shift: Boolean = false) {
            // AppKit matches charactersIgnoringModifiers, which still includes Shift.
            val equivalent = if (shift) when (key) { "[" -> "{"; "]" -> "}"; else -> key.uppercase() } else key
            addItem(NSMenuItem(title, NSSelectorFromString(selector), equivalent).also {
                it.target = this@TerminalApplication
                it.keyEquivalentModifierMask = NSEventModifierFlagCommand or if (shift) NSEventModifierFlagShift else 0uL
            })
        }
        submenu("Kinetica Terminal").apply {
            command("Settings…", "showSettings:", ",")
            addItem(NSMenuItem.separatorItem())
            command("Quit Kinetica Terminal", "quitApplication:", "q")
        }
        submenu("Shell").apply {
            command("New Window", "newWindow:", "n")
            command("New Tab", "newTab:", "t")
            addItem(NSMenuItem.separatorItem())
            command("Close Tab", "closeTab:", "w")
        }
        submenu("Window").apply {
            command("Next Tab", "nextTab:", "]", shift = true)
            command("Previous Tab", "previousTab:", "[", shift = true)
            addItem(NSMenuItem.separatorItem())
            for (number in 1..9) {
                command("Select Tab $number", "selectTab:", number.toString())
                itemAtIndex(numberOfItems - 1)!!.setTag(number.toLong())
            }
        }
        submenu("Edit").apply {
            for ((title, selector, key) in listOf(Triple("Copy", "copy:", "c"), Triple("Paste", "paste:", "v"), Triple("Select All", "selectAll:", "a"))) {
                addItem(NSMenuItem(title, NSSelectorFromString(selector), key).apply { target = null })
            }
            addItem(NSMenuItem.separatorItem())
            command("Find…", "showFind:", "f")
            command("Find Next", "findNext:", "g")
            command("Find Previous", "findPrevious:", "g", shift = true)
            addItem(NSMenuItem.separatorItem())
            command("Clear Screen", "clearScreen:", "k")
            command("Clear Scrollback", "clearHistory:", "k", shift = true)
        }
        submenu("View").apply {
            command("Increase Text Size", "zoomIn:", "+")
            command("Increase Text Size", "zoomIn:", "=")
            itemAtIndex(numberOfItems - 1)!!.hidden = true // Cmd+= is the unshifted + key.
            itemAtIndex(numberOfItems - 1)!!.allowsKeyEquivalentWhenHidden = true
            command("Decrease Text Size", "zoomOut:", "-")
            command("Reset Text Size", "zoomReset:", "0")
        }
    }

    @ObjCAction fun newWindow(sender: NSObject?) { create(null) }
    @ObjCAction fun newTab(sender: NSObject?) { create(active?.window) }
    @ObjCAction fun closeTab(sender: NSObject?) { active?.window?.performClose(sender) }
    @ObjCAction fun nextTab(sender: NSObject?) { active?.window?.selectNextTab(sender); focusActive() }
    @ObjCAction fun previousTab(sender: NSObject?) { active?.window?.selectPreviousTab(sender); focusActive() }
    @ObjCAction fun quitApplication(sender: NSObject?) { if (!disposed) quit() }
    @ObjCAction fun showSettings(sender: NSObject?) {
        val panel = settingsWindow ?: TerminalSettingsWindow(settings) { terminals.forEach { it.applySettings() } }.also { settingsWindow = it }
        panel.show()
    }
    @ObjCAction fun showFind(sender: NSObject?) { active?.pane?.showFind(sender) }
    @ObjCAction fun findNext(sender: NSObject?) { active?.pane?.findNext(sender) }
    @ObjCAction fun findPrevious(sender: NSObject?) { active?.pane?.findPrevious(sender) }
    @ObjCAction fun clearScreen(sender: NSObject?) { active?.pane?.terminal?.clearScreen(sender) }
    @ObjCAction fun clearHistory(sender: NSObject?) { active?.pane?.terminal?.clearHistory(sender) }
    @ObjCAction fun zoomIn(sender: NSObject?) { active?.zoom(1.0) }
    @ObjCAction fun zoomOut(sender: NSObject?) { active?.zoom(-1.0) }
    @ObjCAction fun zoomReset(sender: NSObject?) { active?.resetZoom() }
    @ObjCAction fun selectTab(sender: NSObject?) {
        val index = (sender as? NSMenuItem)?.tag?.toInt()?.minus(1) ?: return
        val group = active?.window?.tabGroup?.windows?.filterIsInstance<NSWindow>() ?: active?.let { listOf(it.window) }.orEmpty()
        group.getOrNull(index)?.makeKeyAndOrderFront(null); focusActive()
    }

    private fun create(tabParent: NSWindow?) {
        if (disposed) return
        val directory = if (tabParent != null) active?.currentWorkingDirectory() else null
        val terminal = TerminalWindow(++sequence, settings, directory) { terminals.remove(it); awaitClosed(it) }
        terminals.add(terminal)
        if (tabParent == null) {
            if (!settings.restoreFrame(terminal.window)) terminal.window.center()
        } else tabParent.addTabbedWindow(terminal.window, NSWindowAbove)
        terminal.window.makeKeyAndOrderFront(null)
        terminal.focus()
    }

    private fun focusActive() { active?.focus() }
    override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean = true
    override fun applicationShouldTerminate(sender: NSApplication): NSApplicationTerminateReply {
        if (terminals.isEmpty() && closing.isEmpty()) return NSTerminateNow
        terminating = true
        dispose()
        return NSTerminateLater
    }
    override fun applicationWillTerminate(notification: NSNotification) { dispose() }

    private fun awaitClosed(terminal: TerminalWindow) {
        if (!closing.add(terminal)) return
        terminal.close {
            closing.remove(terminal)
            // Even an already-exited shell must reply after applicationShouldTerminate returns.
            dispatch_async(dispatch_get_main_queue()) {
                if (terminating && terminals.isEmpty() && closing.isEmpty()) {
                    terminating = false
                    completeTermination()
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        settingsWindow?.close(); settingsWindow = null
        val remaining = terminals.toList()
        terminals.clear()
        remaining.forEach { awaitClosed(it) }
    }
}

internal class TerminalWindow(number: Int, private val settings: TerminalSettingsStore,
    directory: String?, private val closed: (TerminalWindow) -> Unit) : NSObject(), NSWindowDelegateProtocol {
    val window = NSWindow(NSMakeRect(0.0, 0.0, 960.0, 600.0),
        NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
        NSBackingStoreBuffered, false)
    val session = TerminalSession(theme = settings.settings.colorTheme.colors)
    private val fallbackTitle = "Shell $number"
    private var disposed = false
    private var zoom = 0.0
    private var configuredTheme = settings.settings.colorTheme
    val pane = AppKitTerminalPane(AppKitTerminalView(session, "shell")).also {
        it.terminal.configureFont(settings.settings.fontName, settings.settings.fontSize)
        it.setFrame(window.contentView!!.bounds)
        it.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
        window.contentView = it
    }
    private val titleSubscription = session.observe {
        if (!disposed) window.title = session.title.ifEmpty { fallbackTitle }
    }
    private val transport: MacOsPty

    init {
        window.title = fallbackTitle
        window.tabbingIdentifier = "io.heapy.kinetica.terminal"
        window.setReleasedWhenClosed(false)
        window.minSize = NSMakeSize(520.0, 280.0)
        try {
            transport = MacOsPty(session, workingDirectory = directory ?: NSHomeDirectory(), onExit = {
                // EOF is delivered on the main thread after the child has been reaped.
                // Close this shell's window, including when its tab is not selected.
                if (!disposed) window.close()
            })
        } catch (error: Throwable) {
            titleSubscription.dispose(); pane.dispose(); window.close()
            throw error
        }
        window.delegate = this
    }

    fun focus() { window.makeFirstResponder(pane.terminal) }
    fun currentWorkingDirectory(): String? = transport.currentWorkingDirectory()
    fun applySettings() {
        val value = settings.settings
        applyFont()
        if (configuredTheme != value.colorTheme) {
            configuredTheme = value.colorTheme
            session.configureTheme(value.colorTheme.colors)
        }
    }
    private fun applyFont() {
        val value = settings.settings
        pane.terminal.configureFont(value.fontName, (value.fontSize + zoom).coerceIn(6.0, 96.0))
    }
    fun zoom(delta: Double) {
        val current = pane.terminal.currentFontSize
        zoom = (current + delta).coerceIn(6.0, 96.0) - settings.settings.fontSize
        applyFont()
    }
    fun resetZoom() { zoom = 0.0; applyFont() }
    override fun windowDidBecomeKey(notification: NSNotification) { focus() }
    override fun windowDidMove(notification: NSNotification) { if (!disposed && window.visible) settings.saveFrame(window.frame) }
    override fun windowDidResize(notification: NSNotification) { if (!disposed && window.visible) settings.saveFrame(window.frame) }
    override fun windowWillClose(notification: NSNotification) { dispose(); closed(this) }
    fun close(onClosed: () -> Unit) { dispose(); transport.close(onClosed) }
    fun dispose() {
        if (disposed) return
        disposed = true
        titleSubscription.dispose(); transport.dispose(); pane.dispose()
        window.delegate = null
    }
}
