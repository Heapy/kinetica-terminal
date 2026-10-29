package io.heapy.kinetica.terminal

import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString

internal class TerminalNativeClipboard(private val pasteboard: NSPasteboard = NSPasteboard.generalPasteboard) : TerminalClipboard {
    override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) {
        complete(if (location == TerminalClipboardLocation.STANDARD) pasteboard.stringForType(NSPasteboardTypeString)?.encodeToByteArray() else null)
    }

    override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) {
        if (location == TerminalClipboardLocation.STANDARD) {
            val text = try { data.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { complete(); return }
            pasteboard.clearContents()
            if (data.isNotEmpty()) pasteboard.setString(text, NSPasteboardTypeString)
        }
        complete()
    }
}
