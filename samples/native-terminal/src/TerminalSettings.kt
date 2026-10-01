@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.TerminalTheme
import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject

internal enum class TerminalColorTheme(val label: String, val colors: TerminalTheme) {
    DARK("Dark", TerminalTheme()),
    LIGHT("Light", TerminalTheme(0x242424, 0xfafafa, 0x222222, 0xb7d8ff)),
    SOLARIZED("Solarized", TerminalTheme(0x839496, 0x002b36, 0x93a1a1, 0x174652)),
}

internal data class TerminalSettings(val fontName: String? = null, val fontSize: Double = 14.0,
    val colorTheme: TerminalColorTheme = TerminalColorTheme.DARK)

/** Null defaults provide an isolated in-memory store for native application tests. */
internal class TerminalSettingsStore(private val defaults: NSUserDefaults? = NSUserDefaults.standardUserDefaults) {
    var settings: TerminalSettings = TerminalSettings(
        defaults?.stringForKey("terminal.font")?.takeIf { it.isNotBlank() && NSFont.fontWithName(it, 14.0) != null },
        defaults?.doubleForKey("terminal.fontSize")?.takeIf { it.isFinite() && it in 6.0..96.0 } ?: 14.0,
        TerminalColorTheme.entries.firstOrNull { it.name == defaults?.stringForKey("terminal.theme") } ?: TerminalColorTheme.DARK)
        private set
    private var savedFrame: String? = defaults?.stringForKey("terminal.windowFrame")
    fun save(value: TerminalSettings) {
        require(value.fontSize.isFinite() && value.fontSize in 6.0..96.0)
        settings = value
        defaults?.setObject(value.fontName.orEmpty(), forKey = "terminal.font")
        defaults?.setDouble(value.fontSize, forKey = "terminal.fontSize")
        defaults?.setObject(value.colorTheme.name, forKey = "terminal.theme")
    }
    fun saveFrame(frame: CValue<NSRect>) {
        savedFrame = NSStringFromRect(frame)
        defaults?.setObject(savedFrame, forKey = "terminal.windowFrame")
    }
    fun restoreFrame(window: NSWindow): Boolean {
        val frame = savedFrame?.let(::NSRectFromString) ?: return false
        val valid = frame.useContents { origin.x.isFinite() && origin.y.isFinite() && size.width.isFinite() && size.height.isFinite() && size.width > 0 && size.height > 0 }
        if (!valid) return false
        val screens = NSScreen.screens.filterIsInstance<NSScreen>()
        val screen = screens.maxByOrNull { screen ->
            NSIntersectionRect(frame, screen.visibleFrame).useContents { size.width * size.height }
        } ?: return false
        val constrained = frame.useContents {
            val x = origin.x; val y = origin.y; val width = size.width; val height = size.height
            screen.visibleFrame.useContents {
                val w = width.coerceIn(minOf(520.0, size.width), size.width)
                val h = height.coerceIn(minOf(280.0, size.height), size.height)
                NSMakeRect(x.coerceIn(origin.x, origin.x + size.width - w),
                    y.coerceIn(origin.y, origin.y + size.height - h), w, h)
            }
        }
        window.setFrame(constrained, display = false)
        return true
    }
}

internal class TerminalSettingsWindow(private val store: TerminalSettingsStore, private val changed: () -> Unit) : NSObject() {
    private val window = NSPanel(NSMakeRect(0.0, 0.0, 460.0, 240.0), NSWindowStyleMaskTitled or NSWindowStyleMaskClosable,
        NSBackingStoreBuffered, false)
    private val fonts = NSPopUpButton(NSMakeRect(140.0, 173.0, 290.0, 28.0), false)
    private val size = NSTextField(NSMakeRect(140.0, 128.0, 80.0, 26.0))
    private val themes = NSPopUpButton(NSMakeRect(140.0, 83.0, 180.0, 28.0), false)
    private val error = NSTextField.labelWithString("")
    private val fontNames: List<String> = NSFontManager.sharedFontManager.availableFonts.filterIsInstance<String>()
        .filter { !it.startsWith('.') && NSFont.fontWithName(it, 14.0)?.fixedPitch == true }.sorted()

    init {
        window.title = "Terminal Settings"; window.setReleasedWhenClosed(false)
        val content = window.contentView!!
        fun label(text: String, y: Double) { content.addSubview(NSTextField.labelWithString(text).apply { setFrame(NSMakeRect(24.0, y, 108.0, 24.0)) }) }
        label("Font", 176.0); label("Size", 131.0); label("Color theme", 86.0)
        fonts.addItemWithTitle("System Monospaced")
        for (name in fontNames) fonts.addItemWithTitle(NSFont.fontWithName(name, 14.0)?.displayName ?: name)
        for (theme in TerminalColorTheme.entries) themes.addItemWithTitle(theme.label)
        fonts.setAccessibilityLabel("Terminal font"); size.setAccessibilityLabel("Font size"); themes.setAccessibilityLabel("Color theme")
        content.addSubview(fonts); content.addSubview(size); content.addSubview(themes)
        error.setFrame(NSMakeRect(24.0, 46.0, 406.0, 22.0)); error.textColor = NSColor.systemRedColor; content.addSubview(error)
        content.addSubview(NSButton(NSMakeRect(342.0, 12.0, 90.0, 28.0)).apply {
            title = "Apply"; bezelStyle = NSBezelStyleRounded; target = this@TerminalSettingsWindow
            action = NSSelectorFromString("applySettings:"); keyEquivalent = "\r"
        })
    }
    fun show() {
        val value = store.settings
        fonts.selectItemAtIndex((fontNames.indexOf(value.fontName) + 1).toLong())
        size.stringValue = value.fontSize.toString(); themes.selectItemAtIndex(value.colorTheme.ordinal.toLong())
        error.stringValue = ""; window.center(); window.makeKeyAndOrderFront(null)
    }
    @ObjCAction fun applySettings(sender: NSObject?) {
        val points = size.stringValue.toDoubleOrNull()
        if (points == null || !points.isFinite() || points !in 6.0..96.0) { error.stringValue = "Choose a font size between 6 and 96."; return }
        val index = fonts.indexOfSelectedItem.toInt() - 1
        store.save(TerminalSettings(fontNames.getOrNull(index), points, TerminalColorTheme.entries[themes.indexOfSelectedItem.toInt()]))
        changed(); error.stringValue = ""; window.orderOut(null)
    }
    fun close() { window.close() }
}
