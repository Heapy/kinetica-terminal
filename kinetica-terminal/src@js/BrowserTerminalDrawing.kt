package io.heapy.kinetica.terminal

import kotlin.math.ceil
import kotlin.math.round

internal abstract class BrowserTerminalDrawing {
    protected var fonts: BrowserTerminalFontSet? = null
    open fun configureFonts(value: BrowserTerminalFontSet?) { fonts = value }
    open val shaping: Boolean get() = false
    val canvas: dynamic = js("globalThis.document.createElement('canvas')")
    abstract val name: String
    open val glyphCount: Int get() = 0
    open val rasterizations: Int get() = 0
    open val drawCalls: Int get() = 0
    open val imageCacheBytes: Long get() = 0
    open val imageTileCount: Int get() = 0
    open val imageUploads: Long get() = 0
    abstract fun setVisible(visible: Boolean)
    abstract fun resize(width: Double, height: Double, ratio: Double, fontSize: Double, cellWidth: Double, cellHeight: Double, fontFamily: String? = null)
    abstract fun draw(viewport: TerminalViewport)
    abstract fun dispose()
}

internal fun browserFont(size: Double, style: Int = 0, family: String? = null): String {
    val prefix = family?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\", " }.orEmpty()
    return "${if (style and TerminalStyle.ITALIC != 0) "italic " else ""}${if (style and TerminalStyle.BOLD != 0) "bold " else ""}${size}px ${prefix}ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
}
internal fun terminalCssColor(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0')

private fun resizeBrowserCanvas(canvas: dynamic, width: Int, height: Int) {
    // Each assignment resets the backing bitmap independently. Shrink first so
    // changing aspect ratio never requests the new width at the old, larger height.
    if (height < (canvas.height as Number).toInt()) canvas.height = height
    if (canvas.width != width) canvas.width = width
    if (canvas.height != height) canvas.height = height
}

internal fun createBrowserTerminalDrawing(mode: TerminalRendering, invalidate: () -> Unit,
    probe: TerminalRenderProbe = TerminalRenderProbe(null)): BrowserTerminalDrawing {
    if (mode == TerminalRendering.SOFTWARE) return CanvasTerminalDrawing(probe)
    val gpu = WebGlTerminalDrawing(invalidate, probe)
    try { gpu.initialize(); return gpu }
    catch (error: Exception) {
        gpu.dispose()
        if (mode == TerminalRendering.GPU) throw error
        return CanvasTerminalDrawing(probe)
    }
}

internal class CanvasTerminalDrawing(private val probe: TerminalRenderProbe = TerminalRenderProbe(null)) : BrowserTerminalDrawing() {
    override val name = "canvas2d"
    private val context: dynamic = canvas.getContext("2d", js("({alpha:false})"))
    private fun withOpacity(opacity: Double, draw: () -> Unit) {
        val previous = context.globalAlpha as Double
        context.globalAlpha = previous * opacity
        try { draw() } finally { context.globalAlpha = previous }
    }
    private var width = 0.0; private var height = 0.0; private var ratio = 0.0
    private var size = 14.0; private var cellWidth = 0.0; private var cellHeight = 0.0
    private var family: String? = null
    private var clear = true
    private var suspended = false
    private var imagesVisible = false
    private val imageFrame = TerminalGpuFrame()
    private var contrastGlyphs: BrowserSoftwareGlyphs? = null
    private fun clearContrast() { contrastGlyphs?.dispose(); contrastGlyphs = null }
    override fun configureFonts(value: BrowserTerminalFontSet?) {
        if (fonts === value) return
        clearContrast(); super.configureFonts(value); clear = true
    }
    private val uploadBytes = TERMINAL_IMAGE_TILE_SIDE.toLong() * TERMINAL_IMAGE_TILE_SIDE * 4
    private var uploadData: dynamic = null
    // One reusable ImageData plus all cached canvas pixel payloads share the 64 MiB budget.
    private val imageCache = TerminalImageCache<dynamic>(64L * 1024 * 1024 - uploadBytes) { it.width = 0; it.height = 0 }
    override val imageCacheBytes: Long get() = imageCache.bytes + if (uploadData == null) 0 else uploadBytes
    override val imageTileCount: Int get() = imageCache.size
    override var imageUploads: Long = 0
        private set
    override fun setVisible(visible: Boolean) {
        if (suspended == !visible) return
        suspended = !visible
        if (suspended) {
            imageCache.clear(); uploadData = null; clearContrast()
            canvas.width = 0; canvas.height = 0
        }
        clear = true
    }
    override fun resize(width: Double, height: Double, ratio: Double, fontSize: Double, cellWidth: Double, cellHeight: Double, fontFamily: String?) {
        if (this.ratio != ratio || size != fontSize || family != fontFamily || this.cellWidth != cellWidth || this.cellHeight != cellHeight) {
            clearContrast(); clear = true
        }
        if (this.width != width || this.height != height || this.ratio != ratio || size != fontSize || family != fontFamily ||
            !suspended && (canvas.width != (width * ratio).toInt() || canvas.height != (height * ratio).toInt())) {
            family = fontFamily
            this.width = width; this.height = height; this.ratio = ratio; size = fontSize
            canvas.style.width = "${width}px"; canvas.style.height = "${height}px"
            if (!suspended) {
                resizeBrowserCanvas(canvas, (width * ratio).toInt(), (height * ratio).toInt())
                context.setTransform(ratio, 0, 0, ratio, 0, 0)
            }
            clear = true
        }
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
    }
    override fun draw(viewport: TerminalViewport) {
        if (suspended) return
        val measurement = probe.begin(viewport, name)
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, ratio, viewport.columns, viewport.rows) }
        if (viewport.minimumContrast <= 1.0 && contrastGlyphs != null) { clearContrast(); viewport.markAll() }
        if (images.isNotEmpty() || viewport.minimumContrast > 1.0) {
            if (!imagesVisible || clear) viewport.markAll()
            imagesVisible = true
            drawImagesAndText(viewport, images)
            measurement?.submitted(0)
            return
        }
        if (imagesVisible) { imagesVisible = false; imageCache.clear(); uploadData = null; clear = true }
        // A full viewport change can alter the default color or shrink the grid without
        // resizing this backing canvas. Repaint its margins too, using the held theme.
        val fullRepaint = viewport.firstDirtyRow == 0 && viewport.lastDirtyRow == viewport.rows - 1
        if (clear || fullRepaint) {
            context.fillStyle = terminalCssColor(viewport.theme.background); context.fillRect(0, 0, width, height)
            if (clear) viewport.markAll()
            clear = false
        }
        context.textBaseline = "top"
        viewport.paint(object : TerminalPainter {
            override fun withOpacity(opacity: Double, draw: () -> Unit) = this@CanvasTerminalDrawing.withOpacity(opacity, draw)
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
                val left = round(x * ratio) / ratio
                val top = round(y * ratio) / ratio
                context.fillStyle = terminalCssColor(color)
                context.fillRect(left, top, terminalPixelSpan(x, width, ratio), terminalPixelSpan(y, height, ratio))
            }
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {
                context.font = fonts?.cssFont(size, style, family) ?: browserFont(size, style, family)
                context.fillStyle = terminalCssColor(color); context.fillText(value, x, y + 1)
            }
        }, cellWidth, cellHeight)
        measurement?.submitted(0)
    }
    private fun drawImagesAndText(viewport: TerminalViewport, images: List<TerminalImageQuad>) {
        val glyphs = if (viewport.minimumContrast > 1.0) contrastGlyphs ?: BrowserSoftwareGlyphs(fonts, family, size, ratio, cellWidth, cellHeight).also {
            contrastGlyphs = it; viewport.markAll()
        } else null
        val frame = if (glyphs == null) imageFrame.also { it.update(viewport, cellWidth, cellHeight) }
            else glyphs.frame.also { glyphs.prepare(viewport) }
        context.fillStyle = terminalCssColor(viewport.theme.background); context.fillRect(0, 0, width, height)
        context.textBaseline = "top"
        imageCache.beginFrame()
        fun layer(layer: TerminalGraphicsLayer) {
            for (image in images) if (image.layer == layer) for (draw in image.tiles()) {
                val bitmap = imageCache.get(draw.tile) {
                    val result: dynamic = js("globalThis.document.createElement('canvas')")
                    try {
                        result.width = draw.tile.width; result.height = draw.tile.height
                        val ctx = result.getContext("2d")
                        if (uploadData == null) uploadData = ctx.createImageData(TERMINAL_IMAGE_TILE_SIDE, TERMINAL_IMAGE_TILE_SIDE)
                        val bytes = uploadData.data
                        image.image.pixels.writeTile(draw.tile, premultiplied = false, rowStride = TERMINAL_IMAGE_TILE_SIDE * 4) {
                            index, value -> bytes[index] = value.toInt() and 255
                        }
                        ctx.putImageData(uploadData, 0, 0, 0, 0, draw.tile.width, draw.tile.height); imageUploads++
                        result
                    } catch (error: Throwable) {
                        result.width = 0; result.height = 0
                        throw error
                    }
                }
                val q = draw.quad
                context.drawImage(bitmap, q.sourceX, q.sourceY, q.sourceWidth, q.sourceHeight, q.x, q.y, q.width, q.height)
            }
        }
        fun fill(rect: TerminalRect) = withOpacity(rect.opacity) {
            val left = round(rect.x * ratio) / ratio; val top = round(rect.y * ratio) / ratio
            context.fillStyle = terminalCssColor(rect.color)
            context.fillRect(left, top, terminalPixelSpan(rect.x, rect.width, ratio), terminalPixelSpan(rect.y, rect.height, ratio))
        }
        layer(TerminalGraphicsLayer.BELOW_BACKGROUND)
        for (row in frame.rows) for (rect in row.backgrounds) if (!rect.defaultBackground) fill(rect)
        layer(TerminalGraphicsLayer.BELOW_TEXT)
        for (row in frame.rows) for (rect in row.decorations) if (rect.behindText) fill(rect)
        if (glyphs != null) glyphs.draw(context) else for (row in frame.rows) for (glyph in row.glyphs) {
            withOpacity(glyph.opacity) {
                context.font = fonts?.cssFont(size, glyph.key.style, family) ?: browserFont(size, glyph.key.style, family)
                context.fillStyle = terminalCssColor(glyph.color)
                context.fillText(glyph.key.text, glyph.x, glyph.y + 1)
            }
        }
        for (row in frame.rows) for (rect in row.decorations) if (!rect.behindText) fill(rect)
        layer(TerminalGraphicsLayer.ABOVE_TEXT)
        imageCache.endFrame()
        clear = false
    }
    override fun dispose() { clearContrast(); imageCache.clear(); uploadData = null; fonts = null; canvas.width = 0; canvas.height = 0 }
}

