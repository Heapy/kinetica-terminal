// Bold foreground resolution follows Ghostty terminal/style.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Host policy for bold foregrounds. A null policy leaves cell colors unchanged. */
public sealed interface TerminalBoldColor {
    /** Bold ANSI colors 0..7 use palette entries 8..15. */
    public data object BRIGHT : TerminalBoldColor
    /** Also replace default foregrounds (and equal explicit RGB) with this color. */
    public data class Rgb(public val value: Int) : TerminalBoldColor {
        init { require(value in 0..0xffffff) { "Bold color must be an RGB color" } }
    }
}

internal fun terminalForegroundColor(line: TerminalLine, column: Int, default: Int, bold: TerminalBoldColor?): Int {
    val raw = line.rawForeground(column)
    if (bold != null && line.style(column) and TerminalStyle.BOLD != 0) {
        if (raw >= INDEXED_COLOR && (raw and 255) < 8) {
            val index = (raw and 255) + 8
            return line.colors?.palette(index) ?: terminalPalette(index)
        }
        if (bold is TerminalBoldColor.Rgb && (raw < 0 || raw == default)) return bold.value
    }
    return line.foreground(column).let { if (it < 0) default else it }
}
