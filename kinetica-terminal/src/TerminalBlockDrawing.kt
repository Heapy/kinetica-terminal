package io.heapy.kinetica.terminal

/** Solid Unicode Block Elements use the cell grid, never font bearings or line spacing. */
internal fun isTerminalBlockElement(codePoint: Int): Boolean =
    codePoint in 0x2580..0x2590 || codePoint in 0x2594..0x259f

internal fun TerminalPainter.blockElement(codePoint: Int, x: Double, y: Double,
    width: Double, height: Double, color: Int) {
    fun rectangle(left: Int, top: Int, right: Int, bottom: Int) {
        fill(x + width * left / 8, y + height * top / 8,
            width * (right - left) / 8, height * (bottom - top) / 8, color)
    }
    when (codePoint) {
        0x2580 -> rectangle(0, 0, 8, 4) // Upper half.
        in 0x2581..0x2588 -> rectangle(0, 8 - (codePoint - 0x2580), 8, 8) // Lower eighths, through full block.
        in 0x2589..0x258f -> rectangle(0, 0, 0x2590 - codePoint, 8) // Left seven eighths, through one eighth.
        0x2590 -> rectangle(4, 0, 8, 8) // Right half.
        0x2594 -> rectangle(0, 0, 8, 1) // Upper eighth.
        0x2595 -> rectangle(7, 0, 8, 8) // Right eighth.
        else -> {
            // Bits: top-left, top-right, bottom-left, bottom-right.
            val quadrants = when (codePoint) {
                0x2596 -> 4
                0x2597 -> 8
                0x2598 -> 1
                0x2599 -> 13
                0x259a -> 9
                0x259b -> 7
                0x259c -> 11
                0x259d -> 2
                0x259e -> 6
                0x259f -> 14
                else -> error("Not a solid block element: $codePoint")
            }
            for (quadrant in 0..3) if (quadrants and (1 shl quadrant) != 0) {
                val left = quadrant % 2 * 4
                val top = quadrant / 2 * 4
                rectangle(left, top, left + 4, top + 4)
            }
        }
    }
}
