// Grapheme rules adapted from uucode/src/grapheme.zig (MIT, Copyright 2026 Jacob Sandlund).
// Width effects adapted from Ghostty/src/unicode/grapheme.zig (MIT, Ghostty contributors).
// See third-party/UUCODE-MIT.txt, GHOSTTY-LICENSE and test-upstream/unicode.
package io.heapy.kinetica.terminal

internal object GraphemeClass {
    const val OTHER = 0
    const val CONTROL = 1
    const val PREPEND = 2
    const val CR = 3
    const val LF = 4
    const val RI = 5
    const val SPACING = 6
    const val L = 7
    const val V = 8
    const val T = 9
    const val LV = 10
    const val LVT = 11
    const val ZWJ = 12
    const val ZWNJ = 13
    const val PICTOGRAPHIC = 14
    const val MODIFIER_BASE = 15
    const val MODIFIER = 16
    const val INDIC_EXTEND = 17
    const val LINKER_EXTEND = 18
    const val LINKER_OTHER = 19
    const val CONSONANT = 20
}

internal fun graphemeClass(code: Int): Int = TerminalUnicodeData.properties(code) ushr 4

/** Bit zero is the boundary; remaining bits are the next state. Initial state is zero. */
internal fun graphemeStep(previous: Int, next: Int, state: Int, terminalTailoring: Boolean = true): Int {
    val a = graphemeClass(previous)
    val b = graphemeClass(next)
    var base = state and 6 // 0 = default, 2 = pictographic sequence, 4 = odd RI pair
    fun indicExtend(value: Int) = value == GraphemeClass.INDIC_EXTEND || value == GraphemeClass.ZWJ
    fun linker(value: Int) = value == GraphemeClass.LINKER_EXTEND || value == GraphemeClass.LINKER_OTHER
    fun extend(value: Int) = value == GraphemeClass.ZWNJ || value == GraphemeClass.INDIC_EXTEND ||
        value == GraphemeClass.LINKER_EXTEND || !terminalTailoring && value == GraphemeClass.MODIFIER
    fun pictographic(value: Int) = value == GraphemeClass.PICTOGRAPHIC || value == GraphemeClass.MODIFIER_BASE
    fun inEmoji(value: Int) = extend(value) || value == GraphemeClass.ZWJ || pictographic(value) || value == GraphemeClass.MODIFIER
    val afterLinker = linker(a) || state and 1 != 0 && indicExtend(a)
    val linkerState = if (afterLinker && indicExtend(b)) 1 else 0
    if (base == 4 && (a != GraphemeClass.RI || b != GraphemeClass.RI)) base = 0
    if (base == 2 && (!inEmoji(a) || !inEmoji(b))) base = 0
    fun result(boundary: Boolean): Int = ((base or linkerState) shl 1) or if (boundary) 1 else 0

    // UAX #29 GB3–GB8.
    if (a == GraphemeClass.CR && b == GraphemeClass.LF) return result(false)
    if (a == GraphemeClass.CONTROL || a == GraphemeClass.CR || a == GraphemeClass.LF ||
        b == GraphemeClass.CONTROL || b == GraphemeClass.CR || b == GraphemeClass.LF) return result(true)
    if (a == GraphemeClass.L && (b == GraphemeClass.L || b == GraphemeClass.V || b == GraphemeClass.LV || b == GraphemeClass.LVT)) return result(false)
    if ((a == GraphemeClass.LV || a == GraphemeClass.V) && (b == GraphemeClass.V || b == GraphemeClass.T)) return result(false)
    if ((a == GraphemeClass.LVT || a == GraphemeClass.T) && b == GraphemeClass.T) return result(false)
    // GB9a/GB9b and Unicode 18 GB9c (Linker Extend* × Consonant).
    if (b == GraphemeClass.SPACING || a == GraphemeClass.PREPEND) return result(false)
    if (afterLinker && b == GraphemeClass.CONSONANT) { base = 0; return result(false) }
    // GB11, with Ghostty's optional UTS #51 tailoring for isolated skin-tone modifiers.
    if (pictographic(a)) {
        if (extend(b) || b == GraphemeClass.ZWJ || a == GraphemeClass.MODIFIER_BASE && b == GraphemeClass.MODIFIER) {
            base = 2; return result(false)
        }
    } else if (base == 2) {
        if ((extend(a) || a == GraphemeClass.MODIFIER) && (extend(b) || b == GraphemeClass.ZWJ)) return result(false)
        if (a == GraphemeClass.ZWJ && pictographic(b)) { base = 0; return result(false) }
        base = 0
    }
    // GB12/13: pair regional indicators, then start a new cluster.
    if (a == GraphemeClass.RI && b == GraphemeClass.RI) {
        val boundary = base != 0
        base = if (boundary) 0 else 4
        return result(boundary)
    }
    // GB9/GB999.
    return result(!extend(b) && b != GraphemeClass.ZWJ)
}

/** -1 drops an invalid selector, 0 retains width, 1/2 replace the cluster width. */
internal fun graphemeWidthEffect(previous: Int, next: Int): Int {
    if (next == 0xfe0f || next == 0xfe0e) {
        if (TerminalUnicodeData.properties(previous) and 8 == 0) return -1
        return if (next == 0xfe0f) 2 else 1
    }
    return if (TerminalUnicodeData.properties(next) and 4 != 0) 0 else 2
}
