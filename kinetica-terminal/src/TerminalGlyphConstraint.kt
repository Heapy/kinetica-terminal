// Adapted from Ghostty 1.4 font/Glyph.zig and renderer/cell.zig (MIT).
// See third-party/GHOSTTY-LICENSE and test-upstream/ghostty/glyph-constraints-manifest.json.
package io.heapy.kinetica.terminal

internal enum class TerminalGlyphSizing { NONE, FIT, COVER, FIT_COVER1, STRETCH }
internal enum class TerminalGlyphAlignment { NONE, START, END, CENTER, CENTER1 }
internal enum class TerminalGlyphHeight { CELL, ICON }

/** Glyph bounds in physical pixels, with +Y upwards from the bottom of the cell. */
internal data class TerminalGlyphBounds(val width: Double, val height: Double, val x: Double, val y: Double)
internal data class TerminalGlyphMetrics(
    val cellWidth: Double, val cellHeight: Double,
    val faceWidth: Double, val faceHeight: Double, val faceY: Double,
    val iconHeight: Double, val iconHeightSingle: Double,
)

/** Apply the original scale-group rules before rasterization, never to an atlas texture. */
internal data class TerminalGlyphConstraint(
    val size: TerminalGlyphSizing = TerminalGlyphSizing.NONE,
    val vertical: TerminalGlyphAlignment = TerminalGlyphAlignment.NONE,
    val horizontal: TerminalGlyphAlignment = TerminalGlyphAlignment.NONE,
    val padTop: Double = 0.0, val padLeft: Double = 0.0, val padRight: Double = 0.0, val padBottom: Double = 0.0,
    val relativeWidth: Double = 1.0, val relativeHeight: Double = 1.0,
    val relativeX: Double = 0.0, val relativeY: Double = 0.0,
    val maxAspectRatio: Double? = null, val maxColumns: Int = 2,
    val height: TerminalGlyphHeight = TerminalGlyphHeight.CELL,
) {
    val active: Boolean get() = size != TerminalGlyphSizing.NONE ||
        horizontal != TerminalGlyphAlignment.NONE || vertical != TerminalGlyphAlignment.NONE

    fun constrain(glyph: TerminalGlyphBounds, metrics: TerminalGlyphMetrics, columns: Int): TerminalGlyphBounds {
        if (!active) return glyph
        require(columns in 1..2)
        return if (size == TerminalGlyphSizing.STRETCH) {
            // Stretch aligns to the physical grid, discarding the negative padding
            // formerly needed to cover gaps introduced by font-unit rounding.
            copy(padTop = maxOf(0.0, padTop), padBottom = maxOf(0.0, padBottom),
                padLeft = maxOf(0.0, padLeft), padRight = maxOf(0.0, padRight))
                .constrainInner(glyph, metrics.copy(faceWidth = metrics.cellWidth, faceHeight = metrics.cellHeight, faceY = 0.0), columns)
        } else constrainInner(glyph, metrics, columns)
    }

    private fun constrainInner(glyph: TerminalGlyphBounds, metrics: TerminalGlyphMetrics, columns: Int): TerminalGlyphBounds {
        val span = if (size == TerminalGlyphSizing.STRETCH && metrics.faceWidth > 0.9 * metrics.faceHeight) 1
            else minOf(maxColumns, columns)
        val groupWidth = glyph.width / relativeWidth
        val groupHeight = glyph.height / relativeHeight
        val original = TerminalGlyphBounds(groupWidth, groupHeight,
            glyph.x - groupWidth * relativeX, glyph.y - groupHeight * relativeY)
        val (scaleX, scaleY) = scaleFactors(original, metrics, span)
        val width = groupWidth * scaleX; val height = groupHeight * scaleY
        val group = TerminalGlyphBounds(width, height,
            original.x + groupWidth / 2 - width / 2, original.y + groupHeight / 2 - height / 2)
        return TerminalGlyphBounds(scaleX * glyph.width, scaleY * glyph.height,
            alignedX(group, metrics, span) + group.width * relativeX,
            alignedY(group, metrics) + group.height * relativeY)
    }

    private fun scaleFactors(group: TerminalGlyphBounds, metrics: TerminalGlyphMetrics, columns: Int): Pair<Double, Double> {
        if (size == TerminalGlyphSizing.NONE) return 1.0 to 1.0
        val targetWidth = (columns - (padLeft + padRight)) * metrics.faceWidth
        val targetHeight = (1 - (padBottom + padTop)) * when (height) {
            TerminalGlyphHeight.CELL -> metrics.faceHeight
            TerminalGlyphHeight.ICON -> if (columns > 1) metrics.iconHeight else metrics.iconHeightSingle
        }
        var scaleX = targetWidth / group.width
        var scaleY = targetHeight / group.height
        when (size) {
            TerminalGlyphSizing.NONE -> error("Unreachable")
            TerminalGlyphSizing.FIT -> { scaleY = minOf(1.0, scaleX, scaleY); scaleX = scaleY }
            TerminalGlyphSizing.COVER -> { scaleY = minOf(scaleX, scaleY); scaleX = scaleY }
            TerminalGlyphSizing.FIT_COVER1 -> {
                scaleY = minOf(scaleX, scaleY)
                if (columns > 1 && scaleY > 1) scaleY = maxOf(1.0, scaleFactors(group, metrics, 1).second)
                scaleX = scaleY
            }
            TerminalGlyphSizing.STRETCH -> Unit
        }
        maxAspectRatio?.let { if (group.width * scaleX > group.height * scaleY * it) scaleX = group.height * scaleY * it / group.width }
        return scaleX to scaleY
    }

    private fun alignedY(group: TerminalGlyphBounds, metrics: TerminalGlyphMetrics): Double {
        if (size == TerminalGlyphSizing.NONE && vertical == TerminalGlyphAlignment.NONE) return group.y
        val start = metrics.faceY + padBottom * metrics.faceHeight
        val end = metrics.faceY + metrics.faceHeight - group.height - padTop * metrics.faceHeight
        val center = (start + end) / 2
        return when (vertical) {
            TerminalGlyphAlignment.NONE -> if (end < start) center else maxOf(start, minOf(group.y, end))
            TerminalGlyphAlignment.START -> start
            TerminalGlyphAlignment.END -> end
            TerminalGlyphAlignment.CENTER, TerminalGlyphAlignment.CENTER1 -> center
        }
    }

    private fun alignedX(group: TerminalGlyphBounds, metrics: TerminalGlyphMetrics, columns: Int): Double {
        if (size == TerminalGlyphSizing.NONE && horizontal == TerminalGlyphAlignment.NONE) return group.x
        val start = padLeft * metrics.faceWidth
        val end = metrics.faceWidth + (columns - 1) * metrics.cellWidth - group.width - padRight * metrics.faceWidth
        return when (horizontal) {
            TerminalGlyphAlignment.NONE -> maxOf(start, minOf(group.x, end))
            TerminalGlyphAlignment.START -> start
            TerminalGlyphAlignment.END -> maxOf(start, end)
            TerminalGlyphAlignment.CENTER -> maxOf(start, (start + end) / 2)
            TerminalGlyphAlignment.CENTER1 -> maxOf(start, (start + metrics.faceWidth - group.width - padRight * metrics.faceWidth) / 2)
        }
    }

    companion object {
        val NONE = TerminalGlyphConstraint()
        val SYMBOL = TerminalGlyphConstraint(size = TerminalGlyphSizing.FIT)
        val EMOJI = TerminalGlyphConstraint(size = TerminalGlyphSizing.COVER,
            horizontal = TerminalGlyphAlignment.CENTER, vertical = TerminalGlyphAlignment.CENTER, padLeft = 0.025, padRight = 0.025)
    }
}

