@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreText.*

/** Mirrors Ghostty's CoreText sbix/SVG classification without retaining font table data. */
internal class TerminalNativeColorGlyphs private constructor(private val sbix: Boolean, private val svg: TerminalSvgGlyphs) {
    operator fun contains(glyph: Int): Boolean = glyph in 0..65535 && (sbix || glyph in svg)

    companion object {
        private val EMPTY = TerminalNativeColorGlyphs(false, TerminalSvgGlyphs.EMPTY)
        fun read(font: CTFontRef): TerminalNativeColorGlyphs {
            if (CTFontGetSymbolicTraits(font) and kCTFontColorGlyphsTrait == 0u) return EMPTY
            val sbix = CTFontCopyTable(font, 0x73626978u, 0u)
            val hasSbix = if (sbix == null) false else try { CFDataGetLength(sbix) > 0 } finally { CFRelease(sbix) }
            if (hasSbix) return TerminalNativeColorGlyphs(true, TerminalSvgGlyphs.EMPTY)
            val table = CTFontCopyTable(font, 0x53564720u, 0u) ?: return EMPTY
            val svg = try {
                val length = CFDataGetLength(table)
                val bytes = CFDataGetBytePtr(table)
                if (length !in 10..Int.MAX_VALUE.toLong() || bytes == null) TerminalSvgGlyphs.EMPTY
                else TerminalSvgGlyphs.read(length.toInt()) { bytes[it].toInt() }
            } finally { CFRelease(table) }
            return TerminalNativeColorGlyphs(false, svg)
        }
    }
}
