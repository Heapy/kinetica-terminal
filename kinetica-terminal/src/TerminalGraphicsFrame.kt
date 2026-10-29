package io.heapy.kinetica.terminal

/** Immutable placement snapshot; decoded pixels are owned by their immutable content generation. */
internal data class TerminalGraphicsFrameEntry(
    val image: TerminalGraphicsStoredImage,
    val geometry: TerminalGraphicsGeometry,
    val column: Int,
    val row: Long,
    val z: Int,
    val layer: TerminalGraphicsLayer,
)

internal fun TerminalGraphicsState.captureFrame(scrollOffset: Int, columns: Int, rows: Int, cell: TerminalCellSize?): List<TerminalGraphicsFrameEntry> =
    placements.mapNotNull { placement ->
        val origin = position(placement) ?: return@mapNotNull null
        val image = placement.image
        val geometry = placement.geometry.resolve(image.pixels.width, image.pixels.height, cell)
        val row = origin.y.toLong() + scrollOffset
        if (geometry.source.width == 0L || geometry.source.height == 0L || geometry.pixels.width == 0L || geometry.pixels.height == 0L ||
            geometry.grid.width == 0L || geometry.grid.height == 0L || row >= rows || row + geometry.grid.height <= 0 ||
            origin.x >= columns || origin.x.toLong() + geometry.grid.width <= 0) return@mapNotNull null
        TerminalGraphicsFrameEntry(image, geometry, origin.x, row, placement.geometry.z, placement.geometry.layer)
    }.sortedWith(compareBy({ it.z }, { it.image.id }))

/** Clip before converting to GPU floats, including extreme relative offsets and uint32 sizes. */
internal data class TerminalImageQuad(
    val image: TerminalGraphicsStoredImage,
    val layer: TerminalGraphicsLayer,
    val x: Double, val y: Double, val width: Double, val height: Double,
    val sourceX: Double, val sourceY: Double, val sourceWidth: Double, val sourceHeight: Double,
    val cropX: Int = 0, val cropY: Int = 0,
    val cropWidth: Int = image.pixels.width, val cropHeight: Int = image.pixels.height,
)

internal fun TerminalGraphicsFrameEntry.quad(cellWidth: Double, cellHeight: Double, pixelRatio: Double,
    viewportColumns: Int, viewportRows: Int): TerminalImageQuad? {
    require(cellWidth.isFinite() && cellWidth > 0 && cellHeight.isFinite() && cellHeight > 0 && pixelRatio.isFinite() && pixelRatio > 0)
    val x = column * cellWidth + geometry.offset.width / pixelRatio
    val y = row * cellHeight + geometry.offset.height / pixelRatio
    val width = geometry.pixels.width / pixelRatio; val height = geometry.pixels.height / pixelRatio
    val left = maxOf(0.0, x); val top = maxOf(0.0, y)
    val right = minOf(viewportColumns * cellWidth, x + width); val bottom = minOf(viewportRows * cellHeight, y + height)
    if (right <= left || bottom <= top || width <= 0 || height <= 0) return null
    return TerminalImageQuad(image, layer, left, top, right - left, bottom - top,
        geometry.source.x + (left - x) / width * geometry.source.width,
        geometry.source.y + (top - y) / height * geometry.source.height,
        (right - left) / width * geometry.source.width, (bottom - top) / height * geometry.source.height,
        geometry.source.x.toInt(), geometry.source.y.toInt(), geometry.source.width.toInt(), geometry.source.height.toInt())
}

internal data class TerminalImageTileKey(val generation: Any, val x: Int, val y: Int, val width: Int = 0, val height: Int = 0)
internal data class TerminalImageTile(val key: TerminalImageTileKey, val left: Int, val top: Int, val width: Int, val height: Int)
internal data class TerminalImageTileDraw(val tile: TerminalImageTile, val quad: TerminalImageQuad)