/** WebGL2 owns presentation; a detached 2D canvas only rasterizes cache misses. */
private class WebGlTerminalDrawing(private val invalidate: () -> Unit, private val probe: TerminalRenderProbe) : BrowserTerminalDrawing() {
    override val name = "webgl2"
    private val gl: dynamic = canvas.getContext("webgl2", js("({alpha:false,antialias:false,depth:false,stencil:false,premultipliedAlpha:true})"))
    private val raster: dynamic = js("globalThis.document.createElement('canvas')")
    private val rasterContext: dynamic = raster.getContext("2d", js("({willReadFrequently:true})"))
    private val atlas = TerminalGlyphAtlas()
    private var frame = TerminalGpuFrame()
    private var shaper: BrowserTerminalShaper? = null
    override val shaping: Boolean get() = shaper != null
    override fun configureFonts(value: BrowserTerminalFontSet?) {
        if (fonts === value) return
        resetShaping(); super.configureFonts(value)
        atlas.clear(); resetTextures(); repaint = true
    }
    private fun resetShaping() {
        frame = TerminalGpuFrame()
        shaper?.dispose(); shaper = null
    }
    private val batches = TerminalGpuBatches()
    private val textures = mutableListOf<dynamic>()
    private val imageCache = TerminalImageCache<dynamic>(64L * 1024 * 1024,
        drain = { if (!lost) gl.finish() }) { if (gl != null && !lost) gl.deleteTexture(it) }
    private val imageInstance = TerminalInstances()
    override val imageCacheBytes: Long get() = imageCache.bytes
    override val imageTileCount: Int get() = imageCache.size
    override var imageUploads: Long = 0
        private set
    private var program: dynamic = null
    private var buffer: dynamic = null
    private var vao: dynamic = null
    private var viewportUniform: dynamic = null
    private var texturedUniform: dynamic = null
    private var width = 0.0; private var height = 0.0; private var ratio = 0.0
    private var size = 14.0; private var cellWidth = 0.0; private var cellHeight = 0.0
    private var family: String? = null
    private var lost = false; private var disposed = false; private var repaint = true
    private var maxSurfaceWidth = 0; private var maxSurfaceHeight = 0
    private var suspended = false
    private var restoreError: Exception? = null
    private var gpuTimer: BrowserGpuTimer? = null
    override val glyphCount: Int get() = atlas.entryCount
    override var rasterizations: Int = 0
        private set
    override var drawCalls: Int = 0
        private set
    private val onLost: (dynamic) -> Unit = {
        event -> event.preventDefault(); lost = true; imageCache.clear(); imageCache.completed(); gpuTimer?.contextLost(); gpuTimer = null
        resetShaping(); invalidate()
    }
    private val onRestored: (dynamic) -> Unit = {
        if (!disposed) {
            lost = false; textures.clear(); atlas.clear()
            program = null; buffer = null; vao = null
            try { refreshSurfaceLimits(); if (!suspended) initializeResources(); repaint = true } catch (error: Exception) { restoreError = error }
            invalidate()
        }
    }

