// DECRQCRA/XTCHECKSUM adapted from Ghostty terminal/xt_checksum.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal fun terminalChecksum(screen: Array<TerminalLine>, left: Int, top: Int, right: Int, bottom: Int, flags: Int): Int {
    val positive = flags and 1 != 0
    val noAttributes = flags and 2 != 0
    val noTrim = flags and 4 != 0
    val undrawn = flags and 8 != 0
    val full = flags and 16 != 0
    var sum = 0
    var first = true
    for (y in top..bottom) {
        val line = screen[y]
        for (x in left..right) {
            val cp = line.codePoint(x)
            var value = when {
                line.width(x) == 0 -> if (full) continue else 0x1b
                !line.hasText(x) -> if (noTrim || undrawn) 32 else continue
                full -> cp and 0xffff
                cp == 0x7f || cp == 0xff -> 0
                cp in 0x20..0x7e || cp in 0x80..0x9f || cp in 0xa1..0xfe -> cp and 0x7f
                else -> 0x1b
            }
            val style = line.style(x)
            if (!noAttributes) {
                if (line.isProtected(x)) value += 4
                if (style and TerminalStyle.HIDDEN != 0) value += 8
                if (style and TerminalStyle.UNDERLINES != 0) value += 16
                if (style and TerminalStyle.INVERSE != 0) value += 32
                if (style and TerminalStyle.BLINK != 0) value += 64
                if (style and TerminalStyle.BOLD != 0) value += 128
            }
            val extended = style and (TerminalStyle.FAINT or TerminalStyle.ITALIC or TerminalStyle.STRIKE or TerminalStyle.DOUBLE_UNDERLINE) != 0
            if (noTrim || first || value != 32 || extended) sum = (sum + value) and 0xffff
            if (noTrim && !full && line.isCombined(x)) {
                val text = line.text(x)
                var i = if (cp > 0xffff) 2 else 1
                while (i < text.length) {
                    val high = text[i++].code
                    val suffix = if (high in 0xd800..0xdbff && i < text.length && text[i] in '\udc00'..'\udfff')
                        0x10000 + ((high - 0xd800) shl 10) + text[i++].code - 0xdc00 else high
                    sum = (sum + suffix) and 0xffff
                }
            }
            first = noTrim
        }
        if (!noTrim) first = false
    }
    return (if (positive) sum else -sum) and 0xffff
}
