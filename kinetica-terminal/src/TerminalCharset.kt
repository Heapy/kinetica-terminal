// Charset state and mappings adapted from Ghostty charsets.zig / Screen.zig (MIT).
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal enum class TerminalCharset {
    UTF8, ASCII, BRITISH, DEC_SPECIAL;

    fun map(code: Int): Int = when {
        this == BRITISH && code == '#'.code -> '£'.code
        this == DEC_SPECIAL && code in 0x60..0x7e -> DEC_GRAPHICS[code - 0x60].code
        else -> code
    }

    private companion object {
        const val DEC_GRAPHICS = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}

/** Immutable so saved cursors retain independent designations and pending single shifts. */
internal data class TerminalCharsetState(
    val g0: TerminalCharset = TerminalCharset.UTF8,
    val g1: TerminalCharset = TerminalCharset.UTF8,
    val g2: TerminalCharset = TerminalCharset.UTF8,
    val g3: TerminalCharset = TerminalCharset.UTF8,
    val gl: Int = 0,
    // Ghostty retains GR invocation state but does not remap GR in UTF-8 mode.
    val gr: Int = 2,
    val singleShift: Int? = null,
) {
    val active: TerminalCharset get() = when (singleShift ?: gl) {
        0 -> g0
        1 -> g1
        2 -> g2
        else -> g3
    }

    fun designate(slot: Int, set: TerminalCharset): TerminalCharsetState = when (slot) {
        0 -> copy(g0 = set)
        1 -> copy(g1 = set)
        2 -> copy(g2 = set)
        else -> copy(g3 = set)
    }
}
