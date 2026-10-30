@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*

/** Main-thread confined. Retained fallback faces keep glyph IDs valid until disposal. */
internal class TerminalNativeShaper(font: NSFont) {
    private var primaryFont: NSFont? = font
    private val primaryFace: CTFontRef = checkNotNull(CFBridgingRetain(font)).reinterpret()
    private val faces = mutableListOf<CTFontRef>()
    private val sbixFaces = mutableListOf<Boolean>()
    private val resolved = linkedMapOf<String, TerminalResolvedFontCell>()
    private var disposed = false
    val fontCount: Int get() = faces.size

    fun face(index: Int): CTFontRef {
        check(!disposed)
        return faces[index]
    }

    fun isEmojiFont(index: Int): Boolean = CTFontGetSymbolicTraits(face(index)) and kCTFontColorGlyphsTrait != 0u
    fun isSbixFont(index: Int): Boolean { check(!disposed); return sbixFaces[index] }

    /** Resolve one complete terminal grapheme before run boundaries are chosen. */
    fun resolve(text: String): TerminalResolvedFontCell {
        check(!disposed)
        require(text.isNotEmpty())
        resolved[text]?.let { return it }
        val result = resolveFace(text)?.let { TerminalResolvedFontCell(it, text) }
            ?: resolveFace("�")?.let { TerminalResolvedFontCell(it, "�") }
            ?: TerminalResolvedFontCell(checkNotNull(resolveFace(" ")), " ")
        // Cell text is bounded by the terminal's grapheme limit. Do not retain an
        // unbounded vocabulary as a long-running shell emits new combinations.
        if (resolved.size == 512) resolved.remove(resolved.keys.first())
        resolved[text] = result
        return result
    }

    private fun resolveFace(text: String): Int? = memScoped {
        val characters = allocArray<UShortVar>(text.length)
        for (i in text.indices) characters[i] = text[i].code.toUShort()
        val string = checkNotNull(CFStringCreateWithCharacters(null, characters, text.length.toLong()))
        try {
            fun candidate(first: Int, length: Int): Int? {
                val font = CTFontCreateForString(primaryFace, string, CFRangeMake(first.toLong(), length.toLong())) ?: return null
                try {
                    // Ghostty discovery also rejects CoreText's LastResort: its
                    // boxes claim coverage for unsupported Unicode scalars.
                    if (interpretObjCPointer<NSFont>(font.rawValue).fontName == "LastResort") return null
                    return if (supports(font, text)) retainFace(font) else null
                }
                finally { CFRelease(font) }
            }
            // CoreText considers variation selectors when resolving the full text.
            candidate(0, text.length)?.let { return@memScoped it }
            // The first base may use the primary face even when a later mark needs
            // another face. Try each scalar's fallback, accepting only whole-cluster coverage.
            var index = 0
            while (index < text.length) {
                val length = if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
                candidate(index, length)?.let { return@memScoped it }
                index += length
            }
            null
        } finally { CFRelease(string) }
    }

