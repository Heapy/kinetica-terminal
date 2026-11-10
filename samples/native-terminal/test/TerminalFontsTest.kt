@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import kotlin.test.*

class TerminalFontsTest {
    @Test fun bundledFacesAreAvailableAndStyleConversionUsesRealFaces() {
        registerTerminalFonts()
        registerTerminalFonts() // Registration is idempotent.
        val regular = assertNotNull(NSFont.fontWithName(DEFAULT_TERMINAL_FONT, 14.0))
        assertTrue(regular.fixedPitch)
        val manager = NSFontManager.sharedFontManager
        val bold = manager.convertFont(regular, toHaveTrait = NSBoldFontMask)
        val italic = manager.convertFont(regular, toHaveTrait = NSItalicFontMask)
        val boldItalic = manager.convertFont(bold, toHaveTrait = NSItalicFontMask)
        assertEquals("JetBrainsMono-Bold", bold.fontName)
        assertEquals("JetBrainsMono-Italic", italic.fontName)
        assertEquals("JetBrainsMono-BoldItalic", boldItalic.fontName)
        for (font in listOf(regular, bold, italic, boldItalic)) {
            assertEquals(14.0, font.pointSize)
            assertEquals("JetBrains Mono", font.familyName)
        }
        val choices = TerminalSettingsEditor(TerminalSettingsStore(null)) {}.fonts.map { it.value }
        assertContains(choices, DEFAULT_TERMINAL_FONT)
        assertContains(choices, "JetBrainsMono-Bold")
        assertContains(choices, "") // System Monospaced remains available.
    }

    @Test fun everyBundledFaceShapesProgrammingLigatures() {
        registerTerminalFonts()
        for (name in listOf(DEFAULT_TERMINAL_FONT, "JetBrainsMono-Bold", "JetBrainsMono-Italic", "JetBrainsMono-BoldItalic")) {
            val font = assertNotNull(NSFont.fontWithName(name, 14.0))
            for (text in listOf("!=", "=>", "->", "===")) {
                val joined = glyphs(font, text)
                val separate = text.flatMap { glyphs(font, it.toString()) }
                assertTrue(joined.isNotEmpty() && joined.all { it != 0 })
                assertNotEquals(separate, joined, "$name must shape $text as a ligature")
            }
        }
    }

    @Test fun legacyDefaultsMigrateButNamedAndExplicitSystemChoicesSurviveReload() {
        val domain = "io.heapy.kinetica.fonts-test.${NSUUID().UUIDString}"
        val defaults = checkNotNull(NSUserDefaults(suiteName = domain))
        try {
            assertEquals(DEFAULT_TERMINAL_FONT, TerminalSettingsStore(defaults).settings.fontName)
            defaults.setObject("", forKey = "terminal.font")
            assertEquals(DEFAULT_TERMINAL_FONT, TerminalSettingsStore(defaults).settings.fontName)
            defaults.setObject("missing-font-kinetica", forKey = "terminal.font")
            assertEquals(DEFAULT_TERMINAL_FONT, TerminalSettingsStore(defaults).settings.fontName)
            defaults.setObject("Menlo-Regular", forKey = "terminal.font")
            val store = TerminalSettingsStore(defaults)
            assertEquals("Menlo-Regular", store.settings.fontName)
            store.save(store.settings.copy(fontName = null))
            assertNull(TerminalSettingsStore(defaults).settings.fontName)
            store.save(store.settings.copy(fontName = DEFAULT_TERMINAL_FONT))
            assertEquals(DEFAULT_TERMINAL_FONT, TerminalSettingsStore(defaults).settings.fontName)
        } finally { defaults.removePersistentDomainForName(domain) }
    }

    private fun glyphs(font: NSFont, text: String): List<Int> = memScoped {
        val attributed = NSAttributedString.create(string = text,
            attributes = mapOf(NSFontAttributeName to font, NSLigatureAttributeName to 1))
        val retained = checkNotNull(CFBridgingRetain(attributed))
        try {
            val line = checkNotNull(CTLineCreateWithAttributedString(retained.reinterpret()))
            try {
                val runs = checkNotNull(CTLineGetGlyphRuns(line))
                buildList {
                    for (index in 0 until CFArrayGetCount(runs)) {
                        val run: CTRunRef = checkNotNull(CFArrayGetValueAtIndex(runs, index)).reinterpret()
                        val count = CTRunGetGlyphCount(run).toInt()
                        val ids = allocArray<CGGlyphVar>(count)
                        CTRunGetGlyphs(run, CFRangeMake(0, 0), ids)
                        repeat(count) { add(ids[it].toInt()) }
                    }
                }
            } finally { CFRelease(line) }
        } finally { CFRelease(retained) }
    }
}