/** Tiles have one source-pixel gutter so linear sampling does not introduce seams. */
internal fun TerminalImageQuad.tiles(side: Int = 1024): List<TerminalImageTileDraw> {
    require(side in 4..2048)
    val stride = side - 2
    val pixels = image.pixels
    val firstX = (sourceX / stride).toInt(); val firstY = (sourceY / stride).toInt()
    val lastX = (kotlin.math.ceil((sourceX + sourceWidth) / stride).toInt() - 1).coerceAtLeast(firstX)
    val lastY = (kotlin.math.ceil((sourceY + sourceHeight) / stride).toInt() - 1).coerceAtLeast(firstY)
    val result = mutableListOf<TerminalImageTileDraw>()
    for (row in firstY..lastY) for (column in firstX..lastX) {
        val innerX = column * stride; val innerY = row * stride
        val left = maxOf(sourceX, innerX.toDouble()); val top = maxOf(sourceY, innerY.toDouble())
        val right = minOf(sourceX + sourceWidth, (innerX + stride).toDouble(), pixels.width.toDouble())
        val bottom = minOf(sourceY + sourceHeight, (innerY + stride).toDouble(), pixels.height.toDouble())
        if (right <= left || bottom <= top) continue
        // Crop the edge tiles themselves: Canvas and GPU linear sampling must not bleed
        // pixels outside the placement's source rectangle into its enlarged edges.
        val tileLeft = (innerX - 1).coerceAtLeast(cropX); val tileTop = (innerY - 1).coerceAtLeast(cropY)
        val tileWidth = minOf(cropX + cropWidth, innerX + stride + 1) - tileLeft
        val tileHeight = minOf(cropY + cropHeight, innerY + stride + 1) - tileTop
        val tile = TerminalImageTile(TerminalImageTileKey(image.generation, tileLeft, tileTop, tileWidth, tileHeight),
            tileLeft, tileTop, tileWidth, tileHeight)
        result += TerminalImageTileDraw(tile, copy(
            x = x + (left - sourceX) / sourceWidth * width, y = y + (top - sourceY) / sourceHeight * height,
            width = (right - left) / sourceWidth * width, height = (bottom - top) / sourceHeight * height,
            sourceX = left - tileLeft, sourceY = top - tileTop, sourceWidth = right - left, sourceHeight = bottom - top))
    }
    return result
}

/** Convert only one bounded tile; never retain a second full RGBA copy of an RGB image. */
internal fun TerminalGraphicsPixels.tileBytes(tile: TerminalImageTile, premultiplied: Boolean): ByteArray {
    require(tile.left >= 0 && tile.top >= 0 && tile.width > 0 && tile.height > 0 &&
        tile.left.toLong() + tile.width <= width && tile.top.toLong() + tile.height <= height)
    val bytes = ByteArray(tile.width * tile.height * 4)
    val channels = if (format == 24) 3 else 4
    for (y in 0 until tile.height) for (x in 0 until tile.width) {
        val source = ((tile.top + y) * width + tile.left + x) * channels
        val target = (y * tile.width + x) * 4
        val alpha = if (channels == 3) 255 else data[source + 3].toInt() and 255
        for (channel in 0..2) bytes[target + channel] = if (premultiplied)
            (((data[source + channel].toInt() and 255) * alpha + 127) / 255).toByte() else data[source + channel]
        bytes[target + 3] = alpha.toByte()
    }
    return bytes
}

/** Byte-bounded LRU ownership, pruned to the visible frame and released by the owning renderer. */
internal class TerminalImageCache<T>(private val limit: Long, private val release: (T) -> Unit) {
    private data class Entry<T>(val value: T, val bytes: Long)
    private val entries = linkedMapOf<TerminalImageTileKey, Entry<T>>()
    private val used = mutableSetOf<TerminalImageTileKey>()
    var bytes: Long = 0
        private set
    val size: Int get() = entries.size
    init { require(limit > 0) }
    fun beginFrame() { used.clear() }
    fun get(tile: TerminalImageTile, create: () -> T): T {
        val key = tile.key
        used += key
        entries.remove(key)?.let { entries[key] = it; return it.value }
        val cost = tile.width.toLong() * tile.height * 4
        require(cost <= limit) { "Image tile exceeds the renderer cache budget" }
        while (bytes + cost > limit) remove(entries.keys.first())
        val value = create()
        entries[key] = Entry(value, cost); bytes += cost
        return value
    }
    fun endFrame() { entries.keys.filter { it !in used }.forEach(::remove); used.clear() }
    private fun remove(key: TerminalImageTileKey) { entries.remove(key)?.let { bytes -= it.bytes; release(it.value) } }
    fun clear() { entries.keys.toList().forEach(::remove); used.clear() }
}
