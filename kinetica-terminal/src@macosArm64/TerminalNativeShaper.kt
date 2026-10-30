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
    private val faces = mutableListOf<CTFontRef>()
    private var disposed = false
    val fontCount: Int get() = faces.size

    fun face(index: Int): CTFontRef {
        check(!disposed)
        return faces[index]
    }

    fun shape(text: TerminalShapingText): List<TerminalShapedGlyph> {
        check(!disposed)
        if (text.text.isEmpty()) return emptyList()
        val attributed = NSAttributedString.create(string = text.text,
            attributes = mapOf(NSFontAttributeName to checkNotNull(primaryFont), NSLigatureAttributeName to 1))
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
        return faces.lastIndex
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        faces.forEach { CFRelease(it) }
        faces.clear()
        primaryFont = null
    }
}
