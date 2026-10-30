// Clipboard codepoint mappings follow Ghostty terminal/formatter.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Replace each matching source codepoint when copying text. Ranges are inclusive;
 * later entries win. Replacements may contain several characters or be empty, and are
 * not mapped again. Only written cell text is mapped, not formatter-generated whitespace. */
public data class TerminalClipboardCodepointMap(public val range: IntRange, public val replacement: String) {
    public constructor(codepoint: Int, replacement: String) : this(codepoint..codepoint, replacement)
    init {
        require(!range.isEmpty() && range.first >= 0 && range.last <= 0x10ffff) { "Invalid Unicode codepoint range" }
        var index = 0
        while (index < replacement.length) {
            val char = replacement[index++]
            if (char.isHighSurrogate()) {
                require(index < replacement.length && replacement[index].isLowSurrogate()) { "Invalid Unicode replacement" }
                index++
            } else require(!char.isLowSurrogate()) { "Invalid Unicode replacement" }
        }
    }
}

/** Captures which spans came from cells, so policies can change during a presentation hold
 * without mapping synthetic blanks/newlines or reading newer terminal contents. */
internal class TerminalCopyContent(val text: String, private val mappable: IntArray) {
    fun format(mappings: List<TerminalClipboardCodepointMap>): String {
        if (mappings.isEmpty() || mappable.isEmpty()) return text
        return buildString(text.length) {
            var position = 0
            for (span in mappable.indices step 2) {
                val first = mappable[span]; val end = mappable[span + 1]
                append(text, position, first)
                position = first
                while (position < end) {
                    val start = position
                    val char = text[position++]
                    val codepoint = if (char.isHighSurrogate() && position < end && text[position].isLowSurrogate()) {
                        0x10000 + ((char.code - 0xd800) shl 10) + (text[position++].code - 0xdc00)
                    } else char.code
                    val mapping = mappings.lastOrNull { codepoint in it.range }
                    if (mapping == null) append(text, start, position) else append(mapping.replacement)
                }
            }
            append(text, position, text.length)
        }
    }

    companion object { val EMPTY = TerminalCopyContent("", intArrayOf()) }
}

internal fun terminalSelectionCopyContent(range: TerminalSelectionRange, line: (Int) -> TerminalLine,
    trimTrailingSpaces: Boolean): TerminalCopyContent {
    val mappable = ArrayList<Int>()
    var spanStart = -1
    var spanEnd = 0
    val text = terminalSelectionText(range, line, trimTrailingSpaces) { start, end ->
        if (spanStart < 0) spanStart = start
        else if (spanEnd != start) {
            mappable.add(spanStart); mappable.add(spanEnd); spanStart = start
        }
        spanEnd = end
    }
    if (spanStart >= 0) { mappable.add(spanStart); mappable.add(spanEnd) }
    return TerminalCopyContent(text, mappable.toIntArray())
}
