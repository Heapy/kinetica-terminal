@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*

internal fun nativePasteAlert(contents: String): NSAlert = NSAlert().apply {
    alertStyle = NSAlertStyleWarning
    messageText = "Paste into Terminal?"
    informativeText = "This text may execute commands when pasted into the terminal."
    addButtonWithTitle("Cancel"); addButtonWithTitle("Paste")
    val scroll = NSScrollView(NSMakeRect(0.0, 0.0, 480.0, 160.0))
    scroll.borderType = NSBezelBorder; scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true
    val preview = NSTextView(scroll.contentView().bounds)
    preview.editable = false; preview.selectable = true; preview.richText = false
    preview.font = NSFont.monospacedSystemFontOfSize(NSFont.systemFontSize, NSFontWeightRegular)
    preview.string = contents; preview.textContainerInset = NSMakeSize(6.0, 6.0)
    preview.textContainer?.widthTracksTextView = true
    preview.textContainer?.containerSize = NSMakeSize(480.0, Double.MAX_VALUE)
    scroll.documentView = preview; accessoryView = scroll
}

internal fun presentNativePaste(owner: NSWindow?, text: String, complete: (Boolean) -> Unit): TerminalDisposable {
    if (owner == null) { complete(false); return TerminalDisposable {} }
    val alert = nativePasteAlert(text)
    var active = true
    alert.beginSheetModalForWindow(owner) { response ->
        if (active) { active = false; complete(response == NSAlertSecondButtonReturn) }
    }
    return TerminalDisposable {
        if (active) { active = false; owner.endSheet(alert.window, NSModalResponseCancel); alert.window.orderOut(null) }
    }
}
