@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import platform.AppKit.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalNativeShaperTest {
    @Test fun wholeGraphemesResolveBeforeShapingAndMissingGlyphsUseReplacement(): Unit = withTerminalShapingFont { font ->
        val shaper = TerminalNativeShaper(font)
        try {
            for (text in listOf("a", "é", "👍🏽", "👩‍💻", "界")) {
                val resolved = shaper.resolve(text)
                assertEquals(text, resolved.text)
                val shaped = shaper.shape(TerminalShapingText(text, IntArray(text.length), 2), resolved.font)
                assertTrue(shaped.isNotEmpty())
                assertTrue(shaped.all { it.font == resolved.font && it.glyph != 0 }, text)
                val count = shaper.fontCount
                assertEquals(resolved, shaper.resolve(text))
                assertEquals(count, shaper.fontCount)
            }
            val replacement = shaper.resolve(codePointString(0x10fffd))
            assertEquals("�", replacement.text)
            val missingMark = shaper.resolve("a" + codePointString(0x10fffd))
            assertEquals("�", missingMark.text, "Replace the entire unsupported cluster")
        } finally { shaper.dispose() }
        assertEquals(0, shaper.fontCount)
        assertFailsWith<IllegalStateException> { shaper.resolve("a") }
    }

    @Test fun presentationSelectorsChooseColorOrTextFaceBeforeBeingRemoved() = withTerminalShapingFont { font ->
        val shaper = TerminalNativeShaper(font)
        try {
            for ((text, colored) in listOf("❤︎" to false, "❤️" to true)) {
                val resolved = shaper.resolve(text)
                val glyph = shaper.shape(TerminalShapingText("❤", intArrayOf(0), 2), resolved.font).single()
                val bitmap = terminalRasterizeNativeGlyph(shaper.face(glyph.font), glyph.glyph, 16.0, 1022)
                assertTrue(bitmap.rgba.indices.any { it % 4 == 3 && bitmap.rgba[it] != 0.toByte() })
                assertEquals(colored, bitmap.colored, text)
            }
        } finally { shaper.dispose() }
    }

    @Test fun embeddedFontMatchesOriginalGlyphSubstitutionsAndGridOffsets() {
        val bytes = Base64.decode(terminalShapingFont())
        val data = bytes.usePinned { checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())) }
        val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
        val graphicsFont = checkNotNull(CGFontCreateWithDataProvider(provider))
        val font = checkNotNull(CTFontCreateWithGraphicsFont(graphicsFont, 12.0, null, null))
        val shaper = TerminalNativeShaper(interpretObjCPointer<NSFont>(font.rawValue))
        try {
            var checked = 0
            for (encoded in terminalCoreTextShapingFixtures()) {
                val case = Json.parseToJsonElement(Base64.decode(encoded).decodeToString()).jsonObject
                if (case["font"]?.jsonPrimitive?.content?.let { it != "jetbrains_mono" } == true || "features" in case) continue
                for (entry in case.getValue("expected").jsonObject.getValue("runs").jsonArray) {
                    val run = entry.jsonObject
                    if (run.int("font_index") != 0) continue
                    val text = StringBuilder()
                    val clusters = mutableListOf<Int>()
                    for (point in run.getValue("input").jsonArray) {
                        val value = codePointString(point.jsonObject.int("codepoint"))
                        text.append(value); repeat(value.length) { clusters += point.jsonObject.int("cluster") }
                    }
                    val input = TerminalShapingText(text.toString(), clusters.toIntArray(), run.int("cells"))
                    val actual = shaper.shape(input)
                    for (glyph in actual) {
                        val resolved = interpretObjCPointer<NSFont>(shaper.face(glyph.font).rawValue)
                        assertEquals(interpretObjCPointer<NSFont>(font.rawValue).fontName, resolved.fontName)
                        assertEquals(12.0, resolved.pointSize)
                    }
                    val expected = run.getValue("glyphs").jsonArray.map { glyph -> glyph.jsonObject.let {
                        TerminalShapedGlyph(0, it.int("glyph_index"), it.int("x"), it.int("x_offset"), it.int("y_offset"))
                    } }
                    assertEquals(expected, actual.map { it.copy(font = 0) }, "${case["name"]} offset=${run["offset"]}")
                    val count = shaper.fontCount
                    assertEquals(actual, shaper.shape(input), "Identical runs reuse retained font identities")
                    assertEquals(count, shaper.fontCount)
                    checked += actual.size
                }
            }
            assertTrue(checked >= 600)
        } finally {
            shaper.dispose(); CFRelease(font); CGFontRelease(graphicsFont)
            CGDataProviderRelease(provider); CFRelease(data)
        }
        assertEquals(0, shaper.fontCount)
        shaper.dispose()
        assertFailsWith<IllegalStateException> { shaper.shape(TerminalShapingText("", intArrayOf(), 0)) }
    }

    @Test fun fallbackEmojiCanJoinAcrossTerminalCellsWithoutMovingFollowingText() {
        val shaper = TerminalNativeShaper(NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular))
        try {
            val joined = shaper.shape(TerminalShapingText("👍🏽x", intArrayOf(0, 0, 2, 2, 4), 5))
            assertEquals(listOf(0, 4), joined.map { it.column })
            assertEquals(listOf(0, 0), joined.map { it.xOffset })
            assertEquals(2, joined.map { it.font }.distinct().size)
            val grouped = shaper.shape(TerminalShapingText("👍🏽x", intArrayOf(0, 0, 0, 0, 2), 3))
            assertEquals(listOf(0, 2), grouped.map { it.column })
            assertEquals(joined.map { it.glyph }, grouped.map { it.glyph })
        } finally { shaper.dispose() }
    }

    private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int
}
