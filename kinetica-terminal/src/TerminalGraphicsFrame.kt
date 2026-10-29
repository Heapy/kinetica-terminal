package io.heapy.kinetica.terminal

/** Only immutable pixel content crosses into a completed render frame. Storage/animation state
 * must never be consulted again by a renderer, including during synchronized-output holds. */
internal data class TerminalGraphicsImageContent(val id: Long, val pixels: TerminalGraphicsPixels) {
    // Frame selection can return to the same immutable buffer and reuse its cached texture.
    // This is independent of the image-generation counter used for transfer identity checks.
    val generation: Any get() = pixels
}

/** Immutable placement snapshot; decoded pixels are owned by their immutable content generation. */
internal data class TerminalGraphicsFrameEntry(
    val image: TerminalGraphicsImageContent,
    val geometry: TerminalGraphicsGeometry,
    val column: Int,
    val row: Long,
    val z: Int,
    val layer: TerminalGraphicsLayer,
)

internal fun TerminalGraphicsState.captureFrame(scrollOffset: Int, columns: Int, rows: Int, cell: TerminalCellSize?,
    visibleLine: ((Int) -> TerminalLine)? = null): List<TerminalGraphicsFrameEntry> {
    val result = mutableListOf<TerminalGraphicsFrameEntry>()
    val virtualOrigins = mutableMapOf<TerminalGraphicsPlacementKey, TerminalPosition>()
    val defaults = mutableMapOf<Long, TerminalGraphicsPlacement>()
    for (placement in placements) if (placement.virtual) {
        val previous = defaults[placement.key.image]
        if (previous == null || (placement.key.external && !previous.key.external) ||
            (placement.key.external == previous.key.external && placement.key.id < previous.key.id)) defaults[placement.key.image] = placement
    }
    if (defaults.isNotEmpty() && visibleLine != null) for (y in 0 until rows) {
        for (run in terminalGraphicsUnicodeRuns(visibleLine(y), y)) {
            val target = if (run.placement == 0L) defaults[run.image]
                else placement(TerminalGraphicsPlacementKey(run.image, run.placement, true))
            if (target == null) continue
            val old = virtualOrigins[target.key]
            virtualOrigins[target.key] = TerminalPosition(minOf(old?.x ?: run.x, run.x), minOf(old?.y ?: (y - scrollOffset), y - scrollOffset), false)
            val geometry = run.geometry(target.image.pixels, target.geometry, cell) ?: continue
            if (geometry.pixels.width == 0L || geometry.pixels.height == 0L) continue
            result += TerminalGraphicsFrameEntry(target.image.renderContent, geometry, run.x, y.toLong(), -1, TerminalGraphicsLayer.BELOW_TEXT)
        }
    }
    for (placement in placements) {
        if (placement.virtual) continue
        val origin = position(placement, virtualOrigins) ?: continue
        val image = placement.image
        val geometry = placement.geometry.resolve(image.pixels.width, image.pixels.height, cell)
        val row = origin.y.toLong() + scrollOffset
        if (geometry.source.width == 0L || geometry.source.height == 0L || geometry.pixels.width == 0L || geometry.pixels.height == 0L ||
            geometry.grid.width == 0L || geometry.grid.height == 0L || row >= rows || row + geometry.grid.height <= 0 ||
            origin.x >= columns || origin.x.toLong() + geometry.grid.width <= 0) continue
        result += TerminalGraphicsFrameEntry(image.renderContent, geometry, origin.x, row, placement.geometry.z, placement.geometry.layer)
    }
    return result.sortedWith(compareBy({ it.z }, { it.image.id }))
}

