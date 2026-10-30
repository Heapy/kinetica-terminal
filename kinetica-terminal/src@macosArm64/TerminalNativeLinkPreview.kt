@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.CValue
import platform.AppKit.*
import platform.Foundation.*

/** A status overlay that never intercepts terminal pointer events. */
internal class TerminalNativeLinkPreview : NSTextField(NSMakeRect(0.0, 0.0, 0.0, 20.0)) {
    init {
        editable = false; selectable = false; bordered = false; bezeled = false
        drawsBackground = true; hidden = true
        font = NSFont.systemFontOfSize(12.0)
        (cell as? NSTextFieldCell)?.lineBreakMode = NSLineBreakByTruncatingMiddle
        setAccessibilityLabel("Link destination")
    }
    override fun acceptsFirstResponder(): Boolean = false
    override fun hitTest(point: CValue<NSPoint>): NSView? = null
}
