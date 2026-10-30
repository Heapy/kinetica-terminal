// Repeat-click rules follow Ghostty terminal/SelectionGesture.zig (MIT).
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.math.hypot

/** Timing is relative to the last press; distance is relative to the first press. */
internal class TerminalClickSequence {
    private var count = 0
    private var time = Double.NaN
    var x = 0.0
        private set
    private var y = 0.0

    fun press(now: Double, x: Double, y: Double, interval: Double, distance: Double, originValid: Boolean): Int {
        val elapsed = now - time
        val repeat = count > 0 && originValid && elapsed.isFinite() && elapsed >= 0 && elapsed <= interval &&
            hypot(x - this.x, y - this.y) <= distance
        if (repeat) count = (count + 1).coerceAtMost(3) else {
            count = 1; this.x = x; this.y = y
        }
        time = now
        return count
    }

    fun reset() { count = 0; time = Double.NaN }
}