/** Clip before converting to GPU floats, including extreme relative offsets and uint32 sizes. */
internal data class TerminalImageQuad(
    val image: TerminalGraphicsImageContent,
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

internal data class TerminalImageTileKey(val generation: Any, val x: Int, val y: Int, val width: Int = 0, val height: Int = 0,
    val sampleX: Int? = null, val sampleY: Int? = null)
internal data class TerminalImageTile(val key: TerminalImageTileKey, val left: Int, val top: Int, val width: Int, val height: Int)
internal data class TerminalImageTileDraw(val tile: TerminalImageTile, val quad: TerminalImageQuad)

/** Tiles have one source-pixel gutter so linear sampling does not introduce seams. */
internal fun TerminalImageQuad.tiles(side: Int = 1024): List<TerminalImageTileDraw> {
    require(side in 4..2048)
    // Ghostty rounds placeholder source spans independently from destination spans. A zero
    // source span still samples a texel boundary with linear filtering. Bake that one axis
    // into a one-pixel strip so Canvas and Core Graphics can draw it as well as GPUs can.
    if (sourceWidth == 0.0 || sourceHeight == 0.0) {
        val sx = if (sourceWidth == 0.0) sourceX.toInt() else null
        val sy = if (sourceHeight == 0.0) sourceY.toInt() else null
        val px = sx?.coerceIn(0, image.pixels.width - 1)
        val py = sy?.coerceIn(0, image.pixels.height - 1)
        return copy(sourceX = px?.toDouble() ?: sourceX, sourceY = py?.toDouble() ?: sourceY,
            sourceWidth = if (sx != null) 1.0 else sourceWidth, sourceHeight = if (sy != null) 1.0 else sourceHeight,
            cropX = px ?: cropX, cropY = py ?: cropY,
            cropWidth = if (sx != null) 1 else cropWidth, cropHeight = if (sy != null) 1 else cropHeight).tiles(side).map {
                it.copy(tile = it.tile.copy(key = it.tile.key.copy(sampleX = sx, sampleY = sy)))
            }
    }
    val stride = side - 2
    val firstX = (sourceX / stride).toInt(); val firstY = (sourceY / stride).toInt()
    val lastX = (kotlin.math.ceil((sourceX + sourceWidth) / stride).toInt() - 1).coerceAtLeast(firstX)
    val lastY = (kotlin.math.ceil((sourceY + sourceHeight) / stride).toInt() - 1).coerceAtLeast(firstY)
    val result = mutableListOf<TerminalImageTileDraw>()
    for (row in firstY..lastY) for (column in firstX..lastX) {
        val innerX = column * stride; val innerY = row * stride
        val left = maxOf(sourceX, innerX.toDouble()); val top = maxOf(sourceY, innerY.toDouble())
        val right = minOf(sourceX + sourceWidth, (innerX + stride).toDouble())
        val bottom = minOf(sourceY + sourceHeight, (innerY + stride).toDouble())
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
        tile.width <= 2048 && tile.height <= 2048 &&
        tile.left.toLong() + tile.width <= width.toLong() + 1 && tile.top.toLong() + tile.height <= height.toLong() + 1)
    val bytes = ByteArray(tile.width * tile.height * 4)
    val channels = if (format == 24) 3 else 4
    for (y in 0 until tile.height) for (x in 0 until tile.width) {
        // Independently rounded placeholder source coordinates can extend one pixel beyond
        // the image. Duplicate that edge pixel, matching a clamp-to-edge GPU sampler.
        val source = ((tile.top + y).coerceAtMost(height - 1) * width + (tile.left + x).coerceAtMost(width - 1)) * channels
        val target = (y * tile.width + x) * 4
        if (tile.key.sampleX != null || tile.key.sampleY != null) {
            val pointX = tile.key.sampleX; val pointY = tile.key.sampleY
            val nx = if (pointX == null) 1 else 2; val ny = if (pointY == null) 1 else 2
            var alphaSum = 0
            val sums = IntArray(3)
            for (dy in 0 until ny) for (dx in 0 until nx) {
                val sampleX = (if (pointX == null) tile.left + x else pointX - 1 + dx).coerceIn(0,width - 1)
                val sampleY = (if (pointY == null) tile.top + y else pointY - 1 + dy).coerceIn(0,height - 1)
                val offset = (sampleY * width + sampleX) * channels
                val alpha = if (channels == 3) 255 else data[offset + 3].toInt() and 255
                alphaSum += alpha
                for (c in 0..2) sums[c] += (data[offset + c].toInt() and 255) * alpha
            }
            val count = nx * ny
            for (c in 0..2) bytes[target + c] = (if (premultiplied) (sums[c] + count * 127) / (count * 255)
                else if (alphaSum == 0) 0 else (sums[c] + alphaSum / 2) / alphaSum).toByte()
            bytes[target + 3] = ((alphaSum + count / 2) / count).toByte()
            continue
        }
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
