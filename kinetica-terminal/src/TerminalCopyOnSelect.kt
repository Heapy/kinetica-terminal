// Selection-copy policy follows Ghostty Surface.zig and config/Config.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Ghostty's copy-on-select destinations. PRIMARY is the system primary selection;
 * the built-in macOS and browser clipboards do not support it and never redirect it.
 * Automatic copies always use MIXED, independently of the explicit copy format. */
public enum class TerminalCopyOnSelect(internal val locations: List<TerminalClipboardLocation>) {
    NONE(emptyList()),
    PRIMARY(listOf(TerminalClipboardLocation.PRIMARY)),
    CLIPBOARD(listOf(TerminalClipboardLocation.STANDARD)),
    BOTH(listOf(TerminalClipboardLocation.STANDARD, TerminalClipboardLocation.PRIMARY)),
}
