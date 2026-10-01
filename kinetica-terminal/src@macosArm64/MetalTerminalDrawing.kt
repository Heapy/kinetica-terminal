@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.Metal.*
import platform.QuartzCore.*
import platform.posix.memcpy
import kotlin.math.ceil

/** All mutable state is main-thread confined. One asynchronous frame owns the reusable buffers. */
internal class MetalTerminalDrawing(private val probe: TerminalRenderProbe = TerminalRenderProbe(null), private val invalidate: () -> Unit) {
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
    private val frame = TerminalGpuFrame()
    private val batches = TerminalGpuBatches()
    private val textures = mutableListOf<MTLTextureProtocol>()
    private val buffers = mutableListOf<MTLBufferProtocol>()
    private var busy = false
    private var pending = false
    private var disposed = false
    private var resetAtlas = true
    private var failure: String? = null
    private var background: Int? = null
    private var width = 0.0; private var height = 0.0; private var scale = 1.0
    private var submittedWidth = 0.0; private var submittedHeight = 0.0; private var submittedScale = 0.0
    private var fontSize = 14.0; private var cellWidth = 0.0; private var cellHeight = 0.0
    private var fontFamily: String? = null
    private val attributes = mutableMapOf<Int, Map<Any?, *>>()
    val glyphCount: Int get() = atlas.entryCount
    var rasterizations: Int = 0
        private set
    var drawCalls: Int = 0
        private set
    var submittedFrames: Int = 0
        private set

    fun resize(width: Double, height: Double, scale: Double, fontSize: Double, cellWidth: Double, cellHeight: Double,
        managesLayerFrame: Boolean = true, fontFamily: String? = null) {
        if (this.scale != scale || this.fontSize != fontSize || this.fontFamily != fontFamily || this.cellWidth != cellWidth || this.cellHeight != cellHeight) resetAtlas = true
        this.fontFamily = fontFamily
        this.width = width; this.height = height; this.scale = scale; this.fontSize = fontSize
        this.cellWidth = cellWidth; this.cellHeight = cellHeight
        CATransaction.begin(); CATransaction.setDisableActions(true)
        if (managesLayerFrame) layer.frame = NSMakeRect(0.0, 0.0, width, height)
        layer.contentsScale = scale
        layer.drawableSize = NSMakeSize(ceil(width * scale), ceil(height * scale))
        CATransaction.commit()
    }

