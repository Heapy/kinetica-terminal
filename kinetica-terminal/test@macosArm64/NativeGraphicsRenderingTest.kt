@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.Foundation.*
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.test.*

class NativeGraphicsRenderingTest {
    private interface Surface {
        val session: TerminalSession
        val width: Int
        val bytes: Long
        val tiles: Int
        val uploads: Int
        fun capture(): ByteArray // RGBA, top-left origin.
        fun scrollTo(offset: Int)
        fun dispose()
    }
    private fun metal(scale: Double): Surface = object : Surface {
        override val session = TerminalSession(20, 6, 20).also {
            it.resize(20, 6, TerminalCellSize((8 * scale).toInt(), (16 * scale).toInt()))
            it.setGraphicsStorageLimit(64 * 1024 * 1024)
        }
        private val viewport = TerminalViewport(session, {})
        private val renderer = MetalTerminalDrawing {}.also { it.resize(160.0, 96.0, scale, 14.0, 8.0, 16.0) }
        override val width = ceil(160 * scale).toInt()
        override val bytes get() = renderer.imageCacheBytes
        override val tiles get() = renderer.imageTileCount
        override val uploads get() = renderer.imageUploads
        override fun capture(): ByteArray = renderer.snapshot(viewport).also { bytes ->
            for (i in bytes.indices step 4) { val blue = bytes[i]; bytes[i] = bytes[i + 2]; bytes[i + 2] = blue }
        }
        override fun scrollTo(offset: Int) = viewport.scrollTo(offset)
        override fun dispose() { renderer.dispose(); viewport.dispose() }
    }
    private fun software(): Surface = object : Surface {
        override val session = TerminalSession(20, 6, 20)
        private val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE).also { it.setFrameSize(NSMakeSize(160.0, 96.0)) }
        override val width = 160
        override val bytes get() = view.imageCacheBytes
        override val tiles get() = view.imageTileCount
        override val uploads get() = view.imageUploads
        override fun capture(): ByteArray {
            val bytes = ByteArray(160 * 96 * 4)
            val space = checkNotNull(CGColorSpaceCreateDeviceRGB())
            try {
                bytes.usePinned {
                    val context = checkNotNull(CGBitmapContextCreate(it.addressOf(0), 160u, 96u, 8u, 640u, space,
                        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big))
                    NSGraphicsContext.saveGraphicsState()
                    try {
                        CGContextTranslateCTM(context, 0.0, 96.0); CGContextScaleCTM(context, 1.0, -1.0)
                        NSGraphicsContext.setCurrentContext(NSGraphicsContext.graphicsContextWithCGContext(context, true))
                        view.drawRect(NSMakeRect(0.0, 0.0, 160.0, 96.0))
                    } finally { NSGraphicsContext.restoreGraphicsState(); CGContextRelease(context) }
                }
            } finally { CGColorSpaceRelease(space) }
            return bytes
        }
        override fun scrollTo(offset: Int) = view.scrollTo(offset)
        override fun dispose() = view.dispose()
    }

    private val red = listOf(240, 20, 30, 255)
    private val green = listOf(20, 210, 40, 255)
    private val blue = listOf(10, 20, 200, 255)
    private fun image(id: Int, color: List<Int>, extra: String = "") =
        apc("a=T,q=2,i=$id,f=24,s=1,v=1,c=4,r=4,C=1$extra;" + Base64.encode(color.take(3).map(Int::toByte).toByteArray()))
    private fun apc(command: String) = "\u001b_G$command\u001b\\"
    private fun pixel(bytes: ByteArray, width: Int, x: Int = 2, y: Int = 2) =
        (0..3).map { bytes[(y * width + x) * 4 + it].toInt() and 255 }
    private fun Surface.reset() { session.reset(); session.write("\u001b[?25l\u001b]11;#0a14c8\u0007") }
    private fun Surface.expect(color: List<Int>, message: String) = assertEquals(color, pixel(capture(), width), message)

    @Test fun nativeImagePixelsRespectLayersCropAlphaHoldsAndScreenLifecycle() {
        NSApplication.sharedApplication()
        val factories = listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })
        for ((backend, factory) in factories.withIndex()) {
            val surface = factory()
            try {
                with(surface) {
                    reset(); session.write(image(1, red, ",z=-2147483648"))
                    expect(red, "$backend: lowest layer through default background")
                    session.write("\u001b[H\u001b[48;2;10;20;200m \u001b[0m")
                    expect(blue, "$backend: explicit background")
                    session.write("\u001b[H" + image(2, green, ",z=-1"))
                    expect(green, "$backend: middle layer")
                    session.write("\u001b[H\u001b[38;2;240;20;30m█\u001b[0m")
                    expect(red, "$backend: block glyph above middle layer")
                    session.write("\u001b[H" + image(3, green))
                    expect(green, "$backend: top layer")
                    val cached = uploads
                    capture(); assertEquals(cached, uploads, "$backend: reuse")
                    session.write(apc("a=d,d=A,q=2"))
                    expect(red, "$backend: deletion restores glyph"); assertEquals(0L, bytes)
                    reset()
                    session.write(apc("a=T,q=2,i=1,f=32,s=1,v=1,c=4,r=4,C=1;" + Base64.encode(byteArrayOf(240.toByte(), 20, 30, 128.toByte()))))
                    val alpha = pixel(capture(), width)
                    listOf(125, 20, 115, 255).forEachIndexed { i, value -> assertTrue(kotlin.math.abs(alpha[i] - value) <= 1, "$backend: alpha $alpha") }
                    reset()
                    session.write(apc("a=T,q=2,i=1,f=24,s=2,v=1,x=1,w=1,c=4,r=4,C=1;" + Base64.encode((red.take(3) + green.take(3)).map(Int::toByte).toByteArray())))
                    expect(green, "$backend: source crop must not bleed adjacent red")
                    reset(); session.write(image(1, red)); expect(red, "$backend: initial frame")
                    session.write("\u001b[?2026h" + image(1, green))
                    expect(red, "$backend: held generation")
                    session.write("\u001b[?2026l"); expect(green, "$backend: released generation")
                    session.write("\u001b[${session.rows};1H\n"); expect(green, "$backend: partially scrolled image")
                    scrollTo(1); expect(green, "$backend: history image")
                    session.write("\u001b[?1049h"); expect(blue, "$backend: alternate screen")
                    assertEquals(0L, bytes)
                    session.write("\u001b[?1049l"); expect(green, "$backend: primary restored")
                    dispose(); assertEquals(0L, bytes); assertEquals(0, tiles)
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun nativeImageOrientationAndScaledTileSeamsProduceExactPixels() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    reset()
                    val rows = red.take(3) + green.take(3)
                    session.write(apc("a=T,q=2,i=1,f=24,s=1,v=2,c=4,r=4,C=1;" + Base64.encode(rows.map(Int::toByte).toByteArray())))
                    val pixels = capture()
                    assertEquals(red, pixel(pixels, width, 2, 2), "Image top row")
                    assertEquals(green, pixel(pixels, width, 2, session.cellSize!!.height * 4 - 3), "Image bottom row")
                    reset()
                    val payload = Base64.encode(ByteArray(2046 * 3) { red[it % 3].toByte() })
                    session.write(apc("a=T,q=2,i=9,f=24,s=2046,v=1,c=12,r=2,C=1,m=1;${payload.take(4096)}") + apc("m=0;${payload.drop(4096)}"))
                    val tiled = capture()
                    for (x in 2 until session.cellSize!!.width * 12 - 2) assertEquals(red, pixel(tiled, width, x), "Tile seam at $x")
                    assertEquals(3, tiles); assertTrue(bytes < 2046 * 4 + 32)
                    session.write(apc("a=d,d=A,q=2")); capture()
                    assertEquals(0L, bytes)
                }
            } finally { surface.dispose() }
        }
    }
}
