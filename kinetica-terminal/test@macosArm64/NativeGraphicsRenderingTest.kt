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
    @Test fun metalBudgetSplitsPreserveAllLayersAndPixelsAcrossRepeatedFrames() {
        NSApplication.sharedApplication()
        val session = TerminalSession(20, 6)
        session.resize(20, 6, TerminalCellSize(8, 16))
        session.setGraphicsStorageLimit(1024)
        val viewport = TerminalViewport(session, {})
        val reference = MetalTerminalDrawing {}
        val bounded = MetalTerminalDrawing(imageBudget = 8) {}
        try {
            for (renderer in listOf(reference, bounded)) renderer.resize(160.0, 96.0, 1.0, 14.0, 8.0, 16.0)
            session.write("\u001b[?25l\u001b[H\u001b[44mX\u001b[0m")
            repeat(12) { index ->
                val layer = when (index % 3) { 0 -> Int.MIN_VALUE; 1 -> -1; else -> 0 }
                session.write("\u001b[${index / 6 + 1};${index % 6 * 3 + 1}H" +
                    image(index + 1, listOf(index * 20, 200 - index * 10, 60), ",z=$layer"))
            }
            repeat(3) {
                viewport.markAll(); val expected = reference.snapshot(viewport)
                viewport.markAll(); val actual = bounded.snapshot(viewport)
                assertContentEquals(expected, actual, "Budget boundaries must load, not clear, previous passes")
                assertTrue(bounded.imageCacheBytes <= 8)
            }
            assertTrue(bounded.imageBudgetDrains >= 15, "Each frame must exceed the two-tile budget")
            assertTrue(bounded.imageUploads >= 36, "All image generations are rendered on every bounded frame")
            assertEquals(0, reference.imageBudgetDrains, "Ordinary frames stay asynchronous")
            session.write(apc("a=d,d=A,q=2"))
            bounded.snapshot(viewport)
            assertEquals(0L, bounded.imageCacheBytes)
        } finally { reference.dispose(); bounded.dispose(); viewport.dispose() }
    }

    private interface Surface {
        val session: TerminalSession
        val width: Int
        val bytes: Long
        val tiles: Int
        val uploads: Int
        fun capture(): ByteArray // RGBA, top-left origin.
        fun scrollTo(offset: Int)
        fun setVisible(visible: Boolean)
        fun drawWhileHidden()
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
        override fun setVisible(visible: Boolean) = renderer.setVisible(visible)
        override fun drawWhileHidden() = renderer.draw(viewport)
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
        override fun setVisible(visible: Boolean) { view.hidden = !visible }
        override fun drawWhileHidden() { capture() }
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

    @Test fun hiddenNativeSurfacesReleaseImagesAndRestoreHeldPixels() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    reset(); session.write("Hello\u001b[H" + image(1, red))
                    val initial = capture()
                    assertTrue(bytes > 0); assertTrue(tiles > 0)
                    session.write("\u001b[?2026h" + image(1, green))
                    repeat(3) {
                        setVisible(false)
                        drawWhileHidden()
                        assertEquals(0L, bytes, "Hidden image payload")
                        assertEquals(0, tiles, "Hidden image tiles")
                        val previousUploads = uploads
                        setVisible(true)
                        assertContentEquals(initial, capture(), "Reconstruct the held image and text")
                        assertTrue(uploads > previousUploads, "Reupload after resources were reclaimed")
                    }
                    session.write("\u001b[?2026l")
                    expect(green, "Release displays the updated image")
                    setVisible(false); dispose()
                    assertEquals(0L, bytes); assertEquals(0, tiles)
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun hiddenMetalReleasesAtlasBuffersAndDrawableAndRestoresWithoutResize() {
        NSApplication.sharedApplication()
        val session = TerminalSession(20, 6)
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            renderer.resize(160.0, 96.0, 2.0, 14.0, 8.0, 16.0)
            session.write("\u001b[?25lHello")
            val expected = renderer.snapshot(viewport)
            assertTrue(renderer.glyphTextureCount > 0); assertTrue(renderer.vertexBufferBytes > 0)
            renderer.setVisible(false)
            assertEquals(0, renderer.glyphTextureCount); assertEquals(0L, renderer.vertexBufferBytes)
            assertEquals(0, renderer.glyphCount); assertNull(renderer.layer.device)
            renderer.resize(160.0, 96.0, 2.0, 14.0, 8.0, 16.0)
            assertEquals(1.0, renderer.layer.drawableSize.useContents { width })
            renderer.setVisible(true)
            assertNotNull(renderer.layer.device)
            assertEquals(320.0, renderer.layer.drawableSize.useContents { width })
            assertContentEquals(expected, renderer.snapshot(viewport))
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun metalHidingAndDisposalWaitForSubmittedCommands() {
        NSApplication.sharedApplication()
        for (resume in listOf(false, true)) {
            val session = TerminalSession(20, 6)
            session.resize(20, 6, TerminalCellSize(8, 16))
            session.setGraphicsStorageLimit(1024)
            val viewport = TerminalViewport(session, {})
            val renderer = MetalTerminalDrawing {}
            try {
                renderer.resize(160.0, 96.0, 1.0, 14.0, 8.0, 16.0)
                session.write("\u001b[?25lHello\u001b[H" + image(1, red))
                renderer.draw(viewport)
                assertEquals(1, renderer.submittedFrames, "Submit an actual Metal command")
                assertTrue(renderer.imageUploads > 0, "The command samples an image")
                renderer.setVisible(false)
                assertTrue(renderer.imageCacheBytes > 0, "In-flight images remain owned until completion")
                assertTrue(renderer.glyphTextureCount > 0, "In-flight glyphs remain owned until completion")
                assertTrue(renderer.vertexBufferBytes > 0, "In-flight buffers remain owned until completion")
                if (resume) renderer.setVisible(true) else renderer.dispose()
                val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0).timeIntervalSince1970
                while (renderer.vertexBufferBytes > 0 && NSDate().timeIntervalSince1970 < deadline) {
                    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
                }
                assertEquals(0L, renderer.imageCacheBytes)
                assertEquals(0L, renderer.vertexBufferBytes)
                assertEquals(0, renderer.glyphTextureCount)
                if (resume) {
                    assertNotNull(renderer.layer.device, "Completion must not detach a resumed layer")
                    assertEquals(160.0, renderer.layer.drawableSize.useContents { width })
                    val pixels = renderer.snapshot(viewport)
                    assertEquals(red, listOf(pixels[10], pixels[9], pixels[8], pixels[11]).map { it.toInt() and 255 })
                } else assertNull(renderer.layer.device)
            } finally { renderer.dispose(); viewport.dispose() }
        }
    }

    @Test fun reverseVideoReachesSoftwareAndMetalPixelsWithoutRecoloringImages() {
        NSApplication.sharedApplication()
        val factories = listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })
        for ((backend, factory) in factories.withIndex()) {
            val surface = factory()
            try {
                with(surface) {
                    session.configureTheme(TerminalTheme(foreground = 0x112233, background = 0x445566))
                    session.write("\u001b[?25l")
                    expect(listOf(68, 85, 102, 255), "$backend: normal background")
                    session.write("\u001b[?5h")
                    expect(listOf(17, 34, 51, 255), "$backend: reversed default background")
                    session.write("█")
                    expect(listOf(68, 85, 102, 255), "$backend: reversed default foreground")
                    session.write("\u001b[H\u001b[38;2;240;20;30m█\u001b[0m")
                    expect(red, "$backend: explicit foreground")
                    session.write("\u001b[H \u001b[H" + image(1, green, ",z=-2147483648"))
                    expect(green, "$backend: image through a reversed default background")
                    val before = capture()
                    session.write("\u001b[?2026h\u001b[?5l\u001b]10;#aabbcc;#ddeeff\u0007")
                    assertContentEquals(before, capture(), "$backend: held effective colors and images")
                    session.write("\u001b[?2026l")
                    expect(green, "$backend: image keeps its RGB after release")
                    session.write(apc("a=d,d=A,q=2") + "\u001b[2J")
                    expect(listOf(221, 238, 255, 255), "$backend: released defaults")
                }
            } finally { surface.dispose() }
        }
    }

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

    @Test fun nativeAnimationCommandsRenderFramesAndPreserveHeldPixels() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    fun frame(color: List<Int>, fields: String = "") = apc("a=f,i=1,q=2,f=32,s=1,v=1$fields;" + Base64.encode(color.map(Int::toByte).toByteArray()))
                    reset(); session.write(image(1,red)); expect(red,"Root frame")
                    session.write(frame(green)); expect(red,"Append retains root")
                    session.write(apc("a=a,i=1,c=2")); expect(green,"Selected extra frame")
                    session.write("\u001b[?2026h" + frame(red,",r=2")); expect(green,"Edit preserves held pixels")
                    session.write("\u001b[?2026l"); expect(red,"Released frame edit")
                    session.write(frame(green,",r=1")); expect(red,"Undisplayed edit")
                    session.write(apc("a=c,i=1,r=1,c=2,q=2")); expect(green,"Displayed composition")
                    session.write(apc("a=d,d=f,i=1,r=1")); expect(green,"Root promotion")
                    session.write(apc("a=d,d=F,i=1,r=1")); expect(blue,"Final deletion")
                    assertEquals(0L,bytes); assertEquals(0,tiles)
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

    @Test fun nativeUnicodePlaceholdersInheritColumnsAndFollowCellErasure() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    reset()
                    val cw = session.cellSize!!.width; val ch = session.cellSize!!.height
                    val data = ByteArray(cw * 2 * ch * 3) { index ->
                        (if ((index / 3) % (cw * 2) < cw) red else green)[index % 3].toByte()
                    }
                    val placeholder = "\uDBFB\uDEEE"
                    session.write(apc("a=T,q=2,i=1,p=7,U=1,z=20,f=24,s=${cw*2},v=$ch,c=2,r=1;${Base64.encode(data)}") +
                        "\u001b[H\u001b[38;2;0;0;1m$placeholder\u0305\u0305$placeholder")
                    expect(red,"First placeholder cell")
                    assertEquals(green,pixel(capture(),width,cw+2),"Inherited next image column")
                    session.write("\u001b[H ")
                    expect(blue,"Erased placeholder")
                    assertEquals(red,pixel(capture(),width,cw+2),"New run starts from source column zero")
                    session.write("\u001b[H$placeholder\u0305\u0305\u001b[?2026h\u001b[2J")
                    expect(red,"Held placeholders survive erasure")
                    session.write("\u001b[?2026l"); expect(blue,"Released erasure")
                    assertEquals(0L,bytes)
                    reset()
                    val tinyRows = listOf('\u0305','\u030d','\u030e').joinToString("\r\n") { "$placeholder$it\u0305" + placeholder.repeat(3) }
                    session.write(apc("a=T,q=2,i=1,p=7,U=1,f=24,s=1,v=1,c=4,r=3;${Base64.encode(red.take(3).map(Int::toByte).toByteArray())}") +
                        "\u001b[H\u001b[38;2;0;0;1m$tinyRows")
                    val tiny = capture()
                    val padding = kotlin.math.round((ch * 3 - cw * 4) / 2.0).toInt()
                    assertEquals(red,pixel(tiny,width,2,padding+2),"Rounded zero source span")
                    assertEquals(red,pixel(tiny,width,2,ch*2+2),"Rounded span at the image edge")
                }
            } finally { surface.dispose() }
        }
    }
}