    fun draw(viewport: TerminalViewport, synchronizePresentation: Boolean = false) {
        failure?.let { error(it) }
        if (disposed || width <= 0 || height <= 0) return
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
        prepare(viewport, measurement)
        val command = checkNotNull(queue.commandBuffer())
        encode(command, drawable.texture as MTLTextureProtocol, viewport.theme.background)
        if (!transactional) command.presentDrawable(drawable as MTLDrawableProtocol)
        if (measurement != null) {
            drawable.addPresentedHandler { presented ->
                val time = presented?.presentedTime ?: 0.0
                NSOperationQueue.mainQueue.addOperationWithBlock {
                    if (!disposed) probe.observer?.onFramePresented(measurement.id, time)
                }
            }
        }
        busy = true
        command.addCompletedHandler { completed ->
            val message = completed?.error?.localizedDescription
            val gpuTime = if (measurement != null && message == null && completed != null && completed.GPUStartTime > 0.0)
                ((completed.GPUEndTime - completed.GPUStartTime) * 1000).takeIf { it >= 0.0 && it.isFinite() } else null
            NSOperationQueue.mainQueue.addOperationWithBlock {
                busy = false
                if (!disposed) {
                    if (message != null) failure = "Terminal Metal frame failed: $message"
                    if (pending || failure != null) { pending = false; invalidate() }
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

    private fun prepare(viewport: TerminalViewport, measurement: TerminalFrameMeasurement? = null) {
        if (resetAtlas) {
            atlas.clear(); textures.clear(); attributes.clear()
            viewport.markAll(); resetAtlas = false
        }
        frame.update(viewport, cellWidth, cellHeight)
        measurement?.let { it.layout = it.checkpoint() }
        atlas.prepare(frame.keys, ::rasterize, { textures.clear() }, ::upload)
        // Bind a valid texture even to the untextured branch of the fragment shader.
        if (textures.isEmpty()) createTexture()
        measurement?.let { it.atlas = it.checkpoint() }
        batches.build(frame, atlas, scale)
        measurement?.let { it.batch = it.checkpoint() }
    }

    private fun encode(command: MTLCommandBufferProtocol, target: MTLTextureProtocol, background: Int) {
        val pass = MTLRenderPassDescriptor()
        pass.colorAttachments.objectAtIndexedSubscript(0u).apply {
            texture = target
            loadAction = MTLLoadActionClear; storeAction = MTLStoreActionStore
            clearColor = MTLClearColorMake((background shr 16 and 255) / 255.0,
                (background shr 8 and 255) / 255.0, (background and 255) / 255.0, 1.0)
        }
        val encoder = checkNotNull(command.renderCommandEncoderWithDescriptor(pass))
        encoder.setRenderPipelineState(pipeline)
        // drawableSize is rounded to whole pixels; using the unrounded view bounds
        // here would scale every glyph again when the window has a fractional size.
        val viewport = floatArrayOf((target.width.toDouble() / scale).toFloat(), (target.height.toDouble() / scale).toFloat())
        viewport.usePinned { encoder.setVertexBytes(it.addressOf(0), 8u, 1u) }
        drawCalls = 0
        submit(encoder, batches.backgrounds, false, 0, 0)
        for (page in batches.pages.indices) submit(encoder, batches.pages[page], true, page, page + 1)
        submit(encoder, batches.decorations, false, 0, batches.pages.size + 1)
        encoder.endEncoding()
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
        val width = ceil(cellWidth * key.columns * scale).toInt().coerceAtLeast(1)
        val height = ceil(cellHeight * scale).toInt().coerceAtLeast(1)
        val bytes = ByteArray(width * height * 4)
        val colorSpace = checkNotNull(CGColorSpaceCreateDeviceRGB())
        try {
            bytes.usePinned { pinned ->
                val context = checkNotNull(CGBitmapContextCreate(pinned.addressOf(0), width.toULong(), height.toULong(), 8u,
                    (width * 4).toULong(), colorSpace, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                NSGraphicsContext.saveGraphicsState()
                try {
                    CGContextTranslateCTM(context, 0.0, height.toDouble())
                    CGContextScaleCTM(context, scale, -scale)
                    NSGraphicsContext.setCurrentContext(NSGraphicsContext.graphicsContextWithCGContext(context, true))
                    val attrs = attributes.getOrPut(key.style) {
                        val font = terminalNativeFont(fontFamily, fontSize, key.style)
                        mapOf(NSFontAttributeName to font, NSForegroundColorAttributeName to NSColor.whiteColor)
                    }
                    NSAttributedString.create(string = key.text, attributes = attrs).drawAtPoint(NSMakePoint(0.0, 0.0))
                } finally { NSGraphicsContext.restoreGraphicsState(); CGContextRelease(context) }
            }
        } finally { CGColorSpaceRelease(colorSpace) }
        rasterizations++
        return glyphBitmap(width, height, bytes, premultiplied = true)
    }

    /** Readback for native pixel tests only; interactive presentation never waits or reads back. */
    internal fun snapshot(viewport: TerminalViewport): ByteArray {
        check(!busy && !disposed)
        prepare(viewport)
        val pixelWidth = ceil(width * scale).toInt(); val pixelHeight = ceil(height * scale).toInt()
        val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(MTLPixelFormatBGRA8Unorm,
            pixelWidth.toULong(), pixelHeight.toULong(), false)
        descriptor.storageMode = MTLStorageModeShared; descriptor.usage = MTLTextureUsageRenderTarget
        val target = checkNotNull(device.newTextureWithDescriptor(descriptor))
        val command = checkNotNull(queue.commandBuffer())
        encode(command, target, viewport.theme.background); command.commit(); command.waitUntilCompleted()
        check(command.error == null) { command.error?.localizedDescription.orEmpty() }
        val result = ByteArray(pixelWidth * pixelHeight * 4)
        result.usePinned { target.getBytes(it.addressOf(0), (pixelWidth * 4).toULong(),
            MTLRegionMake2D(0u, 0u, pixelWidth.toULong(), pixelHeight.toULong()), 0u) }
        return result
    }

    fun dispose() {
        if (disposed) return
        disposed = true; pending = false
        layer.removeFromSuperlayer(); textures.clear(); buffers.clear(); atlas.clear(); attributes.clear()
        // Submitted command buffers retain their resources until completion.
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
    return textured ? atlas.sample(s, in.uv) * in.tint : in.tint;
}
"""
    }
}
