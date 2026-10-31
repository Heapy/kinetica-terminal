package io.heapy.kinetica.terminal

import kotlin.math.round

/** AUTO selects the platform GPU renderer and falls back when it cannot be used. */
public enum class TerminalRendering { AUTO, GPU, SOFTWARE }

internal data class GlyphKey(val text: String, val columns: Int, val style: Int, val font: Int = -1, val glyph: Int = -1,
    val constraint: TerminalGlyphConstraint = TerminalGlyphConstraint.NONE, val foreground: Int? = null)
internal data class GlyphBitmap(val width: Int, val height: Int, val rgba: ByteArray, val colored: Boolean,
    val left: Int = 0, val top: Int = 0)
internal data class AtlasGlyph(val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val colored: Boolean,
    val left: Int = 0, val top: Int = 0)

/** Bounded shelf atlas. A rollover rebuilds all visible keys before any draw uses the new atlas. */
internal class TerminalGlyphAtlas(val size: Int = 1024, private val maxPages: Int = 4) {
    private class Page(var x: Int = 1, var y: Int = 1, var shelfHeight: Int = 0)
    private val pages = mutableListOf<Page>()
    private val entries = mutableMapOf<GlyphKey, AtlasGlyph>()
    var generation: Int = 0
        private set
    val entryCount: Int get() = entries.size
    val pageCount: Int get() = pages.size

    init { require(size >= 4 && maxPages > 0) }

    fun clear() { entries.clear(); pages.clear(); generation++ }
    operator fun get(key: GlyphKey): AtlasGlyph = entries.getValue(key)

    fun prepare(keys: Set<GlyphKey>, rasterize: (GlyphKey) -> GlyphBitmap,
        reset: () -> Unit, upload: (AtlasGlyph, GlyphBitmap) -> Unit) {
        if (populate(keys, rasterize, upload)) return
        // Do not evict a page under instances already built for this frame.
        clear(); reset()
        check(populate(keys, rasterize, upload)) { "Visible terminal glyphs exceed the bounded GPU atlas" }
    }

    private fun populate(keys: Set<GlyphKey>, rasterize: (GlyphKey) -> GlyphBitmap,
        upload: (AtlasGlyph, GlyphBitmap) -> Unit): Boolean {
        for (key in keys) {
            if (key in entries) continue
            val bitmap = rasterize(key)
            require(bitmap.width > 0 && bitmap.height > 0 && bitmap.rgba.size.toLong() == bitmap.width.toLong() * bitmap.height * 4)
            if (bitmap.width + 2 > size || bitmap.height + 2 > size) return false
            var placement: AtlasGlyph? = null
            for (index in 0..pages.size) {
                if (index == pages.size) {
                    if (pages.size == maxPages) break
                    pages += Page()
                }
                val page = pages[index]
                if (page.x + bitmap.width + 1 > size) {
                    page.y += page.shelfHeight + 1; page.x = 1; page.shelfHeight = 0
                }
                if (page.y + bitmap.height + 1 > size) continue
                placement = AtlasGlyph(index, page.x, page.y, bitmap.width, bitmap.height, bitmap.colored, bitmap.left, bitmap.top)
                page.x += bitmap.width + 1; page.shelfHeight = maxOf(page.shelfHeight, bitmap.height)
                break
            }
            if (placement == null) return false
            upload(placement, bitmap)
            entries[key] = placement
        }
        return true
    }
}

/** Converts straight RGBA from platform font rasterizers to premultiplied RGBA for both GPUs. */
internal fun glyphBitmap(width: Int, height: Int, rgba: ByteArray, premultiplied: Boolean = false): GlyphBitmap {
    var colored = false
    for (i in rgba.indices step 4) {
        val r = rgba[i].toInt() and 255; val g = rgba[i + 1].toInt() and 255
        val b = rgba[i + 2].toInt() and 255; val a = rgba[i + 3].toInt() and 255
        if (a > 0 && (r != g || g != b)) colored = true
        if (!premultiplied) {
            rgba[i] = ((r * a + 127) / 255).toByte()
            rgba[i + 1] = ((g * a + 127) / 255).toByte()
            rgba[i + 2] = ((b * a + 127) / 255).toByte()
        }
    }
    return GlyphBitmap(width, height, rgba, colored)
}

