// Hover identity and decoration rules follow Ghostty RenderState.linkCells and renderer/generic.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Coordinates are viewport-relative; each range covers one visible row with an exclusive end. */
internal data class TerminalLinkHover(val target: TerminalLinkTarget, val ranges: List<TerminalSelectionRange>) {
    fun contains(column: Int, row: Int): Boolean {
        var low = 0; var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {
                row < range.start.line || row == range.start.line && column < range.start.column -> high = middle - 1
                row > range.start.line || column >= range.end.column -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

internal fun terminalLinkHoverAt(column: Int, row: Int, first: Int, rows: Int, last: Int,
    line: (Int) -> TerminalLine, osc8Enabled: Boolean, detectedEnabled: Boolean = true): TerminalLinkHover? {
    if (row !in 0 until rows || column !in 0 until line(first + row).columns) return null
    val hovered = line(first + row)
    val x = if (hovered.width(column) == 0) (column - 1).coerceAtLeast(0) else column
    val identity = if (osc8Enabled) hovered.hyperlinkIdentity(x) else null
    if (identity != null) {
        val ranges = mutableListOf<TerminalSelectionRange>()
        for (y in 0 until rows) {
            val current = line(first + y)
            var start = -1
            for (col in 0..current.columns) {
                val matches = col < current.columns && identity.sameIdentity(current.hyperlinkIdentity(col))
                if (matches && start < 0) start = col
                if (!matches && start >= 0) {
                    ranges += TerminalSelectionRange(TerminalCellPosition(y, start), TerminalCellPosition(y, col))
                    start = -1
                }
            }
        }
        return TerminalLinkHover(TerminalLinkTarget(identity.value.uri, true), ranges)
    }
    if (!detectedEnabled) return null
    val detected = terminalDetectedLinkAt(first + row, x, 0, last, line) ?: return null
    val ranges = (maxOf(first, detected.range.start.line)..minOf(first + rows - 1, detected.range.end.line)).map { y ->
        TerminalSelectionRange(TerminalCellPosition(y - first, if (y == detected.range.start.line) detected.range.start.column else 0),
            TerminalCellPosition(y - first, if (y == detected.range.end.line) detected.range.end.column else line(y).columns))
    }
    return TerminalLinkHover(TerminalLinkTarget(detected.uri, false), ranges)
}

internal fun terminalLinkHoverStyle(style: Int, hovered: Boolean): Int = if (!hovered) style else
    (style and TerminalStyle.UNDERLINES.inv()) or
        if (style and TerminalStyle.UNDERLINES == TerminalStyle.UNDERLINE) TerminalStyle.DOUBLE_UNDERLINE else TerminalStyle.UNDERLINE

/** Ghostty link-previews modes. Activation and highlighting are independent of preview visibility. */
public enum class TerminalLinkPreviewMode { ALL, OSC8, NONE }

internal fun terminalLinkPreview(target: TerminalLinkTarget?, mode: TerminalLinkPreviewMode): String? {
    if (target == null || mode == TerminalLinkPreviewMode.NONE || mode == TerminalLinkPreviewMode.OSC8 && !target.osc8) return null
    return buildString {
        for (char in target.uri) if (char.code in 0..31 || char.code in 127..159 || char == '\u061c' ||
            char in '\u200b'..'\u200f' || char in '\u2028'..'\u202e' || char in '\u2066'..'\u2069' ||
            char == '\u2060' || char == '\ufeff') append("\\u{${char.code.toString(16).uppercase()}}") else append(char)
    }
}

internal data class TerminalLinkModifiers(val shift: Boolean = false, val control: Boolean = false,
    val alt: Boolean = false, val meta: Boolean = false) {
    fun binding(mac: Boolean, session: TerminalSession): Boolean {
        val effectiveShift = shift && !(session.mouseTracking != 0 && !session.mouseShiftCapture)
        return !effectiveShift && !alt && if (mac) meta && !control else control && !meta
    }
    fun hoverAllowed(mac: Boolean, session: TerminalSession, scrollOffset: Int): Boolean = binding(mac, session) &&
        (session.mouseTracking == 0 || scrollOffset > 0 || shift && !session.mouseShiftCapture)
}