    override fun setVisible(visible: Boolean) {
        if (disposed || suspended == !visible) return
        suspended = !visible
        if (suspended) releaseResources()
        repaint = true
    }

    fun initialize() {
        check(gl != null) { "WebGL2 is unavailable" }
        check((gl.getParameter(gl.MAX_TEXTURE_SIZE) as Number).toInt() >= atlas.size)
        refreshSurfaceLimits()
        initializeResources()
        canvas.addEventListener("webglcontextlost", onLost)
        canvas.addEventListener("webglcontextrestored", onRestored)
    }

    private fun refreshSurfaceLimits() {
        val buffer = (gl.getParameter(gl.MAX_RENDERBUFFER_SIZE) as Number).toInt()
        val viewport = gl.getParameter(gl.MAX_VIEWPORT_DIMS)
        maxSurfaceWidth = minOf(buffer, (viewport[0] as Number).toInt())
        maxSurfaceHeight = minOf(buffer, (viewport[1] as Number).toInt())
        check(maxSurfaceWidth > 0 && maxSurfaceHeight > 0) { "WebGL surface limits are unavailable" }
    }

    private fun surfaceCssSize(width: Int, height: Int, ratio: Double) {
        // A smaller actual drawing buffer must be clipped at its physical size,
        // like the native Metal surface, rather than stretched by the compositor.
        canvas.style.width = "${width.toDouble() / ratio}px"
        canvas.style.height = "${height.toDouble() / ratio}px"
    }

