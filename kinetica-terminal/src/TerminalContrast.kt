// Contrast correction follows Ghostty renderer/shaders/glsl/common.glsl and cell.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.math.pow

private val terminalLinearChannels = DoubleArray(256) {
    val value = it / 255.0
    if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

private fun terminalLuminance(rgb: Int, opacity: Double = 1.0): Double =
    terminalLinearChannels[rgb shr 16 and 255] * opacity * 0.2126 +
        terminalLinearChannels[rgb shr 8 and 255] * opacity * 0.7152 +
        terminalLinearChannels[rgb and 255] * opacity * 0.0722

/** Null preserves the original color/alpha. A replacement is always fully opaque, like
 * Ghostty's shader. Faint alpha premultiplies linear foreground channels before comparison. */
internal fun terminalContrastCorrection(minimum: Double, foreground: Int, background: Int, opacity: Double): Int? {
    if (minimum <= 1.0) return null
    val fg = terminalLuminance(foreground, opacity) + 0.05
    val bg = terminalLuminance(background) + 0.05
    if (maxOf(fg, bg) / minOf(fg, bg) >= minimum) return null
    return if (1.05 / bg > bg / 0.05) 0xffffff else 0
}

/** Original noMinContrast/isGraphicsElement ranges, including legacy-computing supplements. */
internal fun terminalContrastExcluded(codepoint: Int): Boolean = codepoint in 0x2500..0x259f ||
    codepoint in 0xe0b0..0xe0d7 || codepoint in 0x1fb00..0x1fbff || codepoint in 0x1cc00..0x1cebf

internal fun terminalFirstCodepoint(text: String): Int {
    if (text.isEmpty()) return 0
    val first = text[0]
    return if (first.isHighSurrogate() && text.length > 1 && text[1].isLowSurrogate())
        0x10000 + ((first.code - 0xd800) shl 10) + text[1].code - 0xdc00 else first.code
}

internal fun terminalGlyphContrast(frame: TerminalGpuFrame, row: TerminalDrawRow, glyph: TerminalGlyph, colored: Boolean): Int? {
    if (colored || glyph.contrastExcluded || frame.minimumContrast <= 1.0) return null
    val cursor = frame.contrastCursor
    if (cursor != null && glyph.x == cursor.column * frame.cellWidth && glyph.y == cursor.line * frame.cellHeight) return null
    // Background runs are ordered and cover the row. Use cell colors, never image pixels.
    var low = 0; var high = row.backgrounds.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (row.backgrounds[mid].x <= glyph.x) low = mid else high = mid - 1
    }
    val background = row.backgrounds.getOrNull(low)?.color ?: frame.defaultBackground
    return terminalContrastCorrection(frame.minimumContrast, glyph.color, background, glyph.opacity)
}
