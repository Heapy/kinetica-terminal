// Local mouse actions follow Ghostty Surface.zig and config/Config.zig (MIT).
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

public enum class TerminalRightClickAction { CONTEXT_MENU, PASTE, COPY, COPY_OR_PASTE, IGNORE }
public enum class TerminalMiddleClickAction { PRIMARY_PASTE, CLIPBOARD_PASTE, IGNORE }

/** Called once for a local press, after application mouse reporting has declined it.
 * False lets the host open its context menu. Copy always uses mixed format. */
internal fun terminalMouseClick(
    button: Int,
    right: TerminalRightClickAction,
    middle: TerminalMiddleClickAction,
    selectionClipboardSupported: Boolean,
    hasSelection: Boolean,
    copy: () -> Unit,
    clear: () -> Unit,
    paste: (TerminalClipboardLocation) -> Unit,
): Boolean {
    when (button) {
        1 -> when (middle) {
            TerminalMiddleClickAction.PRIMARY_PASTE -> if (selectionClipboardSupported) paste(TerminalClipboardLocation.SELECTION)
            TerminalMiddleClickAction.CLIPBOARD_PASTE -> paste(TerminalClipboardLocation.STANDARD)
            TerminalMiddleClickAction.IGNORE -> Unit
        }
        2 -> when (right) {
            TerminalRightClickAction.CONTEXT_MENU -> return false
            TerminalRightClickAction.COPY -> { if (hasSelection) copy(); clear() }
            TerminalRightClickAction.COPY_OR_PASTE -> if (hasSelection) { copy(); clear() } else paste(TerminalClipboardLocation.STANDARD)
            TerminalRightClickAction.PASTE -> { clear(); paste(TerminalClipboardLocation.STANDARD) }
            TerminalRightClickAction.IGNORE -> Unit
        }
        else -> return false
    }
    return true
}

/** Host text reads are bounded and never redirect an unsupported location. */
internal fun TerminalMimeClipboard.readPasteText(location: TerminalClipboardLocation, complete: (String?) -> Unit) {
    read(location, listOf("text/plain"), list = false) { result ->
        val bytes = if (result.result == TerminalClipboardResult.SUCCESS)
            result.contents.firstOrNull { it.mime == "text/plain" }?.data else null
        complete(if (bytes == null || bytes.size > 8 * 1024 * 1024) null else
            try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null })
    }
}
