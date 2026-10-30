@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import platform.Metal.*
import platform.QuartzCore.*
import platform.posix.memcpy
import kotlin.math.ceil
import kotlin.math.roundToInt

/** All mutable state is main-thread confined. One asynchronous frame owns the reusable buffers. */
internal class MetalTerminalDrawing(private val probe: TerminalRenderProbe = TerminalRenderProbe(null),
    imageBudget: Long = 64L * 1024 * 1024,
    private val fontProvider: (String?, Double, Int) -> NSFont = ::terminalNativeFont,
    private val invalidate: () -> Unit) {
    private val device = checkNotNull(MTLCreateSystemDefaultDevice()) { "Metal is unavailable" }
    private val queue = checkNotNull(device.newCommandQueue())
    val layer = CAMetalLayer().apply {
        this.device = this@MetalTerminalDrawing.device as objcnames.protocols.MTLDeviceProtocol
        pixelFormat = MTLPixelFormatBGRA8Unorm
        framebufferOnly = true
        opaque = true
        geometryFlipped = true // IME sublayer coordinates use the same top-left origin as the grid.
        // Live resize can change the layer bounds before the next drawable is ready.
        // Keep the previous frame at its original pixel size instead of stretching its glyphs.
        // Gravity is inverted vertically by geometryFlipped: bottomLeft pins screen top-left.
        contentsGravity = kCAGravityBottomLeft
        masksToBounds = true
        allowsNextDrawableTimeout = true
    }
    private val pipeline: MTLRenderPipelineStateProtocol = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val library = device.newLibraryWithSource(SHADER, null, error.ptr)
            ?: error("Terminal Metal shader: ${error.value?.localizedDescription}")
        val descriptor = MTLRenderPipelineDescriptor().apply {
            vertexFunction = library.newFunctionWithName("terminalVertex")
            fragmentFunction = library.newFunctionWithName("terminalFragment")
            colorAttachments.objectAtIndexedSubscript(0u).apply {
                pixelFormat = MTLPixelFormatBGRA8Unorm
                blendingEnabled = true
                sourceRGBBlendFactor = MTLBlendFactorOne
                destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha
                sourceAlphaBlendFactor = MTLBlendFactorOne
                destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha
            }
        }
        device.newRenderPipelineStateWithDescriptor(descriptor, error.ptr)
            ?: error("Terminal Metal pipeline: ${error.value?.localizedDescription}")
    }
    private val atlas = TerminalGlyphAtlas()
    private val shapers = mutableMapOf<Int, TerminalNativeShaper>()
    private fun shaper(style: Int): TerminalNativeShaper = shapers.getOrPut(style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)) {
        TerminalNativeShaper(fontProvider(fontFamily, fontSize * scale, style and (TerminalStyle.BOLD or TerminalStyle.ITALIC)))
    }
    private fun clearShapers() { shapers.values.forEach { it.dispose() }; shapers.clear() }
    internal val shapingFontCount: Int get() = shapers.values.sumOf { it.fontCount }
    private val frame = TerminalGpuFrame({ text, style -> shaper(style).resolve(text) }) { run ->
        val current = shaper(run.style)
        current.shape(run.input, run.font).map { if (current.isEmojiFont(it.font)) it.copy(emoji = true) else it }
    }
    private val batches = TerminalGpuBatches()
    private val textures = mutableListOf<MTLTextureProtocol>()
    private var drainImages: (() -> Unit)? = null
    private val imageCache = TerminalImageCache<MTLTextureProtocol>(imageBudget,
        drain = { checkNotNull(drainImages).invoke() }) { it.setPurgeableState(MTLPurgeableStateEmpty) }
    var imageBudgetDrains: Int = 0
        private set
    private var drainedGpuMillis: Double? = 0.0
    private val imageInstance = TerminalInstances()
    val imageCacheBytes: Long get() = imageCache.bytes
    val imageTileCount: Int get() = imageCache.size
    var imageUploads: Int = 0
        private set
    private val buffers = mutableListOf<MTLBufferProtocol>()
    private var busy = false
    private var pending = false
    private var disposed = false
    private var visible = true
    private var releasePending = false
    private var presentationSequence = 0L
    private var lastPresentation = 0L
    private var presentationRetry: NSTimer? = null
    internal val glyphTextureCount: Int get() = textures.size
    internal val vertexBufferBytes: Long get() = buffers.sumOf { it.length.toLong() }
    private var resetAtlas = true
    private var failure: String? = null
    private var background: Int? = null
    private var width = 0.0; private var height = 0.0; private var scale = 1.0
    private var submittedWidth = 0.0; private var submittedHeight = 0.0; private var submittedScale = 0.0
    private var fontSize = 14.0; private var cellWidth = 0.0; private var cellHeight = 0.0
    private var fontFamily: String? = null
    val glyphCount: Int get() = atlas.entryCount
    var rasterizations: Int = 0
        private set
    var drawCalls: Int = 0
        private set
    var submittedFrames: Int = 0
        private set

    fun setVisible(visible: Boolean) {
        if (disposed || this.visible == visible) return
        this.visible = visible
        if (!visible) {
            cancelPresentationRetry()
            pending = false; releasePending = true
            imageCache.clear()
            if (!busy) releaseResources()
        } else {
            layer.device = device as objcnames.protocols.MTLDeviceProtocol
            layer.drawableSize = NSMakeSize(ceil(width * scale), ceil(height * scale))
            resetAtlas = true
        }
    }

    private fun cancelPresentationRetry() {
        presentationRetry?.invalidate(); presentationRetry = null
    }

    private fun releaseResources() {
        check(!busy)
        releasePending = false
        imageCache.clear(); imageCache.completed()
        textures.forEach { it.setPurgeableState(MTLPurgeableStateEmpty) }; textures.clear()
        buffers.forEach { it.setPurgeableState(MTLPurgeableStateEmpty) }; buffers.clear()
        atlas.clear(); clearShapers(); resetAtlas = true
        if (!visible || disposed) {
            // CAMetalLayer owns its contents. Assigning null disables drawable presentation
            // on macOS even after its device and drawable size are restored.
            // CAMetalLayer ignores a zero drawable size; shrink its pool before detaching the device.
            layer.drawableSize = NSMakeSize(1.0, 1.0)
            layer.device = null
        }
    }

    fun resize(width: Double, height: Double, scale: Double, fontSize: Double, cellWidth: Double, cellHeight: Double,
        managesLayerFrame: Boolean = true, fontFamily: String? = null) {
        if (this.scale != scale || this.fontSize != fontSize || this.fontFamily != fontFamily || this.cellWidth != cellWidth || this.cellHeight != cellHeight) resetAtlas = true
        this.fontFamily = fontFamily
        this.width = width; this.height = height; this.scale = scale; this.fontSize = fontSize
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
        CATransaction.begin(); CATransaction.setDisableActions(true)
        if (managesLayerFrame) layer.frame = NSMakeRect(0.0, 0.0, width, height)
        layer.contentsScale = scale
        if (visible) layer.drawableSize = NSMakeSize(ceil(width * scale), ceil(height * scale))
        CATransaction.commit()
    }

    fun draw(viewport: TerminalViewport, synchronizePresentation: Boolean = false) {
        failure?.let { error(it) }
        if (disposed || !visible || width <= 0 || height <= 0) return
        if (background != viewport.theme.background) {
            background = viewport.theme.background
            val rgb = viewport.theme.background
            CATransaction.begin(); CATransaction.setDisableActions(true)
            // Gravity keeps an older, smaller drawable at its original size. Newly
            // exposed pixels must use the terminal background, not the host window's.
            layer.backgroundColor = NSColor.colorWithSRGBRed((rgb shr 16 and 255) / 255.0,
                (rgb shr 8 and 255) / 255.0, (rgb and 255) / 255.0, 1.0).CGColor
            CATransaction.commit()
        }
        if (busy) { pending = true; return }
        // A differently sized drawable must become visible with its layer geometry.
        // Async presentation can otherwise land a frame before/after the bounds change,
        // briefly offsetting or clipping text even with non-scaling contents gravity.
        val transactional = synchronizePresentation || width != submittedWidth || height != submittedHeight || scale != submittedScale
        layer.presentsWithTransaction = transactional
        val measurement = probe.begin(viewport, "metal")
        val drawable = layer.nextDrawable() ?: return
        measurement?.let { it.acquire = it.checkpoint() }
        val images = prepare(viewport, measurement)
        val command = encode(drawable.texture as MTLTextureProtocol, viewport.theme.background, images)
        val priorGpuMillis = drainedGpuMillis
        if (!transactional) command.presentDrawable(drawable as MTLDrawableProtocol)
        val sequence = ++presentationSequence
        drawable.addPresentedHandler { presented ->
            val time = presented?.presentedTime ?: 0.0
            NSOperationQueue.mainQueue.addOperationWithBlock {
                if (!disposed) {
                    if (sequence > lastPresentation) {
                        lastPresentation = sequence
                        cancelPresentationRetry()
                        if (time == 0.0 && visible) {
                            // Window attachment can drop a drawable. An idle shell may never
                            // invalidate again; retry on the next frame without spinning the
                            // main queue while Core Animation is still committing the window.
                            presentationRetry = NSTimer.timerWithTimeInterval(0.016, repeats = false) {
                                presentationRetry = null
                                if (!disposed && visible) invalidate()
                            }.also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
                        }
                    }
                    if (measurement != null) probe.observer?.onFramePresented(measurement.id, time)
                }
            }
        }
        busy = true
        command.addCompletedHandler { completed ->
            val message = completed?.error?.localizedDescription
            val gpuTime = if (measurement != null && message == null && completed != null && priorGpuMillis != null)
                gpuMillis(completed)?.plus(priorGpuMillis) else null
            NSOperationQueue.mainQueue.addOperationWithBlock {
                busy = false
                imageCache.completed()
                if (releasePending) releaseResources()
                if (!disposed) {
                    if (message != null) failure = "Terminal Metal frame failed: $message"
                    if (visible && (pending || failure != null)) { pending = false; invalidate() }
                    if (measurement != null) probe.observer?.onGpuTime(measurement.id, gpuTime)
                }
            }
        }
        command.commit()
        if (transactional) {
            // Only resize waits for scheduling (not completion). Present in the current
            // Core Animation transaction; regular terminal output stays asynchronous.
            command.waitUntilScheduled()
            drawable.present()
        }
        submittedWidth = width; submittedHeight = height; submittedScale = scale
        submittedFrames++
        measurement?.submitted(drawCalls)
    }

    private fun prepare(viewport: TerminalViewport, measurement: TerminalFrameMeasurement? = null): List<TerminalImageQuad> {
        if (resetAtlas) {
            atlas.clear(); textures.clear(); clearShapers()
            viewport.markAll(); resetAtlas = false
        }
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, scale, viewport.columns, viewport.rows) }
        frame.update(viewport, cellWidth, cellHeight)
        measurement?.let { it.layout = it.checkpoint() }
        atlas.prepare(frame.keys, ::rasterize, { textures.clear() }, ::upload)
        // Bind a valid texture even to the untextured branch of the fragment shader.
        if (textures.isEmpty()) createTexture()
        measurement?.let { it.atlas = it.checkpoint() }
        batches.build(frame, atlas, scale, transparentDefaultBackground = images.isNotEmpty())
        measurement?.let { it.batch = it.checkpoint() }
        return images
    }

    private fun encode(target: MTLTextureProtocol, background: Int, images: List<TerminalImageQuad>): MTLCommandBufferProtocol {
        drainedGpuMillis = 0.0
        var command = checkNotNull(queue.commandBuffer())
        val pass = MTLRenderPassDescriptor()
        pass.colorAttachments.objectAtIndexedSubscript(0u).apply {
            texture = target
            loadAction = MTLLoadActionClear; storeAction = MTLStoreActionStore
            clearColor = MTLClearColorMake((background shr 16 and 255) / 255.0,
                (background shr 8 and 255) / 255.0, (background and 255) / 255.0, 1.0)
        }
        // drawableSize is rounded to whole pixels; using the unrounded view bounds
        // here would scale every glyph again when the window has a fractional size.
        val viewport = floatArrayOf((target.width.toDouble() / scale).toFloat(), (target.height.toDouble() / scale).toFloat())
        fun beginEncoder(): MTLRenderCommandEncoderProtocol = checkNotNull(command.renderCommandEncoderWithDescriptor(pass)).also { encoder ->
            encoder.setRenderPipelineState(pipeline)
            viewport.usePinned { encoder.setVertexBytes(it.addressOf(0), 8u, 1u) }
        }
        var encoder = beginEncoder()
        // A command buffer retains every sampled tile, even after LRU eviction. Finish the
        // current bounded batch before freeing its pixels; subsequent passes load the target.
        drainImages = {
            encoder.endEncoding(); command.commit(); command.waitUntilCompleted()
            check(command.error == null) { command.error?.localizedDescription.orEmpty() }
            val elapsed = gpuMillis(command)
            drainedGpuMillis = drainedGpuMillis?.let { before -> elapsed?.let { before + it } }
            imageBudgetDrains++
            command = checkNotNull(queue.commandBuffer())
            pass.colorAttachments.objectAtIndexedSubscript(0u).loadAction = MTLLoadActionLoad
            encoder = beginEncoder()
        }
        drawCalls = 0
        imageCache.beginFrame()
        try {
            drawImages({ encoder }, images, TerminalGraphicsLayer.BELOW_BACKGROUND)
            submit(encoder, batches.backgrounds, false, 0, 0)
            drawImages({ encoder }, images, TerminalGraphicsLayer.BELOW_TEXT)
            for (page in batches.pages.indices) submit(encoder, batches.pages[page], true, page, page + 1)
            submit(encoder, batches.decorations, false, 0, batches.pages.size + 1)
            drawImages({ encoder }, images, TerminalGraphicsLayer.ABOVE_TEXT)
            imageCache.endFrame()
            encoder.endEncoding()
            return command
        } finally { drainImages = null }
    }

    private fun gpuMillis(command: MTLCommandBufferProtocol): Double? =
        if (command.GPUStartTime > 0.0) ((command.GPUEndTime - command.GPUStartTime) * 1000).takeIf { it >= 0.0 && it.isFinite() }
        else null

    private fun drawImages(currentEncoder: () -> MTLRenderCommandEncoderProtocol, images: List<TerminalImageQuad>, layer: TerminalGraphicsLayer) {
        for (image in images) if (image.layer == layer) for (draw in image.tiles()) {
            val tile = draw.tile
            val texture = imageCache.get(tile) {
                val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatRGBA8Unorm,
                    tile.width.toULong(), tile.height.toULong(), false)
                descriptor.storageMode = MTLStorageModeShared; descriptor.usage = MTLTextureUsageShaderRead
                val texture = checkNotNull(device.newTextureWithDescriptor(descriptor))
                image.image.pixels.tileBytes(tile, premultiplied = true).usePinned {
                    texture.replaceRegion(MTLRegionMake2D(0u, 0u, tile.width.toULong(), tile.height.toULong()),
                        0u, it.addressOf(0), (tile.width * 4).toULong())
                }
                imageUploads++
                texture
            }
            val encoder = currentEncoder()
            val q = draw.quad
            imageInstance.clear()
            imageInstance.add(q.x, q.y, q.width, q.height, 0xffffff,
                (q.sourceX / tile.width).toFloat(), (q.sourceY / tile.height).toFloat(),
                ((q.sourceX + q.sourceWidth) / tile.width).toFloat(), ((q.sourceY + q.sourceHeight) / tile.height).toFloat())
            // Metal copies these small bytes into command-owned storage. Reusing a mutable
            // shared buffer here would make earlier image draws see the final placement.
            imageInstance.data.usePinned { encoder.setVertexBytes(it.addressOf(0), 48u, 0u) }
            encoder.setFragmentTexture(texture, 0u)
            memScoped {
                val flag = alloc<UIntVar>(); flag.value = 2u
                encoder.setFragmentBytes(flag.ptr, 4u, 0u)
            }
            encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, 0u, 4u, 1u)
            drawCalls++
        }
    }

    private fun submit(encoder: MTLRenderCommandEncoderProtocol, instances: TerminalInstances, textured: Boolean, page: Int, slot: Int) {
        if (instances.count == 0) return
        val length = instances.floatCount.toULong() * 4u
        while (buffers.size <= slot) buffers += checkNotNull(device.newBufferWithLength(4096u, MTLResourceStorageModeShared))
        if (buffers[slot].length < length) buffers[slot] = checkNotNull(device.newBufferWithLength(length * 2u, MTLResourceStorageModeShared))
        val buffer = buffers[slot]
        instances.data.usePinned { memcpy(buffer.contents(), it.addressOf(0), length) }
        encoder.setVertexBuffer(buffer, 0u, 0u)
        encoder.setFragmentTexture(textures[page], 0u)
        memScoped {
            val flag = alloc<UIntVar>(); flag.value = if (textured) 1u else 0u
            encoder.setFragmentBytes(flag.ptr, 4u, 0u)
        }
        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, 0u, 4u, instances.count.toULong())
        drawCalls++
    }

    private fun createTexture() {
        val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatRGBA8Unorm,
            atlas.size.toULong(), atlas.size.toULong(), false)
        descriptor.storageMode = MTLStorageModeShared; descriptor.usage = MTLTextureUsageShaderRead
        textures += checkNotNull(device.newTextureWithDescriptor(descriptor))
    }
    private fun upload(entry: AtlasGlyph, bitmap: GlyphBitmap) {
        // prepare() runs only after the preceding command completes: no CPU/GPU texture races.
        while (textures.size <= entry.page) createTexture()
        bitmap.rgba.usePinned {
            textures[entry.page].replaceRegion(MTLRegionMake2D(entry.x.toULong(), entry.y.toULong(), bitmap.width.toULong(), bitmap.height.toULong()),
                0u, it.addressOf(0), (bitmap.width * 4).toULong())
        }
    }

    private fun rasterize(key: GlyphKey): GlyphBitmap {
        val font = fontProvider(fontFamily, fontSize * scale, 0)
        val baseline = (cellHeight * scale + font.ascender + font.descender) / 2.0
        val faceHeight = font.ascender - font.descender + font.leading
        val metrics = TerminalGlyphMetrics(cellWidth * scale, cellHeight * scale, cellWidth * scale, faceHeight,
            cellHeight * scale - baseline.roundToInt() - (font.leading / 2 - font.descender),
            faceHeight, (2 * font.capHeight + faceHeight) / 3)
        val bitmap = terminalRasterizeNativeGlyph(shaper(key.style).face(key.font), key.glyph, baseline, atlas.size - 2,
            key.constraint, metrics, key.columns)
        rasterizations++
        return bitmap
    }

    /** Readback for native pixel tests only; normal presentation only waits under image-budget pressure. */
    internal fun snapshot(viewport: TerminalViewport): ByteArray {
        check(!busy && !disposed && visible)
        val images = prepare(viewport)
        val pixelWidth = ceil(width * scale).toInt(); val pixelHeight = ceil(height * scale).toInt()
        val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm,
            pixelWidth.toULong(), pixelHeight.toULong(), false)
        descriptor.storageMode = MTLStorageModeShared; descriptor.usage = MTLTextureUsageRenderTarget
        val target = checkNotNull(device.newTextureWithDescriptor(descriptor))
        val command = encode(target, viewport.theme.background, images)
        command.commit(); command.waitUntilCompleted(); imageCache.completed()
        check(command.error == null) { command.error?.localizedDescription.orEmpty() }
        val result = ByteArray(pixelWidth * pixelHeight * 4)
        result.usePinned { target.getBytes(it.addressOf(0), (pixelWidth * 4).toULong(),
            MTLRegionMake2D(0u, 0u, pixelWidth.toULong(), pixelHeight.toULong()), 0u) }
        return result
    }

    fun dispose() {
        if (disposed) return
        cancelPresentationRetry()
        disposed = true; pending = false; releasePending = true
        layer.removeFromSuperlayer(); imageCache.clear()
        if (!busy) releaseResources()
        // Keep resources reachable until the submitted command completes, then mark them empty.
    }

    companion object {
        private const val SHADER = """
#include <metal_stdlib>
using namespace metal;
struct Instance { float4 rect; float4 uvRect; float4 tint; };
struct Vertex { float4 position [[position]]; float2 uv; float4 tint; };
vertex Vertex terminalVertex(uint vertexId [[vertex_id]], uint instanceId [[instance_id]],
    const device Instance* instances [[buffer(0)]], constant float2& viewportSize [[buffer(1)]]) {
    Instance item = instances[instanceId];
    float2 corner = float2(float(vertexId & 1), float(vertexId >> 1));
    float2 p = item.rect.xy + corner * item.rect.zw;
    Vertex out;
    out.position = float4(p.x / viewportSize.x * 2 - 1, 1 - p.y / viewportSize.y * 2, 0, 1);
    out.uv = mix(item.uvRect.xy, item.uvRect.zw, corner); out.tint = item.tint;
    return out;
}
fragment float4 terminalFragment(Vertex in [[stage_in]], texture2d<float> atlas [[texture(0)]],
    constant uint& textured [[buffer(0)]]) {
    constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::nearest);
    constexpr sampler imageSampler(coord::normalized, address::clamp_to_edge, filter::linear);
    return textured == 2 ? atlas.sample(imageSampler, in.uv) * in.tint :
        (textured ? atlas.sample(s, in.uv) * in.tint : in.tint);
}
"""
    }
}
