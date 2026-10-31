@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.Metal.*
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.test.*

class NativeGraphicsRenderingTest {
    @Test fun selectionColorsPixelsMatchExplicitColorsAcrossInverseTextAndDecorations() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    reset()
                    session.configureTheme(TerminalTheme(foreground = 0x123456, background = 0x654321))
                    session.write("\u001b]4;1;#112233;9;#8899aa;4;#445566\u0007")
                    session.boldColor = TerminalBoldColor.BRIGHT
                    for (inverse in listOf(false, true)) {
                        val cellForeground = if (inverse) 0x445566 else 0x8899aa
                        val cellBackground = if (inverse) 0x8899aa else 0x445566
                        val foregrounds = listOf(null to 0x654321, TerminalSelectionColor.CELL_FOREGROUND to cellForeground,
                            TerminalSelectionColor.CELL_BACKGROUND to cellBackground, TerminalSelectionColor.Rgb(0xabcdef) to 0xabcdef)
                        val backgrounds = listOf(null to 0x123456, TerminalSelectionColor.CELL_FOREGROUND to cellForeground,
                            TerminalSelectionColor.CELL_BACKGROUND to cellBackground, TerminalSelectionColor.Rgb(0x0f1e2d) to 0x0f1e2d)
                        for ((foreground, fg) in foregrounds) for ((background, bg) in backgrounds) {
                            session.selectionColors = TerminalSelectionColors(foreground, background)
                            session.write("\u001b[H\u001b[0;1;31;44${if (inverse) ";7" else ""};4:3;53;9;58;2;20;210;40mA█g😀")
                            selectAll()
                            val selected = capture()
                            clearSelection()
                            session.write("\u001b[H\u001b[0;1;38;2;${fg shr 16};${fg shr 8 and 255};${fg and 255};48;2;${bg shr 16};${bg shr 8 and 255};${bg and 255};4:3;53;9;58;2;20;210;40mA█g😀")
                            assertContentEquals(capture(), selected, "foreground=$foreground background=$background inverse=$inverse")
                        }
                    }
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun boldColorPixelsMatchExplicitColorsAcrossTextBlocksEmojiAndDecorations() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    reset()
                    session.write("\u001b]10;#123456\u0007\u001b]4;1;#123456;9;#204060;200;#abcdef\u0007")
                    val background = capture()
                    for (policy in listOf(TerminalBoldColor.BRIGHT, TerminalBoldColor.Rgb(0x2468ac))) {
                        val default = if (policy is TerminalBoldColor.Rgb) policy.value else 0x123456
                        for ((sgr, expected) in listOf("39" to default, "31" to 0x204060, "91" to 0x204060,
                            "38;5;200" to 0xabcdef, "38;2;18;52;86" to default, "38;2;9;8;7" to 0x090807,
                            "31;7" to 0x204060)) {
                            session.boldColor = null
                            session.write("\u001b[H\u001b[0;1;$sgr;4:3;53;9;58;2;20;210;40mA█g😀")
                            capture()
                            session.boldColor = policy // Updating host policy must repaint existing cells.
                            val actual = capture()
                            assertTrue(actual.indices.count { actual[it] != background[it] } > 16, "Must draw text and decorations")
                            session.boldColor = null
                            val inverse = if (sgr == "31;7") ";7" else ""
                            session.write("\u001b[H\u001b[0;1;38;2;${expected shr 16};${expected shr 8 and 255};${expected and 255}$inverse;4:3;53;9;58;2;20;210;40mA█g😀")
                            assertContentEquals(capture(), actual, "policy=$policy sgr=$sgr")
                        }
                    }
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun coloredUnderlinesStayBehindDescendersAndBlocksWhileStrikeCoversEmoji() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    for (backgroundImage in listOf(false, true)) for (faint in listOf(false, true)) for (style in 1..6) {
                        val strike = style == 6
                        val text = if (strike) "😀😀" else "gypq██"
                        val cells = if (strike) 4 else 6
                        val decoration = if (strike) "9" else "4:$style;58;2;0;255;0"
                        val pen = "38;2;255;255;255" + if (faint) ";2" else ""
                        fun render(attributes: String, text: String): ByteArray {
                            session.reset()
                            session.write("\u001b[?25l\u001b]11;#000000\u0007" +
                                (if (backgroundImage) image(44, listOf(0, 0, 120, 255), ",z=-1") else "") +
                                "\u001b[H\u001b[${pen}${attributes}m$text")
                            return capture()
                        }
                        val background = render("", "")
                        val glyphs = render("", text)
                        val line = render(";$decoration", " ".repeat(cells))
                        val combined = render(";$decoration", text)
                        var overlaps = 0
                        for (i in combined.indices step 4) {
                            val base = background[i].toInt() and 255
                            val coverage = (((if (strike) line[i] else glyphs[i]).toInt() and 255) - base).toDouble() / (255 - base)
                            if (coverage > 0.1 && ((if (strike) glyphs[i + 1] else line[i + 1]).toInt() and 255) - (background[i + 1].toInt() and 255) > 30) overlaps++
                            for (channel in 0..2) {
                                val behind = (if (strike) glyphs[i + channel] else line[i + channel]).toInt() and 255
                                val expected = kotlin.math.round(255 * coverage + behind * (1 - coverage)).toInt()
                                val actual = combined[i + channel].toInt() and 255
                                assertTrue(kotlin.math.abs(actual - expected) <= 3,
                                    "Decoration byte ${i + channel}: expected $expected, got $actual; image=$backgroundImage, faint=$faint, style=$style")
                            }
                        }
                        assertTrue(overlaps > 4, "Must intersect real glyph and decoration pixels: count=$overlaps, image=$backgroundImage, faint=$faint, style=$style")
                    }
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun faintCompositesTextColorGlyphsAndDecorationsOverBackgroundsAndImages() {
        NSApplication.sharedApplication()
        val samples = listOf("" to "Aa😀", "" to "█▀") + (1..5).map { "4:$it;53;9;58;2;20;210;40;" to "  " }
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    for (backgroundImage in listOf(false, true)) for ((attributes, text) in samples) {
                        reset()
                        if (backgroundImage) session.write(image(44, green, ",z=-1"))
                        val background = capture()
                        session.write("\u001b[H\u001b[${attributes}38;2;240;20;30m$text")
                        val normal = capture()
                        session.write("\u001b[H\u001b[2m$text")
                        for (configured in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) {
                            session.faintOpacity = configured
                            val faint = capture()
                            val alpha = ceil(configured * 255).toInt()
                            var affected = 0
                            for (i in normal.indices) {
                                val base = background[i].toInt() and 255
                                val full = normal[i].toInt() and 255
                                if (full != base) affected++
                                val expected = (base * (255 - alpha) + full * alpha + 127) / 255
                                val actual = faint[i].toInt() and 255
                                assertTrue(kotlin.math.abs(actual - expected) <= 2,
                                    "Faint opacity $configured byte $i: expected $expected, got $actual; image=$backgroundImage, attributes=$attributes, text=$text")
                            }
                            assertTrue(affected > 16, "Must exercise visible text or decorations")
                        }
                        session.faintOpacity = 0.1
                        session.write("\u001b[H\u001b[22m$text")
                        assertContentEquals(normal, capture(), "SGR 22 restores full opacity")
                    }
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun overlinePixelsUseForegroundAndRespectResetHiddenInverseAndPaletteChanges() {
        NSApplication.sharedApplication()
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                with(surface) {
                    fun top(expected: List<Int>) = assertEquals(expected, pixel(capture(), width, 2, 0))
                    session.write("\u001b[?25l\u001b[38;2;240;20;30;48;2;10;20;200;58;2;20;210;40;53m  ")
                    top(red) // The underline color must not color the overline.
                    assertEquals(blue, pixel(capture(), width, 2, 4))
                    session.write("\u001b[H\u001b[55m  "); top(blue)
                    session.write("\u001b[H\u001b[53;8m  "); top(blue)
                    session.write("\u001b[H\u001b[28;7m  "); top(blue)
                    assertEquals(red, pixel(capture(), width, 2, 4))
                    session.write("\u001b[H\u001b[0;53;2;38;2;240;20;30m  ")
                    top(listOf(135, 25, 30, 255))
                    session.write("\u001b[H\u001b[0;53;31m  \u001b]4;1;#123456\u0007")
                    top(listOf(18, 52, 86, 255))
                }
            } finally { surface.dispose() }
        }
    }

    @Test fun metalSurfaceDimensionsRespectDeviceLimitsAcrossResizeAndVisibility() {
        NSApplication.sharedApplication()
        val device = checkNotNull(MTLCreateSystemDefaultDevice())
        // Original Ghostty's Device.queryMaxTextureSize, from Apple's feature-set table.
        val limit = when {
            device.supportsFamily(MTLGPUFamilyApple10) -> 32768
            device.supportsFamily(MTLGPUFamilyApple3) -> 16384
            else -> 8192
        }
        val session = TerminalSession(20, 6)
        val viewport = TerminalViewport(session, {})
        val renderer = MetalTerminalDrawing {}
        try {
            session.resize(20, 6, TerminalCellSize(8, 16))
            session.setGraphicsStorageLimit(64 * 1024 * 1024)
            session.write("\u001b[?25l\u001b]11;#0a14c8\u0007" + image(1, red))
            for (scale in listOf(1.0, 1.25, 2.0)) for (wide in listOf(true, false)) {
                val major = limit / scale + 100.25
                val minor = 2 / scale
                renderer.resize(if (wide) major else minor, if (wide) minor else major,
                    scale, 14.0, 8.0, 16.0)
                val width = if (wide) limit else 2
                val height = if (wide) 2 else limit
                fun expectSize() = renderer.layer.drawableSize.useContents {
                    assertEquals(width.toDouble(), this.width)
                    assertEquals(height.toDouble(), this.height)
                }
                expectSize()
                renderer.setVisible(false)
                renderer.setVisible(true)
                expectSize()
                // Only two pixels along the short axis: exercise real rendering without
                // allocating a maximum-width * maximum-height texture or readback buffer.
                val bytes = renderer.snapshot(viewport)
                assertEquals(width * height * 4, bytes.size)
                assertEquals(red, pixel(bytes, width, 0, 0).let { listOf(it[2], it[1], it[0], it[3]) })
                assertEquals(blue, pixel(bytes, width, width - 1, height - 1).let { listOf(it[2], it[1], it[0], it[3]) })
            }
            renderer.resize(160.2, 96.2, 1.25, 14.0, 8.0, 16.0)
            renderer.layer.drawableSize.useContents {
                assertEquals(201.0, width); assertEquals(121.0, height)
            }
            if (limit == 32768) {
                renderer.resize(limit.toDouble(), limit.toDouble(), 1.0, 14.0, 8.0, 16.0)
                assertFailsWith<IllegalArgumentException> { renderer.snapshot(viewport) }
            }
        } finally { renderer.dispose(); viewport.dispose() }
    }

    @Test fun nativePngCropPixelsMatchTheFixtureWithAndWithoutOuterZlib() {
        NSApplication.sharedApplication()
        val fixture = terminalGraphicsPngFixtures().first { Base64.decode(it.rgba)[3] == 255.toByte() }
        val png = Base64.decode(fixture.png)
        val expected = Base64.decode(fixture.rgba).take(4).map { it.toInt() and 255 }
        for (factory in listOf<() -> Surface>(::software, { metal(1.0) }, { metal(1.25) }, { metal(2.0) })) {
            val surface = factory()
            try {
                for (compressed in listOf(false, true)) with(surface) {
                    reset()
                    val payload = if (compressed) storedZlib(png) else png
                    session.write(apc("a=T,q=2,i=1,f=100," + (if (compressed) "o=z," else "") +
                        "w=1,h=1,c=4,r=4,C=1;" + Base64.encode(payload)))
                    expect(expected, "PNG first-pixel crop, outer zlib=$compressed")
                }
            } finally { surface.dispose() }
        }
    }

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
        fun selectAll()
        fun clearSelection()
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
        override fun selectAll() = viewport.selectAll()
        override fun clearSelection() = viewport.clearSelection()
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
        override fun selectAll() = view.selectAll(null)
        override fun clearSelection() = view.clearSelection()
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