    private fun shader(type: dynamic, source: String): dynamic {
        val shader = gl.createShader(type)
        gl.shaderSource(shader, source); gl.compileShader(shader)
        if (gl.getShaderParameter(shader, gl.COMPILE_STATUS) != true) {
            val message = gl.getShaderInfoLog(shader).toString(); gl.deleteShader(shader)
            error("Terminal shader compilation failed: $message")
        }
        return shader
    }

    private fun initializeResources() {
        gpuTimer = probe.observer?.let { BrowserGpuTimer(gl, it) }
        val vertex = shader(gl.VERTEX_SHADER, VERTEX)
        val fragment = try { shader(gl.FRAGMENT_SHADER, FRAGMENT) } catch (error: Exception) { gl.deleteShader(vertex); throw error }
        program = gl.createProgram()
        gl.attachShader(program, vertex); gl.attachShader(program, fragment); gl.linkProgram(program)
        gl.deleteShader(vertex); gl.deleteShader(fragment)
        check(gl.getProgramParameter(program, gl.LINK_STATUS) == true) { "Terminal shader link failed: ${gl.getProgramInfoLog(program)}" }
        vao = gl.createVertexArray(); buffer = gl.createBuffer()
        gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        for (attribute in 0..2) {
            gl.enableVertexAttribArray(attribute)
            gl.vertexAttribPointer(attribute, 4, gl.FLOAT, false, 48, attribute * 16)
            gl.vertexAttribDivisor(attribute, 1)
        }
        gl.useProgram(program)
        viewportUniform = gl.getUniformLocation(program, "viewportSize")
        texturedUniform = gl.getUniformLocation(program, "textured")
        gl.uniform1i(gl.getUniformLocation(program, "atlas"), 0)
        gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA)
        gl.disable(gl.DEPTH_TEST)
        // A complete white texture is bound even for untextured draws (WebGL sampler validation).
        createTexture()
    }

    override fun resize(width: Double, height: Double, ratio: Double, fontSize: Double, cellWidth: Double, cellHeight: Double, fontFamily: String?) {
        val pixelWidth = (width * ratio).toInt().coerceIn(0, maxSurfaceWidth)
        val pixelHeight = (height * ratio).toInt().coerceIn(0, maxSurfaceHeight)
        if (this.width != width || this.height != height || this.ratio != ratio ||
            !suspended && (canvas.width != pixelWidth || canvas.height != pixelHeight)) {
            if (!suspended) resizeBrowserCanvas(canvas, pixelWidth, pixelHeight)
            surfaceCssSize(pixelWidth, pixelHeight, ratio)
            repaint = true
        }
        if (this.ratio != ratio || size != fontSize || family != fontFamily || this.cellWidth != cellWidth || this.cellHeight != cellHeight) {
            resetShaping(); atlas.clear(); resetTextures(); repaint = true
        }
        this.width = width; this.height = height; this.ratio = ratio; size = fontSize
        family = fontFamily
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
    }

    override fun draw(viewport: TerminalViewport) {
        restoreError?.let { throw it }
        if (lost || disposed || suspended || width <= 0 || height <= 0) return
        if (program == null) initializeResources()
        // The browser may allocate less than requested even within reported GPU limits.
        val pixelWidth = (gl.drawingBufferWidth as Number).toInt()
        val pixelHeight = (gl.drawingBufferHeight as Number).toInt()
        surfaceCssSize(pixelWidth, pixelHeight, ratio)
        if (shaper == null) fonts?.createShaper(family, size * ratio)?.let {
            shaper = it; frame = TerminalGpuFrame(it::resolve, it::shape); repaint = true
        }
        val measurement = probe.begin(viewport, name)
        if (repaint) { viewport.markAll(); repaint = false }
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, ratio, viewport.columns, viewport.rows) }
        frame.update(viewport, cellWidth, cellHeight)
        measurement?.let { it.layout = it.checkpoint() }
        atlas.prepare(frame.keys, ::rasterize, ::resetTextures, ::upload)
        measurement?.let { it.atlas = it.checkpoint() }
        batches.build(frame, atlas, ratio, transparentDefaultBackground = images.isNotEmpty())
        measurement?.let { it.batch = it.checkpoint() }
        val query = measurement?.let { gpuTimer?.begin(it.id) }
        gl.viewport(0, 0, pixelWidth, pixelHeight)
        val color = viewport.theme.background
        gl.clearColor((color shr 16 and 255) / 255.0, (color shr 8 and 255) / 255.0, (color and 255) / 255.0, 1)
        gl.clear(gl.COLOR_BUFFER_BIT)
        gl.useProgram(program); gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        gl.uniform2f(viewportUniform, pixelWidth.toDouble() / ratio, pixelHeight.toDouble() / ratio)
        gl.activeTexture(gl.TEXTURE0)
        drawCalls = 0
        imageCache.beginFrame()
        drawImages(images, TerminalGraphicsLayer.BELOW_BACKGROUND)
        submit(batches.backgrounds, false, 0)
        drawImages(images, TerminalGraphicsLayer.BELOW_TEXT)
        submit(batches.underlays, false, 0)
        for (page in batches.pages.indices) submit(batches.pages[page], true, page)
        submit(batches.decorations, false, 0)
        drawImages(images, TerminalGraphicsLayer.ABOVE_TEXT)
        imageCache.endFrame()
        // No future image draw needs these resources. Complete queued sampling before release.
        if (images.isEmpty() && imageCache.bytes > 0) { gl.finish(); imageCache.completed() }
        gpuTimer?.end(query)
        measurement?.submitted(drawCalls)
    }

    private fun submit(instances: TerminalInstances, textured: Boolean, page: Int) {
        if (instances.count == 0) return
        gl.bindTexture(gl.TEXTURE_2D, textures[page])
        gl.uniform1i(texturedUniform, if (textured) 1 else 0)
        gl.bufferData(gl.ARRAY_BUFFER, instances.data.asDynamic().subarray(0, instances.floatCount), gl.STREAM_DRAW)
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, instances.count)
        drawCalls++
    }

    private fun drawImages(images: List<TerminalImageQuad>, layer: TerminalGraphicsLayer) {
        for (image in images) if (image.layer == layer) for (draw in image.tiles()) {
            val tile = draw.tile
            val texture = imageCache.get(tile) {
                val texture = gl.createTexture()
                try {
                    gl.bindTexture(gl.TEXTURE_2D, texture)
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
                    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
                    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
                    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, tile.width, tile.height, 0, gl.RGBA, gl.UNSIGNED_BYTE,
                        unsignedBytes(image.image.pixels.tileBytes(tile, premultiplied = true)))
                    check((gl.getError() as Number).toInt() == 0) { "Unable to upload terminal image tile" }
                    imageUploads++
                    texture
                } catch (error: Exception) { gl.deleteTexture(texture); throw error }
            }
            val q = draw.quad
            imageInstance.clear()
            imageInstance.add(q.x, q.y, q.width, q.height, 0xffffff,
                (q.sourceX / tile.width).toFloat(), (q.sourceY / tile.height).toFloat(),
                ((q.sourceX + q.sourceWidth) / tile.width).toFloat(), ((q.sourceY + q.sourceHeight) / tile.height).toFloat())
            gl.bindTexture(gl.TEXTURE_2D, texture); gl.uniform1i(texturedUniform, 1)
            gl.bufferData(gl.ARRAY_BUFFER, imageInstance.data.asDynamic().subarray(0, imageInstance.floatCount), gl.STREAM_DRAW)
            gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 1)
            drawCalls++
        }
    }

    private fun createTexture() {
        val texture = gl.createTexture(); textures.add(texture)
        gl.bindTexture(gl.TEXTURE_2D, texture)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, atlas.size, atlas.size, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    }
    private fun resetTextures() {
        if (gl == null || lost) { textures.clear(); return }
        textures.forEach { gl.deleteTexture(it) }; textures.clear()
        if (!suspended && program != null) createTexture()
    }
    private fun upload(entry: AtlasGlyph, bitmap: GlyphBitmap) {
        while (textures.size <= entry.page) createTexture()
        gl.bindTexture(gl.TEXTURE_2D, textures[entry.page])
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
        gl.texSubImage2D(gl.TEXTURE_2D, 0, entry.x, entry.y, bitmap.width, bitmap.height, gl.RGBA, gl.UNSIGNED_BYTE, unsignedBytes(bitmap.rgba))
    }
    private fun rasterize(key: GlyphKey): GlyphBitmap {
        if (key.font >= 0) {
            val bitmap = checkNotNull(shaper).rasterize(key, raster, rasterContext, cellWidth * ratio, cellHeight * ratio, atlas.size - 2)
            rasterizations++
            return bitmap
        }
        rasterizations++
        return browserRasterizeGlyph(key, raster, rasterContext, cellWidth, cellHeight, ratio, size, family)
    }

    private fun releaseResources() {
        imageCache.clear()
        // Finish glyph draws as well as image sampling before releasing their storage.
        if (gl != null && !lost) gl.finish()
        imageCache.completed()
        gpuTimer?.dispose(); gpuTimer = null
        if (gl != null && !lost) {
            gl.useProgram(null); gl.bindVertexArray(null); gl.bindBuffer(gl.ARRAY_BUFFER, null); gl.bindTexture(gl.TEXTURE_2D, null)
            textures.forEach { gl.deleteTexture(it) }; gl.deleteBuffer(buffer); gl.deleteVertexArray(vao); gl.deleteProgram(program)
        }
        program = null; buffer = null; vao = null; viewportUniform = null; texturedUniform = null
        textures.clear(); atlas.clear(); resetShaping()
        raster.width = 0; raster.height = 0; canvas.width = 0; canvas.height = 0
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        releaseResources()
        fonts = null
        canvas.removeEventListener("webglcontextlost", onLost); canvas.removeEventListener("webglcontextrestored", onRestored)
    }

    companion object {
        private const val VERTEX = """#version 300 es
layout(location=0) in vec4 rect;
layout(location=1) in vec4 uvRect;
layout(location=2) in vec4 tint;
uniform vec2 viewportSize;
out vec2 uv;
out vec4 color;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 position = rect.xy + corner * rect.zw;
    gl_Position = vec4(position.x / viewportSize.x * 2.0 - 1.0, 1.0 - position.y / viewportSize.y * 2.0, 0, 1);
    uv = mix(uvRect.xy, uvRect.zw, corner); color = tint;
}
"""
        private const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D atlas;
uniform bool textured;
in vec2 uv;
in vec4 color;
out vec4 outputColor;
void main() { outputColor = textured ? texture(atlas, uv) * color : color; }
"""
    }
}

private fun unsignedBytes(bytes: ByteArray): dynamic = js("new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)")
