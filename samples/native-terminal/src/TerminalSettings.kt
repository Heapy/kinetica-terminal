@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.TerminalTheme
import io.heapy.kinetica.*
import io.heapy.kinetica.appkit.*
import io.heapy.kinetica.application.WindowBounds
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
    fun saveBounds(bounds: WindowBounds) {
        savedFrame = NSStringFromRect(NSMakeRect(bounds.x, bounds.y, bounds.width, bounds.height))
        defaults?.setObject(savedFrame, forKey = "terminal.windowFrame")
    }
    val bounds: WindowBounds? get() = savedFrame?.let(::NSRectFromString)?.useContents {
        if (origin.x.isFinite() && origin.y.isFinite() && size.width.isFinite() && size.height.isFinite() && size.width > 0 && size.height > 0)
            WindowBounds(origin.x, origin.y, size.width, size.height) else null
    }
}

internal class TerminalSettingsEditor(private val store: TerminalSettingsStore, private val applied: () -> Unit) {
    val font = store(store.settings.fontName.orEmpty())
    val size = store(store.settings.fontSize.toString())
    val theme = store(store.settings.colorTheme.name)
    val error = store("")
    val fonts: List<ChoiceOption> = listOf(ChoiceOption("", "System Monospaced")) +
        NSFontManager.sharedFontManager.availableFonts.filterIsInstance<String>()
            .filter { !it.startsWith('.') && NSFont.fontWithName(it, 14.0)?.fixedPitch == true || it == store.settings.fontName }
            .sorted().map { ChoiceOption(it, NSFont.fontWithName(it, 14.0)?.displayName ?: it) }
    fun apply() {
        val points = size.value.toDoubleOrNull()
        if (points == null || !points.isFinite() || points !in 6.0..96.0) {
            error.value = "Choose a font size between 6 and 96."
            return
        }
        store.save(TerminalSettings(font.value.takeIf { it.isNotEmpty() }, points, TerminalColorTheme.valueOf(theme.value)))
        error.value = ""
        applied()
    }
}

@UiComponent
internal fun ComponentScope.TerminalSettingsContent(editor: TerminalSettingsEditor) {
    host("column", props = mapOf("padding" to "20", "spacing" to "12")) {
        row {
            host("appkit:label", props = mapOf("value" to "Font", "width" to "90"))
            appKitChoice(editor.font.value, editor.fonts, onChange = { editor.font.value = it },
                semantics = Semantics(label = "Terminal font", testTag = "font-family"), width = 320.0)
            host("appkit:spacer")
        }
        row {
            host("appkit:label", props = mapOf("value" to "Size", "width" to "90"))
            textInput(editor.size.value, onInput = { editor.size.value = it }, onSubmit = editor::apply,
                semantics = Semantics(label = "Font size", testTag = "font-size"))
        }
        row {
            host("appkit:label", props = mapOf("value" to "Color theme", "width" to "90"))
            appKitChoice(editor.theme.value, TerminalColorTheme.entries.map { ChoiceOption(it.name, it.label) },
                onChange = { editor.theme.value = it }, semantics = Semantics(label = "Color theme", testTag = "theme"), width = 320.0)
            host("appkit:spacer")
        }
        text(editor.error.value)
        row {
            host("appkit:spacer")
            button(onClick = editor::apply, semantics = Semantics(label = "Apply settings", testTag = "apply")) { text("Apply") }
        }
    }
}