internal data class TerminalGlyph(val key: GlyphKey, val x: Double, val y: Double, val color: Int,
    val xOffset: Int = 0, val yOffset: Int = 0, val clipLeft: Double = x, val clipRight: Double = Double.NaN,
    val opacity: Double = 1.0, val contrastExcluded: Boolean = false)
internal data class TerminalRect(val x: Double, val y: Double, val width: Double, val height: Double, val color: Int,
    val defaultBackground: Boolean = false, val opacity: Double = 1.0, val behindText: Boolean = false)

// Tie-to-even endpoint rounding can collapse a 1px line at 9.5..10.5 to 10..10.
// Preserve spans at least a physical pixel; shorter cell-edge fragments may snap away.
internal fun terminalPixelSpan(start: Double, size: Double, ratio: Double): Double =
    (round((start + size) * ratio) - round(start * ratio)).coerceAtLeast(if (size * ratio >= 1.0) 1.0 else 0.0) / ratio
internal class TerminalDrawRow {
    val backgrounds = mutableListOf<TerminalRect>()
    val glyphs = mutableListOf<TerminalGlyph>()
    val decorations = mutableListOf<TerminalRect>()
    fun clear() { backgrounds.clear(); glyphs.clear(); decorations.clear() }
}

/** Retains clean rows; neither platform backend has to reinterpret terminal cells. */
internal class TerminalGpuFrame(
    private val resolveFont: ((String, Int) -> TerminalResolvedFontCell)? = null,
    private val shape: ((TerminalFontRun) -> List<TerminalShapedGlyph>)? = null,
) : TerminalGlyphPainter {
    init { require((resolveFont == null) == (shape == null)) }
    override val shapesRows: Boolean get() = resolveFont != null
    val rows = mutableListOf<TerminalDrawRow>()
    val keys = linkedSetOf<GlyphKey>()
    var cellWidth: Double = 0.0
        private set
    var cellHeight: Double = 0.0
        private set
    private var current = TerminalDrawRow()
    private var columns = 0
    private var opacity = 1.0
    private var faintOpacity = 0.5
    var minimumContrast = 1.0
        private set
    var defaultBackground = 0
        private set
    var contrastCursor: TerminalCellPosition? = null
        private set

    override fun withOpacity(opacity: Double, draw: () -> Unit) {
        val previous = this.opacity
        this.opacity *= opacity
        try { draw() } finally { this.opacity = previous }
    }

    fun update(viewport: TerminalViewport, width: Double, height: Double) {
        faintOpacity = viewport.faintOpacity
        minimumContrast = viewport.minimumContrast
        defaultBackground = viewport.theme.background
        contrastCursor = viewport.contrastCursor()
        if (rows.size != viewport.rows || columns != viewport.columns || cellWidth != width || cellHeight != height) {
            rows.clear(); repeat(viewport.rows) { rows += TerminalDrawRow() }
            cellWidth = width; cellHeight = height; columns = viewport.columns
            viewport.markAll()
        }
        viewport.paint(this, width, height)
        keys.clear()
        for (row in rows) for (glyph in row.glyphs) keys += glyph.key
    }

    override fun beginRow(row: Int) { current = rows[row]; current.clear() }
    override fun shapeRow(line: TerminalLine, row: Int, cursor: Int?, selected: BooleanArray, isolated: IntRange?, foreground: (Int) -> Int): Boolean {
        val resolve = resolveFont ?: return false
        val shape = checkNotNull(shape)
        for (run in terminalFontRuns(line, cursor, selected, isolated, resolve)) {
            if (run.font < 0) continue
            for (glyph in shape(run)) {
                val column = run.offset + glyph.column
                val constraint = terminalGlyphConstraint(line.codePoint(column), glyph.emoji)
                val available = if (constraint.active) terminalGlyphConstraintWidth(line, column) else 0
                // Symbol constraints may intentionally use a blank beyond the font run.
                // Its available span is part of atlas identity because it changes rasterization.
                val right = maxOf(run.offset + run.input.columns, column + available).coerceAtMost(line.columns)
                current.glyphs += TerminalGlyph(GlyphKey("", available, run.style and (TerminalStyle.BOLD or TerminalStyle.ITALIC),
                    glyph.font, glyph.glyph, constraint, if (glyph.usesForeground) foreground(column) else null),
                    column * cellWidth, row * cellHeight, foreground(column),
                    glyph.xOffset, glyph.yOffset, run.offset * cellWidth, right * cellWidth,
                    terminalForegroundOpacity(line.style(column), faintOpacity),
                    contrastExcluded = minimumContrast > 1.0 && terminalContrastExcluded(line.codePoint(column)))
            }
        }
        return true
    }
    override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
        appendRect(current.backgrounds, x, y, width, height, color)
    }
    override fun background(x: Double, y: Double, width: Double, height: Double, color: Int, default: Boolean) {
        appendRect(current.backgrounds, x, y, width, height, color, default)
    }
    override fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
        appendRect(current.decorations, x, y, width, height, color)
    }
    override fun underlay(x: Double, y: Double, width: Double, height: Double, color: Int) {
        appendRect(current.decorations, x, y, width, height, color, behindText = true)
    }
    private fun appendRect(list: MutableList<TerminalRect>, x: Double, y: Double, width: Double, height: Double, color: Int,
        default: Boolean = false, behindText: Boolean = false) {
        // Merge adjacent equal backgrounds after keeping glyph boundaries independent.
        val last = list.lastOrNull()
        if (last != null && last.color == color && last.y == y && last.height == height && last.x + last.width == x && last.defaultBackground == default && last.opacity == opacity && last.behindText == behindText) {
            list[list.lastIndex] = last.copy(width = last.width + width)
        } else list += TerminalRect(x, y, width, height, color, default, opacity, behindText)
    }
    override fun glyph(value: String, x: Double, y: Double, columns: Int, color: Int, style: Int) {
        if (value.isNotBlank()) current.glyphs += TerminalGlyph(GlyphKey(value, columns,
            style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)), x, y, color,
            clipRight = minOf(x + columns * cellWidth, this.columns * cellWidth), opacity = opacity,
            contrastExcluded = minimumContrast > 1.0 && terminalContrastExcluded(terminalFirstCodepoint(value)))
    }
    override fun text(value: String, x: Double, y: Double, color: Int, style: Int) =
        glyph(value, x, y, 1, color, style)
}

