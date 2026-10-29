// Mouse protocol semantics adapted from Ghostty input/mouse_encode.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.math.ceil
import kotlin.math.floor

public enum class TerminalMouseFormat { X10, UTF8, SGR, URXVT, SGR_PIXELS }

/** Physical-pixel viewport and cell dimensions, excluding any host padding. */
public data class TerminalMouseGeometry(val width: Double, val height: Double, val cellWidth: Double, val cellHeight: Double) {
    init {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0 &&
            cellWidth.isFinite() && cellWidth > 0 && cellHeight.isFinite() && cellHeight > 0)
    }

    internal fun position(x: Double, y: Double): TerminalMousePosition? {
        // Float arithmetic is still f64 on Kotlin/JS. The bit roundtrip enforces the
        // original encoder's f32 input precision on every platform.
        val px = Float.fromBits(x.toFloat().toBits()).toDouble()
        val py = Float.fromBits(y.toFloat().toBits()).toDouble()
        if (!px.isFinite() || !py.isFinite()) return null
        val columns = floor(width / cellWidth).toInt().coerceIn(1, 4096)
        val rows = floor(height / cellHeight).toInt().coerceIn(1, 4096)
        fun rounded(value: Double) = (if (value < 0) ceil(value - 0.5) else floor(value + 0.5)).toInt()
        return TerminalMousePosition(floor(px / cellWidth).toInt().coerceIn(0, columns - 1),
            floor(py / cellHeight).toInt().coerceIn(0, rows - 1), rounded(px), rounded(py),
            px >= 0 && py >= 0 && px <= width && py <= height)
    }
}

internal data class TerminalMousePosition(val column: Int, val row: Int, val pixelX: Int?, val pixelY: Int?, val inside: Boolean)
private fun TerminalSession.cellPosition(column: Int, row: Int) = TerminalMousePosition(
    column.coerceIn(0, columns - 1), row.coerceIn(0, rows - 1), null, null,
    column in 0 until columns && row in 0 until rows)

/**
 * Cell-coordinate mouse protocols. Pixel mode requires [sendMousePixels] and emits no guessed
 * coordinates through this API. Buttons: 0/1/2, no button: 3,
 * wheel up/down/left/right: 64..67, back/forward: 128/129. Shift bypasses reporting.
 * True means application mouse handling owns the event, including suppressed reports.
 */
public fun TerminalSession.sendMouse(column: Int, row: Int, button: Int, release: Boolean = false,
    motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false): Boolean {
    if (shift || !handlesMouse(button, release, motion)) return false
    encodeMouse(column, row, button, release, motion, alt, control)?.let(::sendProtocolBytes)
    return true
}

/** Physical-pixel input, relative to the terminal's top-left; outside positions are retained. */
public fun TerminalSession.sendMousePixels(x: Double, y: Double, geometry: TerminalMouseGeometry,
    button: Int, release: Boolean = false, motion: Boolean = false, shift: Boolean = false,
    alt: Boolean = false, control: Boolean = false, anyButtonPressed: Boolean = button != 3 && !release): Boolean {
    if (shift || !handlesMouse(button, release, motion)) return false
    val position = geometry.position(x, y) ?: return false
    encodeMouse(position, button, release, motion, alt, control, anyButtonPressed)?.let(::sendProtocolBytes)
    return true
}

internal fun TerminalSession.handlesMouse(button: Int, release: Boolean, motion: Boolean): Boolean =
    mouseTracking != 0 && (button in 0..2 || button in 64..67 || button in 128..129 || button == 3 && motion) &&
        !(release && motion) && !(button in 64..67 && (release || motion)) &&
        !(mouseTracking == 9 && (release || motion || button !in 0..2)) &&
        !(motion && (mouseTracking == 1000 || mouseTracking == 1002 && button == 3))

internal fun TerminalSession.encodeMouse(column: Int, row: Int, button: Int, release: Boolean = false,
    motion: Boolean = false, alt: Boolean = false, control: Boolean = false): ByteArray? =
    encodeMouse(cellPosition(column, row), button, release, motion, alt, control, button != 3 && !release)

private fun TerminalSession.positionAllowed(position: TerminalMousePosition, release: Boolean, pressed: Boolean): Boolean =
    release || position.inside || (mouseTracking == 1002 || mouseTracking == 1003) && pressed