    private fun supports(font: CTFontRef, text: String): Boolean = memScoped {
        val characters = allocArray<UShortVar>(2)
        val glyphs = allocArray<CGGlyphVar>(2)
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val length = if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
            val code = if (length == 2) 0x10000 + ((first.code - 0xd800) shl 10) + text[index + 1].code - 0xdc00 else first.code
            // Joiners and presentation selectors affect shaping; they need no visible glyph.
            if (code !in 0xfe00..0xfe0f && code !in 0xe0100..0xe01ef && code != 0x200c && code != 0x200d) {
                characters[0] = first.code.toUShort()
                if (length == 2) characters[1] = text[index + 1].code.toUShort()
                if (!CTFontGetGlyphsForCharacters(font, characters, glyphs, length.toLong())) return@memScoped false
            }
            index += length
        }
        true
    }

    fun shape(text: TerminalShapingText, font: Int? = null): List<TerminalShapedGlyph> {
        check(!disposed)
        if (text.text.isEmpty()) return emptyList()
        val attributed = NSAttributedString.create(string = text.text,
            attributes = mapOf(NSFontAttributeName to (if (font == null) checkNotNull(primaryFont)
                else interpretObjCPointer<NSFont>(face(font).rawValue)), NSLigatureAttributeName to 1))
        val retained = checkNotNull(CFBridgingRetain(attributed))
        try {
            return memScoped {
                val level = alloc<IntVar>(); level.value = 0
                val number = checkNotNull(CFNumberCreate(null, kCFNumberIntType, level.ptr))
                val keys = allocArray<COpaquePointerVar>(1); keys[0] = kCTTypesetterOptionForcedEmbeddingLevel
                val values = allocArray<COpaquePointerVar>(1); values[0] = number
                val options = checkNotNull(CFDictionaryCreate(null, keys, values, 1,
                    kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
                CFRelease(number)
                val typesetter = try {
                    checkNotNull(CTTypesetterCreateWithAttributedStringAndOptions(retained.reinterpret(), options))
                } finally { CFRelease(options) }
                try {
                    val line = checkNotNull(CTTypesetterCreateLine(typesetter, CFRangeMake(0, 0)))
                    try { readLine(text, line) } finally { CFRelease(line) }
                } finally { CFRelease(typesetter) }
            }
        } finally { CFRelease(retained) }
    }

    private fun readLine(text: TerminalShapingText, line: CTLineRef): List<TerminalShapedGlyph> = memScoped {
        val runs = checkNotNull(CTLineGetGlyphRuns(line))
        val glyphs = ArrayList<TerminalRawGlyph>(CTLineGetGlyphCount(line).toInt())
        var reorder = false
        for (i in 0 until CFArrayGetCount(runs).toInt()) {
            val run: CTRunRef = checkNotNull(CFArrayGetValueAtIndex(runs, i.toLong())).reinterpret()
            val attributes = checkNotNull(CTRunGetAttributes(run))
            val runFont: CTFontRef = checkNotNull(CFDictionaryGetValue(attributes, kCTFontAttributeName)).reinterpret()
            val face = retainFace(runFont)
            val count = CTRunGetGlyphCount(run).toInt()
            val ids = allocArray<CGGlyphVar>(count)
            val indices = allocArray<CFIndexVar>(count)
            val positions = allocArray<CGPoint>(count)
            val advances = allocArray<CGSize>(count)
            val range = CFRangeMake(0, 0)
            CTRunGetGlyphs(run, range, ids)
            CTRunGetStringIndices(run, range, indices)
            CTRunGetPositions(run, range, positions)
            CTRunGetAdvances(run, range, advances)
            val status = CTRunGetStatus(run)
            if (status and (kCTRunStatusNonMonotonic or kCTRunStatusRightToLeft) != 0u) reorder = true
            for (j in 0 until count) glyphs += TerminalRawGlyph(face, ids[j].toInt(), indices[j].toInt(),
                advances[j].width, positions[j].x, positions[j].y)
        }
        terminalPositionGlyphs(text, glyphs, reorder)
    }

    private fun retainFace(font: CTFontRef): Int {
        // CFEqual is not imported by Kotlin/Native's SDK bindings. CTFont and
        // NSFont are toll-free bridged, so NSObject equality preserves descriptors,
        // variations and feature settings rather than comparing just font names.
        val candidate = interpretObjCPointer<NSFont>(font.rawValue)
        val existing = faces.indexOfFirst { interpretObjCPointer<NSFont>(it.rawValue).isEqual(candidate) }
        if (existing >= 0) return existing
        CFRetain(font)
        faces += font
        sbixFaces += terminalNativeFontHasSbix(font)
        return faces.lastIndex
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        faces.forEach { CFRelease(it) }
        faces.clear()
        sbixFaces.clear()
        resolved.clear()
        CFRelease(primaryFace)
        primaryFont = null
    }
}