/** 12 floats per instance: rectangle, UV rectangle, premultiplied tint. Reuses backing storage. */
internal class TerminalInstances {
    var data = FloatArray(12 * 128)
        private set
    var count = 0
        private set
    val floatCount: Int get() = count * 12
    fun clear() { count = 0 }
    fun add(x: Double, y: Double, width: Double, height: Double, color: Int,
        u0: Float = 0f, v0: Float = 0f, u1: Float = 0f, v1: Float = 0f, opacity: Double = 1.0) {
        val offset = count * 12
        if (offset + 12 > data.size) data = data.copyOf(data.size * 2)
        data[offset] = x.toFloat(); data[offset + 1] = y.toFloat()
        data[offset + 2] = width.toFloat(); data[offset + 3] = height.toFloat()
        data[offset + 4] = u0; data[offset + 5] = v0; data[offset + 6] = u1; data[offset + 7] = v1
        val alpha = opacity.toFloat()
        data[offset + 8] = (color shr 16 and 255) / 255f * alpha
        data[offset + 9] = (color shr 8 and 255) / 255f * alpha
        data[offset + 10] = (color and 255) / 255f * alpha; data[offset + 11] = alpha
        count++
    }
    fun add(rect: TerminalRect) = add(rect.x, rect.y, rect.width, rect.height, rect.color, opacity = rect.opacity)
}

