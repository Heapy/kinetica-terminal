package io.heapy.kinetica.terminal

import kotlin.math.ceil
import kotlin.math.floor

/** One font run. UTF-16 indices map to terminal columns, including surrogate-pair padding. */
internal class TerminalShapingText(val text: String, clusters: IntArray, val columns: Int) {
    val clusters = clusters.copyOf()

    init {
        require(columns >= 0 && text.length == clusters.size)
        require(clusters.all { it in 0 until columns })
        for (i in 1 until text.length) {
            if (text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()) require(clusters[i] == clusters[i - 1])
        }
    }

    fun firstInCluster(index: Int): Boolean {
        var previous = index - 1
        if (previous > 0 && text[previous].isLowSurrogate() && text[previous - 1].isHighSurrogate()) previous--
        return previous < 0 || clusters[previous] != clusters[index]
    }
}

/** Platform shaper output. Positions and advances use the same physical-pixel coordinate space. */
internal data class TerminalRawGlyph(val font: Int, val glyph: Int, val index: Int,
    val advanceX: Double, val x: Double, val y: Double)

/** A font glyph positioned relative to a terminal column, independently of its natural advance. */
internal data class TerminalShapedGlyph(val font: Int, val glyph: Int, val column: Int,
    val xOffset: Int, val yOffset: Int)

/**
 * Align starts of clusters to the terminal grid, retaining offsets of marks belonging to
 * multi-cell ligatures. Adapted from Ghostty 1.4 font/shaper/{coretext,harfbuzz}.zig (MIT).
 * Resetting at every changed string index incorrectly moves marking glyphs after ligatures.
 */
internal fun terminalPositionGlyphs(text: TerminalShapingText, glyphs: List<TerminalRawGlyph>,
    reorder: Boolean = false): List<TerminalShapedGlyph> {
    var furthestCluster = 0
    var advance = 0.0
    var cellColumn = 0
    var cellAdvance = 0.0
    val result = ArrayList<TerminalShapedGlyph>(glyphs.size)
    for (glyph in glyphs) {
        require(glyph.index in text.clusters.indices)
        require(glyph.advanceX.isFinite() && glyph.x.isFinite() && glyph.y.isFinite())
        val cluster = text.clusters[glyph.index]
        if (cellColumn != cluster && cluster > furthestCluster && text.firstInCluster(glyph.index)) {
            cellColumn = cluster
            cellAdvance = advance
        }
        result += TerminalShapedGlyph(glyph.font, glyph.glyph, cellColumn,
            terminalGlyphRound(glyph.x - cellAdvance), terminalGlyphRound(glyph.y))
        advance += glyph.advanceX
        furthestCluster = maxOf(furthestCluster, cluster)
    }
    return if (reorder) result.sortedBy { it.column } else result
}

// CoreText's original shaper uses Zig @round: halfway values round away from zero.
private fun terminalGlyphRound(value: Double): Int =
    (if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)).toInt()
