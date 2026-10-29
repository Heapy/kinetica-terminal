package io.heapy.kinetica.terminal

import kotlin.math.ceil
import kotlin.math.round

internal abstract class BrowserTerminalDrawing {
    val canvas: dynamic = js("globalThis.document.createElement('canvas')")
    abstract val name: String
    open val glyphCount: Int get() = 0
    open val rasterizations: Int get() = 0
    open val drawCalls: Int get() = 0
    open val imageCacheBytes: Long get() = 0
    open val imageTileCount: Int get() = 0
    open val imageUploads: Long get() = 0
    abstract fun resize(width: Double, height: Double, ratio: Double, fontSize: Double, cellWidth: Double, cellHeight: Double, fontFamily: String? = null)
    abstract fun draw(viewport: TerminalViewport)
    abstract fun dispose()
}

internal fun browserFont(size: Double, style: Int = 0, family: String? = null): String {
    val prefix = family?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\", " }.orEmpty()
    return "${if (style and TerminalStyle.ITALIC != 0) "italic " else ""}${if (style and TerminalStyle.BOLD != 0) "bold " else ""}${size}px ${prefix}ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
}
internal fun terminalCssColor(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0')

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
    private var width = 0.0; private var height = 0.0; private var ratio = 0.0
    private var size = 14.0; private var cellWidth = 0.0; private var cellHeight = 0.0
    private var family: String? = null
    private var clear = true
    private var imagesVisible = false
    private val imageFrame = TerminalGpuFrame()
    private val imageCache = TerminalImageCache<dynamic>(64L * 1024 * 1024) { it.width = 0; it.height = 0 }
    override val imageCacheBytes: Long get() = imageCache.bytes
    override val imageTileCount: Int get() = imageCache.size
    override var imageUploads: Long = 0
        private set
    override fun resize(width: Double, height: Double, ratio: Double, fontSize: Double, cellWidth: Double, cellHeight: Double, fontFamily: String?) {
        if (this.width != width || this.height != height || this.ratio != ratio || size != fontSize || family != fontFamily) {
            family = fontFamily
            this.width = width; this.height = height; this.ratio = ratio; size = fontSize
            canvas.width = (width * ratio).toInt(); canvas.height = (height * ratio).toInt()
            canvas.style.width = "${width}px"; canvas.style.height = "${height}px"
            context.setTransform(ratio, 0, 0, ratio, 0, 0); clear = true
        }
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
    }
    override fun draw(viewport: TerminalViewport) {
        val measurement = probe.begin(viewport, name)
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, ratio, viewport.columns, viewport.rows) }
        if (images.isNotEmpty()) {
            if (!imagesVisible) viewport.markAll()
            imagesVisible = true
            drawImagesAndText(viewport, images)
            measurement?.submitted(0)
            return
        }
        if (imagesVisible) { imagesVisible = false; imageCache.clear(); clear = true }
        if (clear) {
            context.fillStyle = terminalCssColor(viewport.theme.background); context.fillRect(0, 0, width, height)
            viewport.markAll(); clear = false
        }
        context.textBaseline = "top"
        viewport.paint(object : TerminalPainter {
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
                val left = round(x * ratio) / ratio
                val top = round(y * ratio) / ratio
                context.fillStyle = terminalCssColor(color)
                context.fillRect(left, top, round((x + width) * ratio) / ratio - left,
                    round((y + height) * ratio) / ratio - top)
            }
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {
                context.font = browserFont(size, style, family); context.fillStyle = terminalCssColor(color); context.fillText(value, x, y + 1)
            }
        }, cellWidth, cellHeight)
        measurement?.submitted(0)
    }
    private fun drawImagesAndText(viewport: TerminalViewport, images: List<TerminalImageQuad>) {
        imageFrame.update(viewport, cellWidth, cellHeight)
        context.fillStyle = terminalCssColor(viewport.theme.background); context.fillRect(0, 0, width, height)
        context.textBaseline = "top"
        imageCache.beginFrame()
        fun layer(layer: TerminalGraphicsLayer) {
            for (image in images) if (image.layer == layer) for (draw in image.tiles()) {
                val bitmap = imageCache.get(draw.tile) {
                    val result: dynamic = js("globalThis.document.createElement('canvas')")
                    result.width = draw.tile.width; result.height = draw.tile.height
                    val ctx = result.getContext("2d")
                    val data = ctx.createImageData(draw.tile.width, draw.tile.height)
                    data.data.set(unsignedBytes(image.image.pixels.tileBytes(draw.tile, premultiplied = false)))
                    ctx.putImageData(data, 0, 0); imageUploads++
                    result
                }
                val q = draw.quad
                context.drawImage(bitmap, q.sourceX, q.sourceY, q.sourceWidth, q.sourceHeight, q.x, q.y, q.width, q.height)
            }
        }
        fun fill(rect: TerminalRect) {
            val left = round(rect.x * ratio) / ratio; val top = round(rect.y * ratio) / ratio
            context.fillStyle = terminalCssColor(rect.color)
            context.fillRect(left, top, round((rect.x + rect.width) * ratio) / ratio - left,
                round((rect.y + rect.height) * ratio) / ratio - top)
        }
        layer(TerminalGraphicsLayer.BELOW_BACKGROUND)
        for (row in imageFrame.rows) for (rect in row.backgrounds) if (!rect.defaultBackground) fill(rect)
        layer(TerminalGraphicsLayer.BELOW_TEXT)
        for (row in imageFrame.rows) for (glyph in row.glyphs) {
            context.font = browserFont(size, glyph.key.style, family); context.fillStyle = terminalCssColor(glyph.color)
            context.fillText(glyph.key.text, glyph.x, glyph.y + 1)
        }
        for (row in imageFrame.rows) for (rect in row.decorations) fill(rect)
        layer(TerminalGraphicsLayer.ABOVE_TEXT)
        imageCache.endFrame()
        clear = false
    }
    override fun dispose() { imageCache.clear(); canvas.width = 0; canvas.height = 0 }
}

