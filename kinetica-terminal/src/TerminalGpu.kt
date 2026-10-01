package io.heapy.kinetica.terminal

import kotlin.math.round

/** AUTO selects the platform GPU renderer and falls back when it cannot be used. */
public enum class TerminalRendering { AUTO, GPU, SOFTWARE }

internal data class GlyphKey(val text: String, val columns: Int, val style: Int)
internal data class GlyphBitmap(val width: Int, val height: Int, val rgba: ByteArray, val colored: Boolean)
internal data class AtlasGlyph(val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val colored: Boolean)

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
                placement = AtlasGlyph(index, page.x, page.y, bitmap.width, bitmap.height, bitmap.colored)
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

internal data class TerminalGlyph(val key: GlyphKey, val x: Double, val y: Double, val color: Int)
internal data class TerminalRect(val x: Double, val y: Double, val width: Double, val height: Double, val color: Int)
internal class TerminalDrawRow {
    val backgrounds = mutableListOf<TerminalRect>()
    val glyphs = mutableListOf<TerminalGlyph>()
    val decorations = mutableListOf<TerminalRect>()
    fun clear() { backgrounds.clear(); glyphs.clear(); decorations.clear() }
}

/** Retains clean rows; neither platform backend has to reinterpret terminal cells. */
internal class TerminalGpuFrame : TerminalGlyphPainter {
    val rows = mutableListOf<TerminalDrawRow>()
    val keys = linkedSetOf<GlyphKey>()
    var cellWidth: Double = 0.0
        private set
    var cellHeight: Double = 0.0
        private set
    private var current = TerminalDrawRow()
    private var columns = 0

    fun update(viewport: TerminalViewport, width: Double, height: Double) {
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
    override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
        appendRect(current.backgrounds, x, y, width, height, color)
    }
    override fun decoration(x: Double, y: Double, width: Double, height: Double, color: Int) {
        appendRect(current.decorations, x, y, width, height, color)
    }
    private fun appendRect(list: MutableList<TerminalRect>, x: Double, y: Double, width: Double, height: Double, color: Int) {
        // Merge adjacent equal backgrounds after keeping glyph boundaries independent.
        val last = list.lastOrNull()
        if (last != null && last.color == color && last.y == y && last.height == height && last.x + last.width == x) {
            list[list.lastIndex] = last.copy(width = last.width + width)
        } else list += TerminalRect(x, y, width, height, color)
    }
    override fun glyph(value: String, x: Double, y: Double, columns: Int, color: Int, style: Int) {
        if (value.isNotBlank()) current.glyphs += TerminalGlyph(GlyphKey(value, columns,
            style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)), x, y, color)
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
        u0: Float = 0f, v0: Float = 0f, u1: Float = 0f, v1: Float = 0f) {
        val offset = count * 12
        if (offset + 12 > data.size) data = data.copyOf(data.size * 2)
        data[offset] = x.toFloat(); data[offset + 1] = y.toFloat()
        data[offset + 2] = width.toFloat(); data[offset + 3] = height.toFloat()
        data[offset + 4] = u0; data[offset + 5] = v0; data[offset + 6] = u1; data[offset + 7] = v1
        data[offset + 8] = (color shr 16 and 255) / 255f
        data[offset + 9] = (color shr 8 and 255) / 255f
        data[offset + 10] = (color and 255) / 255f; data[offset + 11] = 1f
        count++
    }
    fun add(rect: TerminalRect) = add(rect.x, rect.y, rect.width, rect.height, rect.color)
}

internal class TerminalGpuBatches {
    val backgrounds = TerminalInstances()
    val decorations = TerminalInstances()
    val pages = mutableListOf<TerminalInstances>()
    fun build(frame: TerminalGpuFrame, atlas: TerminalGlyphAtlas, pixelRatio: Double = 1.0) {
        require(pixelRatio.isFinite() && pixelRatio > 0)
        backgrounds.clear(); decorations.clear(); pages.forEach { it.clear() }
        while (pages.size < atlas.pageCount) pages += TerminalInstances()
        for (row in frame.rows) {
            for (rect in row.backgrounds) addRect(backgrounds, rect, pixelRatio)
            for (rect in row.decorations) addRect(decorations, rect, pixelRatio)
            for (glyph in row.glyphs) {
                val entry = atlas[glyph.key]
                // The atlas already contains antialiased device pixels. Squeezing its
                // ceil-sized bitmap into a fractional cell drops different texel columns
                // at different grid positions with nearest sampling. Preserve it 1:1.
                val x = round(glyph.x * pixelRatio)
                val y = round(glyph.y * pixelRatio)
                // A rounded cell can be one pixel narrower than the ceil-sized bitmap.
                // Crop its padding/overhang instead of blending it into the next cell.
                val width = minOf(entry.width.toDouble(), round((glyph.x + frame.cellWidth * glyph.key.columns) * pixelRatio) - x)
                val height = minOf(entry.height.toDouble(), round((glyph.y + frame.cellHeight) * pixelRatio) - y)
                pages[entry.page].add(x / pixelRatio, y / pixelRatio, width / pixelRatio, height / pixelRatio,
                    if (entry.colored) 0xffffff else glyph.color,
                    entry.x.toFloat() / atlas.size, entry.y.toFloat() / atlas.size,
                    (entry.x + width).toFloat() / atlas.size, (entry.y + height).toFloat() / atlas.size)
            }
        }
    }

    private fun addRect(target: TerminalInstances, rect: TerminalRect, pixelRatio: Double) {
        val x = round(rect.x * pixelRatio) / pixelRatio
        val y = round(rect.y * pixelRatio) / pixelRatio
        target.add(x, y, round((rect.x + rect.width) * pixelRatio) / pixelRatio - x,
            round((rect.y + rect.height) * pixelRatio) / pixelRatio - y, rect.color)
    }
}
