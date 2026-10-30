package io.heapy.kinetica.terminal

import kotlin.js.Promise
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Presentation offered by this face, used for Unicode defaults and VS15/VS16. */
public enum class BrowserTerminalFontPresentation { TEXT, EMOJI }

/** A host-supplied SFNT face (.ttf/.otf). The input bytes are copied on construction.
 * Supply separate faces for bold/italic; missing styles fall back to regular.
 * Families are searched in supplied order after the configured primary family. */
public class BrowserTerminalFont(
    public val family: String,
    data: ByteArray,
    public val style: Int = 0,
    public val presentation: BrowserTerminalFontPresentation = BrowserTerminalFontPresentation.TEXT,
) {
    init {
        require(family.isNotBlank() && family.length <= 256 && family.none { it.code < 32 })
        require(style and (TerminalStyle.BOLD or TerminalStyle.ITALIC).inv() == 0)
        require(data.size in 1..32 * 1024 * 1024)
    }
    internal val bytes = data.copyOf()
}

/** Owns one WASM runtime and the CSS faces needed by measurement, IME and Canvas fallback. */
internal class BrowserTerminalFontSet private constructor(
    sources: List<BrowserTerminalFont>, private val runtime: dynamic,
) {
    private var sources = sources
    private val aliases = sources.map { it.family }.distinct().associateWith { "kinetica-terminal-${nextAlias++}" }
    private val cssFaces = mutableListOf<dynamic>()
    private var disposed = false
    val fontCount: Int get() = (runtime.fontCount as Number).toInt()
    val fontBytes: Int get() = (runtime.fontBytes as Number).toInt()
    val memoryBytes: Int get() = (runtime.memoryBytes as Number).toInt()

    fun primary(family: String?): String? = if (family == null)
        sources.first { it.style == 0 && it.presentation == BrowserTerminalFontPresentation.TEXT }.family
        else family.takeIf { it in aliases }

    fun cssFont(size: Double, style: Int, family: String?): String {
        val primary = primary(family) ?: return browserFont(size, style, family)
        val names = listOf(primary) + aliases.keys.filter { it != primary }
        val prefix = "${if (style and TerminalStyle.ITALIC != 0) "italic " else ""}${if (style and TerminalStyle.BOLD != 0) "bold " else ""}"
        return "$prefix${size}px " + names.joinToString(", ") { "\"${aliases.getValue(it)}\"" } + ", monospace"
    }

    fun createShaper(family: String?, pixels: Double): BrowserTerminalShaper? {
        check(!disposed)
        val primary = primary(family) ?: return null
        val faces = mutableListOf<dynamic>()
        try {
            for (source in sources) faces.add(runtime.createFont(browserFontBytes(source.bytes), pixels))
            return BrowserTerminalShaper(sources, faces, primary)
        } catch (error: Throwable) {
            faces.forEach { it.dispose() }
            throw error
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val fonts: dynamic = js("globalThis.document.fonts")
        cssFaces.forEach { fonts.delete(it) }; cssFaces.clear()
        runtime.dispose()
        sources = emptyList()
    }

    private fun loadCss(isCurrent: () -> Boolean): Promise<BrowserTerminalFontSet> {
        val loads = sources.map { source ->
            val face = newBrowserFontFace(aliases.getValue(source.family), browserFontBytes(source.bytes),
                if (source.style and TerminalStyle.ITALIC != 0) "italic" else "normal",
                if (source.style and TerminalStyle.BOLD != 0) "700" else "400")
            cssFaces.add(face)
            face.load().unsafeCast<Promise<dynamic>>()
        }
        return Promise.all(loads.toTypedArray()).then {
            check(isCurrent()) { "Terminal font load was superseded" }
            val fonts: dynamic = js("globalThis.document.fonts")
            cssFaces.forEach { fonts.add(it) }
            this
        }
    }

    companion object {
        private var nextAlias = 0
        fun load(moduleUrl: String, sources: List<BrowserTerminalFont>, isCurrent: () -> Boolean): Promise<BrowserTerminalFontSet> {
            val snapshot = sources.toList()
            require(snapshot.isNotEmpty() && snapshot.size <= 64)
            require(snapshot.sumOf { it.bytes.size.toLong() } <= 64L * 1024 * 1024)
            require(snapshot.any { it.style == 0 && it.presentation == BrowserTerminalFontPresentation.TEXT })
            require(snapshot.distinctBy { it.family to it.style }.size == snapshot.size)
            var pending: BrowserTerminalFontSet? = null
            // Dynamic chaining preserves promise flattening at the JS boundary.
            return importBrowserShaper(moduleUrl).then { module: dynamic ->
                check(isCurrent()) { "Terminal font load was superseded" }
                module.createTerminalHarfBuzz()
            }
                .then { runtime: dynamic ->
                    if (!isCurrent()) { runtime.dispose(); error("Terminal font load was superseded") }
                    val set = BrowserTerminalFontSet(snapshot, runtime)
                    pending = set
                    // Validate SFNT files before registering any faces with the document.
                    set.createShaper(null, 14.0)?.dispose()
                    set.loadCss(isCurrent)
                }.catch { error: dynamic -> pending?.dispose(); throw error }
                .unsafeCast<Promise<BrowserTerminalFontSet>>()
        }
    }
}

/** Only one shaper per drawing surface. Face IDs remain valid until its atlas is reset. */
internal class BrowserTerminalShaper(
    private val sources: List<BrowserTerminalFont>, private val faces: List<dynamic>, private val primary: String,
) {
    private val resolved = linkedMapOf<Pair<String, Int>, TerminalResolvedFontCell>()
    private var disposed = false

    fun resolve(text: String, style: Int): TerminalResolvedFontCell {
        check(!disposed)
        require(text.isNotEmpty())
        val key = text to (style and (TerminalStyle.BOLD or TerminalStyle.ITALIC))
        resolved[key]?.let { return it }
        val result = resolveFace(text, key.second)?.let { TerminalResolvedFontCell(it, text) }
            ?: resolveFace("�", key.second)?.let { TerminalResolvedFontCell(it, "�") }
            ?: TerminalResolvedFontCell(checkNotNull(resolveFace(" ", key.second)) { "Terminal fonts must contain a space" }, " ")
        if (resolved.size == 512) resolved.remove(resolved.keys.first())
        resolved[key] = result
        return result
    }

    private fun resolveFace(text: String, style: Int): Int? {
        val codes = text.terminalCodepoints()
        val emoji = when {
            0xfe0e in codes -> false
            0xfe0f in codes -> true
            else -> terminalEmojiPresentation(codes.first())
        }
        val presentation = if (emoji) BrowserTerminalFontPresentation.EMOJI else BrowserTerminalFontPresentation.TEXT
        val ordered = sources.indices.sortedBy { if (sources[it].family == primary) 0 else 1 }
        fun supports(index: Int): Boolean = codes.all { code ->
            code in 0xfe00..0xfe0f || code in 0xe0100..0xe01ef || code == 0x200c || code == 0x200d ||
                faces[index].nominalGlyph(code) != null
        }
        // Exact style/presentation, then regular with the same presentation, then
        // any regular face. Every candidate must cover the entire grapheme.
        for (requestedStyle in listOf(style, 0).distinct()) {
            ordered.firstOrNull { sources[it].style == requestedStyle && sources[it].presentation == presentation && supports(it) }
                ?.let { return it }
        }
        return ordered.firstOrNull { sources[it].style == 0 && supports(it) }
    }

    fun shape(run: TerminalFontRun): List<TerminalShapedGlyph> {
        check(!disposed)
        val values: dynamic = faces[run.font].shape(run.input.text)
        var x = 0; var y = 0
        val raw = ArrayList<TerminalRawGlyph>((values.length as Number).toInt())
        for (i in 0 until (values.length as Number).toInt()) {
            val value = values[i]
            // Original Ghostty HarfBuzz rounds each 26.6 advance/offset separately.
            fun pixel(value: dynamic): Int = ((value as Number).toInt() + 32) shr 6
            val advance = pixel(value.xAdvance)
            raw += TerminalRawGlyph(run.font, (value.glyph as Number).toInt(), (value.index as Number).toInt(),
                advance.toDouble(), (x + pixel(value.xOffset)).toDouble(), (y + pixel(value.yOffset)).toDouble())
            x += advance; y += pixel(value.yAdvance)
        }
        return terminalPositionGlyphs(run.input, raw)
    }

    fun rasterize(key: GlyphKey, canvas: dynamic, context: dynamic, cellHeight: Double, maxSize: Int): GlyphBitmap {
        check(!disposed)
        val face = faces[key.font]
        val extents: dynamic = face.extents(key.glyph)
        if (extents == null) return GlyphBitmap(1, 1, ByteArray(4), false)
        fun coordinate(value: dynamic) = (value as Number).toDouble() / 64
        val x = coordinate(extents.xBearing); val y = coordinate(extents.yBearing)
        val w = coordinate(extents.width); val h = coordinate(extents.height)
        if (w == 0.0 || h == 0.0) return GlyphBitmap(1, 1, ByteArray(4), false)
        val left = floor(minOf(x, x + w)).toInt() - 1
        val right = ceil(maxOf(x, x + w)).toInt() + 1
        val bottom = floor(minOf(y, y + h)).toInt() - 1
        val top = ceil(maxOf(y, y + h)).toInt() + 1
        val width = right - left; val height = top - bottom
        check(width in 1..maxSize && height in 1..maxSize) { "Browser glyph exceeds the bounded atlas" }
        val primaryIndex = sources.indexOfFirst { it.family == primary && it.style == 0 }.takeIf { it >= 0 } ?: 0
        val metrics: dynamic = faces[primaryIndex].horizontalExtents()
        val baseline = if (metrics == null) cellHeight * 0.8 else
            (cellHeight + coordinate(metrics.ascender) + coordinate(metrics.descender)) / 2
        canvas.width = width; canvas.height = height
        val png: dynamic = face.colorPng(key.glyph)
        if (png != null) {
            val bytes = ByteArray((png.length as Number).toInt()) { (png[it] as Number).toByte() }
            val image = decodeTerminalGraphicsPng(bytes, TerminalGraphicsDecodeLimits(
                maxEncodedBytes = 4 * 1024 * 1024, maxDecodedBytes = 4 * 1024 * 1024, maxDimension = 1024))
            val source: dynamic = js("globalThis.document.createElement('canvas')")
            try {
                source.width = image.width; source.height = image.height
                val ctx = source.getContext("2d")
                val data = ctx.createImageData(image.width, image.height)
                for (i in image.data.indices) data.data[i] = image.data[i].toInt() and 255
                ctx.putImageData(data, 0, 0)
                context.drawImage(source, x - left, top - y, w, -h)
            } finally { source.width = 0; source.height = 0 }
        } else {
            val outline: dynamic = face.outline(key.glyph)
            context.setTransform(1.0 / 64, 0, 0, -1.0 / 64, -left, top)
            context.fillStyle = "white"; context.beginPath()
            var i = 0
            fun next(): Double = (outline[i++] as Number).toDouble()
            while (i < (outline.length as Number).toInt()) when (next().toInt()) {
                0 -> context.moveTo(next(), next())
                1 -> context.lineTo(next(), next())
                2 -> context.quadraticCurveTo(next(), next(), next(), next())
                3 -> context.bezierCurveTo(next(), next(), next(), next(), next(), next())
                4 -> context.closePath()
                else -> error("Invalid HarfBuzz outline")
            }
            context.fill()
        }
        val pixels: dynamic = context.getImageData(0, 0, width, height).data
        return glyphBitmap(width, height, ByteArray(width * height * 4) { (pixels[it] as Number).toByte() })
            .copy(left = left, top = baseline.roundToInt() - top)
    }

    fun dispose() {
        if (disposed) return
        disposed = true; resolved.clear(); faces.forEach { it.dispose() }
    }
}

private fun browserFontBytes(bytes: ByteArray): dynamic = js("new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)")
private fun importBrowserShaper(url: String): dynamic = js("import(new URL(url, globalThis.document.baseURI).href)")
private fun newBrowserFontFace(family: String, bytes: dynamic, style: String, weight: String): dynamic =
    js("new FontFace(family, bytes, {style, weight})")