/** WebGL2 owns presentation; a detached 2D canvas only rasterizes cache misses. */
private class WebGlTerminalDrawing(private val invalidate: () -> Unit, private val probe: TerminalRenderProbe) : BrowserTerminalDrawing() {
    override val name = "webgl2"
    private val gl: dynamic = canvas.getContext("webgl2", js("({alpha:false,antialias:false,depth:false,stencil:false,premultipliedAlpha:true})"))
    private val raster: dynamic = js("globalThis.document.createElement('canvas')")
    private val rasterContext: dynamic = raster.getContext("2d", js("({willReadFrequently:true})"))
    private val atlas = TerminalGlyphAtlas()
    private val frame = TerminalGpuFrame()
    private val batches = TerminalGpuBatches()
    private val textures = mutableListOf<dynamic>()
    private val imageCache = TerminalImageCache<dynamic>(64L * 1024 * 1024) { if (gl != null && !lost) gl.deleteTexture(it) }
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
    private var restoreError: Exception? = null
    private var gpuTimer: BrowserGpuTimer? = null
    override val glyphCount: Int get() = atlas.entryCount
    override var rasterizations: Int = 0
        private set
    override var drawCalls: Int = 0
        private set
    private val onLost: (dynamic) -> Unit = {
        event -> event.preventDefault(); lost = true; imageCache.clear(); gpuTimer?.contextLost(); gpuTimer = null; invalidate()
    }
    private val onRestored: (dynamic) -> Unit = {
        if (!disposed) {
            lost = false; textures.clear(); atlas.clear()
            try { initializeResources(); repaint = true } catch (error: Exception) { restoreError = error }
            invalidate()
        }
    }

    fun initialize() {
        check(gl != null) { "WebGL2 is unavailable" }
        check((gl.getParameter(gl.MAX_TEXTURE_SIZE) as Number).toInt() >= atlas.size)
        initializeResources()
        canvas.addEventListener("webglcontextlost", onLost)
        canvas.addEventListener("webglcontextrestored", onRestored)
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
        if (this.width != width || this.height != height || this.ratio != ratio) {
            canvas.width = (width * ratio).toInt(); canvas.height = (height * ratio).toInt()
            // Keep both WebGL and the CSS compositor at one device pixel per texel.
            canvas.style.width = "${(canvas.width as Number).toDouble() / ratio}px"
            canvas.style.height = "${(canvas.height as Number).toDouble() / ratio}px"; repaint = true
        }
        if (this.ratio != ratio || size != fontSize || family != fontFamily || this.cellWidth != cellWidth || this.cellHeight != cellHeight) {
            atlas.clear(); resetTextures(); repaint = true
        }
        this.width = width; this.height = height; this.ratio = ratio; size = fontSize
        family = fontFamily
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
    }

