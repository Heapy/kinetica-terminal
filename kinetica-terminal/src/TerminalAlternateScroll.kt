package io.heapy.kinetica.terminal

/** Positive lines scroll up. Application mouse reporting and local Shift gestures take precedence. */
internal fun TerminalSession.sendAlternateScroll(lines: Int, shift: Boolean = false): Boolean {
    if (lines == 0 || !alternateScreen || !alternateScroll || mouseTracking != 0 || shift) return false
    val key = TerminalKey(if (lines > 0) "ArrowUp" else "ArrowDown")
    // Bound input from a single host event, including extreme synthetic deltas.
    repeat(kotlin.math.abs(lines.coerceIn(-64, 64))) { sendKey(key) }
    return true
}
