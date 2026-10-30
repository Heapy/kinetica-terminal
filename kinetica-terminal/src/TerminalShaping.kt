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
    val xOffset: Int, val yOffset: Int, val emoji: Boolean = false, val usesForeground: Boolean = false)

internal data class TerminalResolvedFontCell(val font: Int, val text: String)
internal data class TerminalFontRun(val offset: Int, val style: Int, val font: Int, val input: TerminalShapingText)

/** Original run boundaries, before platform shaping. Background and hover decorations are independent. */
internal fun terminalFontRuns(line: TerminalLine, cursor: Int?, selected: BooleanArray,
    resolve: (String, Int) -> TerminalResolvedFontCell): List<TerminalFontRun> {
    require(selected.size == line.columns)
    var end = line.columns
    while (end > 0 && line.isEmpty(end - 1)) end--
    val result = mutableListOf<TerminalFontRun>()
    var start = 0
    while (start < end) {
        if (line.style(start) and TerminalStyle.HIDDEN != 0) { start++; continue }
        val style = line.style(start)
        val text = StringBuilder()
        val clusters = mutableListOf<Int>()
        var font: Int? = null
        var column = start
        while (column < end) {
            // Ghostty only applies the end boundary when its inclusive column is
            // greater than zero: selecting column zero alone keeps the run intact.
            if (column > start && selected[column] != selected[column - 1] &&
                !(column == 1 && selected[0])) break
            if (line.width(column) == 0 || line.isSpacerHead(column)) { column++; continue }
            if (column > start) {
                val previous = column - 1
                if (!line.isCombined(previous) && !line.isCombined(column)) {
                    val before = line.codePoint(previous); val after = line.codePoint(column)
                    if (before == 'f'.code && (after == 'i'.code || after == 'l'.code) || before == 's'.code && after == 't'.code) break
                }
                if (line.style(column) != style || line.rawForeground(column) != line.rawForeground(start) ||
                    line.rawUnderlineColor(column) != line.rawUnderlineColor(start)) break
            }
            if (!line.isCombined(column) && cursor != null &&
                (start == cursor && column == start + 1 || start < cursor && column == cursor)) break
            val value = if (line.codePoint(column) == TERMINAL_GRAPHICS_PLACEHOLDER || line.codePoint(column) == 0) " " else line.text(column)
            // Existing geometric block drawing stays outside the platform font atlas.
            val resolved = if (!line.isCombined(column) && isTerminalBlockElement(line.codePoint(column)))
                TerminalResolvedFontCell(-1, value) else resolve(value, style)
            if (font != null && font != resolved.font) break
            font = resolved.font
            for (character in resolved.text) {
                // Presentation selectors choose the face, but are not sent to the shaper.
                if (character == '\ufe0e' || character == '\ufe0f') continue
                text.append(character); clusters += column - start
            }
            column++
        }
        check(column > start)
        result += TerminalFontRun(start, style, font ?: -1,
            TerminalShapingText(text.toString(), clusters.toIntArray(), column - start))
        start = column
    }
    return result
}

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