internal fun terminalGlyphConstraint(code: Int, emoji: Boolean = false): TerminalGlyphConstraint =
    if (emoji) TerminalGlyphConstraint.EMOJI else TerminalGlyphConstraintData.constraint(code)
        ?: if (TerminalGlyphConstraintData.isSymbol(code)) TerminalGlyphConstraint.SYMBOL else TerminalGlyphConstraint.NONE

/** A symbol can occupy the following blank unless another non-graphics symbol precedes it. */
internal fun terminalGlyphConstraintWidth(line: TerminalLine, column: Int): Int {
    // Ghostty's gridWidth reports one column for wide-character spacer cells.
    val width = line.width(column).coerceAtLeast(1)
    if (width > 1 || !TerminalGlyphConstraintData.isSymbol(line.codePoint(column))) return width
    if (column == line.columns - 1) return 1
    if (column > 0) {
        val previous = line.codePoint(column - 1)
        val graphics = previous in 0x2500..0x259f || previous in 0x1fb00..0x1fbff ||
            previous in 0x1cc00..0x1cebf || previous in 0xe0b0..0xe0d7
        if (TerminalGlyphConstraintData.isSymbol(previous) && !graphics) return 1
    }
    return when (line.codePoint(column + 1)) { 0, 0x20, 0x2002 -> 2; else -> 1 }
}