    override fun draw(viewport: TerminalViewport) {
        restoreError?.let { throw it }
        if (lost || disposed || width <= 0 || height <= 0) return
        val measurement = probe.begin(viewport, name)
        if (repaint) { viewport.markAll(); repaint = false }
        frame.update(viewport, cellWidth, cellHeight)
        measurement?.let { it.layout = it.checkpoint() }
        atlas.prepare(frame.keys, ::rasterize, ::resetTextures, ::upload)
        measurement?.let { it.atlas = it.checkpoint() }
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, ratio, viewport.columns, viewport.rows) }
        batches.build(frame, atlas, ratio, transparentDefaultBackground = images.isNotEmpty())
        measurement?.let { it.batch = it.checkpoint() }
        val query = measurement?.let { gpuTimer?.begin(it.id) }
        gl.viewport(0, 0, canvas.width, canvas.height)
        val color = viewport.theme.background
        gl.clearColor((color shr 16 and 255) / 255.0, (color shr 8 and 255) / 255.0, (color and 255) / 255.0, 1)
        gl.clear(gl.COLOR_BUFFER_BIT)
        gl.useProgram(program); gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
        gl.uniform2f(viewportUniform, (canvas.width as Number).toDouble() / ratio, (canvas.height as Number).toDouble() / ratio)
        gl.activeTexture(gl.TEXTURE0)
        drawCalls = 0
        imageCache.beginFrame()
        drawImages(images, TerminalGraphicsLayer.BELOW_BACKGROUND)
        submit(batches.backgrounds, false, 0)
        drawImages(images, TerminalGraphicsLayer.BELOW_TEXT)
        for (page in batches.pages.indices) submit(batches.pages[page], true, page)
        submit(batches.decorations, false, 0)
        drawImages(images, TerminalGraphicsLayer.ABOVE_TEXT)
        imageCache.endFrame()
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
        textures.forEach { gl.deleteTexture(it) }; textures.clear(); createTexture()
    }
    private fun upload(entry: AtlasGlyph, bitmap: GlyphBitmap) {
        while (textures.size <= entry.page) createTexture()
        gl.bindTexture(gl.TEXTURE_2D, textures[entry.page])
        gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1)
        gl.texSubImage2D(gl.TEXTURE_2D, 0, entry.x, entry.y, bitmap.width, bitmap.height, gl.RGBA, gl.UNSIGNED_BYTE, unsignedBytes(bitmap.rgba))
    }
    private fun rasterize(key: GlyphKey): GlyphBitmap {
        val width = ceil(cellWidth * key.columns * ratio).toInt().coerceAtLeast(1)
        val height = ceil(cellHeight * ratio).toInt().coerceAtLeast(1)
        raster.width = width; raster.height = height
        rasterContext.setTransform(ratio, 0, 0, ratio, 0, 0)
        rasterContext.font = browserFont(size, key.style, family); rasterContext.textBaseline = "top"; rasterContext.fillStyle = "white"
        rasterContext.fillText(key.text, 0, 1)
        val pixels: dynamic = rasterContext.getImageData(0, 0, width, height).data
        val bytes = ByteArray(width * height * 4) { (pixels[it] as Number).toByte() }
        rasterizations++
        return glyphBitmap(width, height, bytes)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        imageCache.clear()
        gpuTimer?.dispose(); gpuTimer = null
        canvas.removeEventListener("webglcontextlost", onLost); canvas.removeEventListener("webglcontextrestored", onRestored)
        if (gl != null && !lost) {
            textures.forEach { gl.deleteTexture(it) }; gl.deleteBuffer(buffer); gl.deleteVertexArray(vao); gl.deleteProgram(program)
        }
        textures.clear(); atlas.clear(); raster.width = 0; raster.height = 0; canvas.width = 0; canvas.height = 0
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
