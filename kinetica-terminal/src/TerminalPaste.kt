// Paste encoding adapted from Ghostty input/paste.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal fun terminalEncodePaste(text: String, bracketed: Boolean): String = buildString(text.length) {
    if (bracketed) append("\u001b[200~")
    for (char in text) append(when (char.code) {
        // xterm's fixed control-byte replacement set, also used by Ghostty for DnD.
        0x00, 0x08, 0x05, 0x04, 0x1b, 0x7f,
        0x03, 0x1c, 0x15, 0x1a, 0x11, 0x13, 0x17, 0x16, 0x12, 0x0f -> ' '
        0x0a -> if (bracketed) '\n' else '\r'
        else -> char
    })
    if (bracketed) append("\u001b[201~")
}
