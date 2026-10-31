// Selection color resolution follows Ghostty renderer/generic.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** A literal or the effective cell color, after bold-color and SGR inverse. */
public sealed interface TerminalSelectionColor {
    public data object CELL_FOREGROUND : TerminalSelectionColor
    public data object CELL_BACKGROUND : TerminalSelectionColor
    public data class Rgb(public val value: Int) : TerminalSelectionColor {
        init { require(value in 0..0xffffff) { "Selection color must be an RGB color" } }
    }
}

/** Ghostty selection defaults: null foreground uses the terminal background, and null
 * background uses the terminal foreground. Set [TerminalSession.selectionColors] to null
 * to use the host theme's existing selection style instead. */
public data class TerminalSelectionColors(
    public val foreground: TerminalSelectionColor? = null,
    public val background: TerminalSelectionColor? = null,
)

private fun selectionColor(value: TerminalSelectionColor?, foreground: Int, background: Int, default: Int): Int = when (value) {
    null -> default
    TerminalSelectionColor.CELL_FOREGROUND -> foreground
    TerminalSelectionColor.CELL_BACKGROUND -> background
    is TerminalSelectionColor.Rgb -> value.value
}

internal fun terminalSelectionForeground(colors: TerminalSelectionColors?, theme: TerminalTheme, foreground: Int, background: Int): Int =
    if (colors == null) foreground else selectionColor(colors.foreground, foreground, background, theme.background)

internal fun terminalSelectionBackground(colors: TerminalSelectionColors?, theme: TerminalTheme, foreground: Int, background: Int): Int =
    if (colors == null) theme.selection else selectionColor(colors.background, foreground, background, theme.foreground)
