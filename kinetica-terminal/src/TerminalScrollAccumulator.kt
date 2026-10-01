package io.heapy.kinetica.terminal

/** Precise devices report pixels; conventional wheels report line-sized ticks. */
internal class TerminalScrollAccumulator {
    private var remainder = 0.0
    fun consume(delta: Double, precise: Boolean, cellSize: Double, linesPerTick: Double = 3.0): Int {
        if (!delta.isFinite() || !cellSize.isFinite() || cellSize <= 0) return 0
        if (delta * remainder < 0) remainder = 0.0
        remainder += if (precise) delta / cellSize else delta * linesPerTick
        val lines = remainder.toInt().coerceIn(-1000, 1000)
        remainder -= lines
        return lines
    }
    fun reset() { remainder = 0.0 }
}
