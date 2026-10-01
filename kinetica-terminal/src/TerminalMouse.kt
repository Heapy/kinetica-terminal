package io.heapy.kinetica.terminal

/**
 * SGR or legacy mouse protocol, in zero-based cells. Buttons: 0/1/2, no button: 3,
 * wheel up/down/left/right: 64..67, back/forward: 128/129. Shift bypasses reporting.
 * True means application mouse handling owns the event, including suppressed reports.
 */
public fun TerminalSession.sendMouse(column: Int, row: Int, button: Int, release: Boolean = false,
    motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false): Boolean {
    if (shift || !handlesMouse(button, release, motion)) return false
    encodeMouse(column, row, button, release, motion, alt, control)?.let(::sendProtocolBytes)
    return true
}

internal fun TerminalSession.handlesMouse(button: Int, release: Boolean, motion: Boolean): Boolean =
    mouseTracking != 0 && (button in 0..2 || button in 64..67 || button in 128..129 || button == 3 && motion) &&
        !(release && motion) && !(button in 64..67 && (release || motion)) &&
        !(motion && (mouseTracking == 1000 || mouseTracking == 1002 && button == 3))

internal fun TerminalSession.encodeMouse(column: Int, row: Int, button: Int, release: Boolean = false,
    motion: Boolean = false, alt: Boolean = false, control: Boolean = false): ByteArray? {
    if (!handlesMouse(button, release, motion)) return null
    // Release outside the view must reach the TUI. Dragging outside is useful in motion modes.
    if (!release && (column !in 0 until columns || row !in 0 until rows) &&
        (mouseTracking == 1000 || button == 3)) return null
    val x = column.coerceIn(0, columns - 1) + 1
    val y = row.coerceIn(0, rows - 1) + 1
    val code = (if (release && !sgrMouse) 3 else button) +
        (if (motion) 32 else 0) + (if (alt) 8 else 0) + (if (control) 16 else 0)
    return if (sgrMouse) "\u001b[<$code;$x;$y${if (release) 'm' else 'M'}".encodeToByteArray()
    else if (x <= 223 && y <= 223) byteArrayOf(27, 91, 77, (code + 32).toByte(), (x + 32).toByte(), (y + 32).toByte())
    else null
}

/** Per-surface state: sessions can have more than one independently positioned mouse. */
internal class TerminalMouseReporter(private val session: TerminalSession) {
    private var lastCell: Pair<Int, Int>? = null
    private var lastPosition: Pair<Int, Int>? = null
    private var epoch = -1
    private var size = 0 to 0
    private val pressed = mutableSetOf<Int>()

    fun send(column: Int, row: Int, button: Int, release: Boolean = false,
        motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false): Boolean {
        if (epoch != session.mouseEpoch) { reset(); epoch = session.mouseEpoch }
        if (size != (session.columns to session.rows)) {
            lastCell = null
            size = session.columns to session.rows
        }
        if (release && !pressed.remove(button)) return false
        if (shift && !release || !session.handlesMouse(button, release, motion)) { lastCell = null; return false }
        val cell = column.coerceIn(0, session.columns - 1) to row.coerceIn(0, session.rows - 1)
        if (motion && lastCell == cell) return true
        val bytes = session.encodeMouse(column, row, button, release, motion, alt, control) ?: return true
        if (!release && !motion && button !in 64..67) pressed.add(button)
        lastCell = cell
        lastPosition = cell
        session.sendProtocolBytes(bytes)
        return true
    }

    fun cancel() {
        val cell = lastPosition
        val buttons = pressed.toList()
        reset()
        if (cell != null && epoch == session.mouseEpoch) buttons.forEach { session.sendMouse(cell.first, cell.second, it, release = true) }
    }

    fun reset() { lastCell = null; lastPosition = null; pressed.clear() }
}