internal class TerminalGpuBatches {
    val backgrounds = TerminalInstances()
    val underlays = TerminalInstances()
    val decorations = TerminalInstances()
    val pages = mutableListOf<TerminalInstances>()
    fun build(frame: TerminalGpuFrame, atlas: TerminalGlyphAtlas, pixelRatio: Double = 1.0, transparentDefaultBackground: Boolean = false) {
        require(pixelRatio.isFinite() && pixelRatio > 0)
        backgrounds.clear(); underlays.clear(); decorations.clear(); pages.forEach { it.clear() }
        while (pages.size < atlas.pageCount) pages += TerminalInstances()
        for (row in frame.rows) {
            for (rect in row.backgrounds) if (!transparentDefaultBackground || !rect.defaultBackground) addRect(backgrounds, rect, pixelRatio)
            for (rect in row.decorations) addRect(if (rect.behindText) underlays else decorations, rect, pixelRatio)
        }
        drawTerminalGlyphs(frame, atlas, pixelRatio) { entry, x, y, width, height, sourceX, sourceY, sourceWidth, sourceHeight, tint, opacity ->
            pages[entry.page].add(x, y, width, height, tint,
                (entry.x + sourceX).toFloat() / atlas.size, (entry.y + sourceY).toFloat() / atlas.size,
                (entry.x + sourceX + sourceWidth).toFloat() / atlas.size,
                (entry.y + sourceY + sourceHeight).toFloat() / atlas.size, opacity)
        }
    }

    private fun addRect(target: TerminalInstances, rect: TerminalRect, pixelRatio: Double) {
        val x = round(rect.x * pixelRatio) / pixelRatio
        val y = round(rect.y * pixelRatio) / pixelRatio
        target.add(x, y, terminalPixelSpan(rect.x, rect.width, pixelRatio),
            terminalPixelSpan(rect.y, rect.height, pixelRatio), rect.color, opacity = rect.opacity)
    }
}

/** Shared device-pixel clipping and tint resolution for GPU and software bitmap renderers.
 * Source rectangles are relative to the glyph bitmap, without atlas page offsets. */
internal inline fun drawTerminalGlyphs(frame: TerminalGpuFrame, atlas: TerminalGlyphAtlas, pixelRatio: Double,
    firstRow: Int = 0, lastRow: Int = frame.rows.lastIndex,
    draw: (AtlasGlyph, Double, Double, Double, Double, Double, Double, Double, Double, Int, Double) -> Unit) {
    require(pixelRatio.isFinite() && pixelRatio > 0)
    for (index in firstRow.coerceAtLeast(0)..lastRow.coerceAtMost(frame.rows.lastIndex)) for (glyph in frame.rows[index].glyphs) {
        val row = frame.rows[index]
        val entry = atlas[glyph.key]
        val correction = terminalGlyphContrast(frame, row, glyph, entry.colored)
        val tint = if (entry.colored) 0xffffff else correction ?: glyph.color
        // Ghostty's color-atlas branch ignores vertex tint/alpha. The vertex shader
        // also replaces block-cursor text with the opaque cursor foreground.
        val opacity = if (entry.colored || correction != null || terminalGlyphUnderCursor(frame, glyph)) 1.0 else glyph.opacity
        if (glyph.key.glyph >= 0) {
            val left = round(glyph.x * pixelRatio) + glyph.xOffset + entry.left
            val top = round(glyph.y * pixelRatio) - glyph.yOffset + entry.top
            val x = maxOf(left, round(glyph.clipLeft * pixelRatio))
            val y = maxOf(top, round(glyph.y * pixelRatio))
            val right = minOf(left + entry.width, round(glyph.clipRight * pixelRatio))
            val bottom = minOf(top + entry.height, round((glyph.y + frame.cellHeight) * pixelRatio))
            if (right <= x || bottom <= y) continue
            draw(entry, x / pixelRatio, y / pixelRatio, (right - x) / pixelRatio, (bottom - y) / pixelRatio,
                x - left, y - top, right - x, bottom - y, tint, opacity)
        } else {
            // Keep raster texels 1:1, cropping ceil-sized padding at rounded cell edges.
            val x = round(glyph.x * pixelRatio)
            val y = round(glyph.y * pixelRatio)
            val right = glyph.clipRight.takeIf { it.isFinite() } ?: (glyph.x + frame.cellWidth * glyph.key.columns)
            val width = minOf(entry.width.toDouble(), round(right * pixelRatio) - x)
            val height = minOf(entry.height.toDouble(), round((glyph.y + frame.cellHeight) * pixelRatio) - y)
            draw(entry, x / pixelRatio, y / pixelRatio, width / pixelRatio, height / pixelRatio,
                0.0, 0.0, width, height, tint, opacity)
        }
    }
}
