@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*

/** Measurement, software text, IME and the Metal atlas resolve exactly the same face. */
internal fun terminalNativeFont(family: String?, size: Double, style: Int = 0): NSFont {
    var font = family?.takeIf { it.isNotBlank() }?.let { NSFont.fontWithName(it, size) }
        ?: NSFont.monospacedSystemFontOfSize(size, NSFontWeightRegular)
    if (style and TerminalStyle.BOLD != 0) font = NSFontManager.sharedFontManager.convertFont(font, toHaveTrait = NSBoldFontMask)
    if (style and TerminalStyle.ITALIC != 0) font = NSFontManager.sharedFontManager.convertFont(font, toHaveTrait = NSItalicFontMask)
    return font
}