private fun TerminalSession.encodeMouse(position: TerminalMousePosition, button: Int, release: Boolean,
    motion: Boolean, alt: Boolean, control: Boolean, anyButtonPressed: Boolean): ByteArray? {
    if (!handlesMouse(button, release, motion)) return null
    // Release outside the view must reach the TUI. Dragging outside is useful in motion modes.
    if (!positionAllowed(position, release, anyButtonPressed)) return null
    val x = position.column + 1
    val y = position.row + 1
    val sgr = mouseFormat == TerminalMouseFormat.SGR || mouseFormat == TerminalMouseFormat.SGR_PIXELS
    val code = (if (release && !sgr) 3 else button) +
        (if (motion) 32 else 0) + (if (alt && mouseTracking != 9) 8 else 0) +
        (if (control && mouseTracking != 9) 16 else 0)
    return when (mouseFormat) {
        TerminalMouseFormat.X10 -> if (x <= 223 && y <= 223)
            byteArrayOf(27, 91, 77, (code + 32).toByte(), (x + 32).toByte(), (y + 32).toByte()) else null
        TerminalMouseFormat.UTF8 -> ("\u001b[M" + codePointString(code + 32) + codePointString(x + 32) + codePointString(y + 32)).encodeToByteArray()
        TerminalMouseFormat.URXVT -> "\u001b[${code + 32};$x;${y}M".encodeToByteArray()
        TerminalMouseFormat.SGR -> "\u001b[<$code;$x;$y${if (release) 'm' else 'M'}".encodeToByteArray()
        TerminalMouseFormat.SGR_PIXELS -> if (position.pixelX == null || position.pixelY == null) null
            else "\u001b[<$code;${position.pixelX};${position.pixelY}${if (release) 'm' else 'M'}".encodeToByteArray()
    }
}

/** Per-surface state: sessions can have more than one independently positioned mouse. */
internal class TerminalMouseReporter(private val session: TerminalSession) {
    private var lastCell: Pair<Int, Int>? = null
    private var lastPosition: TerminalMousePosition? = null
    private var geometry: TerminalMouseGeometry? = null
    private var epoch = -1
    private var size = 0 to 0
    private val pressed = mutableSetOf<Int>()

    fun send(column: Int, row: Int, button: Int, release: Boolean = false,
        motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false): Boolean =
        send(session.cellPosition(column, row), button, release, motion, shift, alt, control)

    fun sendPixels(x: Double, y: Double, geometry: TerminalMouseGeometry, button: Int, release: Boolean = false,
        motion: Boolean = false, shift: Boolean = false, alt: Boolean = false, control: Boolean = false): Boolean {
        if (this.geometry != geometry) { lastCell = null; this.geometry = geometry }
        return send(geometry.position(x, y) ?: return false, button, release, motion, shift, alt, control)
    }

    private fun send(position: TerminalMousePosition, button: Int, release: Boolean,
        motion: Boolean, shift: Boolean, alt: Boolean, control: Boolean): Boolean {
        if (epoch != session.mouseEpoch) { reset(); epoch = session.mouseEpoch }
        if (size != (session.columns to session.rows)) {
            lastCell = null
            size = session.columns to session.rows
        }
        if (release && !pressed.remove(button)) return false
        if (shift && !release || !session.handlesMouse(button, release, motion)) { lastCell = null; return false }
        val anyPressed = pressed.isNotEmpty() || !release && button != 3 && button !in 64..67
        if (!session.positionAllowed(position, release, anyPressed)) return true
        val cell = position.column to position.row
        if (motion && session.mouseFormat != TerminalMouseFormat.SGR_PIXELS && lastCell == cell) return true
        lastCell = cell
        val bytes = session.encodeMouse(position, button, release, motion, alt, control, anyPressed) ?: return true
        if (!release && !motion && button !in 64..67) pressed.add(button)
        lastPosition = position
        session.sendProtocolBytes(bytes)
        return true
    }

    fun cancel() {
        val cell = lastPosition
        val buttons = pressed.toList()
        reset()
        if (cell != null && epoch == session.mouseEpoch) buttons.forEach {
            session.encodeMouse(cell, it, release = true, motion = false, alt = false, control = false, anyButtonPressed = false)
                ?.let(session::sendProtocolBytes)
        }
    }

    fun reset() { lastCell = null; lastPosition = null; pressed.clear() }
}
