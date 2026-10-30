// Styled clipboard formatting follows Ghostty terminal/formatter.zig and style.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

public enum class TerminalCopyFormat { PLAIN, VT, HTML, MIXED }

/** A single clipboard item; null means that MIME representation is absent. */
public data class TerminalCopyData(public val text: String? = null, public val html: String? = null) {
    internal fun contents(): List<TerminalMimeClipboardContent> = buildList {
        text?.let { add(TerminalMimeClipboardContent("text/plain", it.encodeToByteArray())) }
        html?.let { add(TerminalMimeClipboardContent("text/html", it.encodeToByteArray())) }
    }
}

internal class TerminalCopySnapshot(
    private val range: TerminalSelectionRange,
    private val rows: Array<TerminalLine>,
    private val theme: TerminalTheme,
) {
    private fun line(index: Int): TerminalLine = rows[index - range.start.line]
    private var plain: TerminalCopyContent? = null
    private var trimmed: TerminalCopyContent? = null
    fun text(trim: Boolean, mappings: List<TerminalClipboardCodepointMap>): String {
        val content = if (trim) trimmed ?: terminalSelectionCopyContent(range, ::line, true).also { trimmed = it }
        else plain ?: terminalSelectionCopyContent(range, ::line, false).also { plain = it }
        return content.format(mappings)
    }
    fun data(format: TerminalCopyFormat, trim: Boolean, mappings: List<TerminalClipboardCodepointMap>): TerminalCopyData =
        terminalCopyData(range, ::line, theme, format, trim, mappings)
}

internal fun terminalCopyData(range: TerminalSelectionRange, line: (Int) -> TerminalLine, theme: TerminalTheme,
    format: TerminalCopyFormat, trim: Boolean, mappings: List<TerminalClipboardCodepointMap>): TerminalCopyData {
    fun plain() = terminalSelectionCopyContent(range, line, trim).format(mappings)
    return when (format) {
        TerminalCopyFormat.PLAIN -> TerminalCopyData(text = plain())
        TerminalCopyFormat.VT -> TerminalCopyData(text = terminalStyledSelection(range, line, theme, false, true, trim, mappings))
        TerminalCopyFormat.HTML -> TerminalCopyData(html = terminalStyledSelection(range, line, theme, true, true, trim, mappings))
        TerminalCopyFormat.MIXED -> TerminalCopyData(text = plain(), html = terminalStyledSelection(range, line, theme, true, false, trim, mappings))
    }
}

private data class CopyStyle(val flags: Int = 0, val rawFg: Int = -1, val rawBg: Int = -1, val rawUnderline: Int = -1,
    val fg: Int = -1, val bg: Int = -1, val underline: Int = -1) {
    val default: Boolean get() = flags == 0 && rawFg < 0 && rawBg < 0 && rawUnderline < 0
    fun has(flag: Int) = flags and flag != 0
    val underlineKind: Int get() = when {
        has(TerminalStyle.DOUBLE_UNDERLINE) -> 2
        has(TerminalStyle.UNDERCURL) -> 3
        has(TerminalStyle.DOTTED_UNDERLINE) -> 4
        has(TerminalStyle.DASHED_UNDERLINE) -> 5
        has(TerminalStyle.UNDERLINE) -> 1
        else -> 0
    }
}

private fun copyStyle(row: TerminalLine, x: Int): CopyStyle = CopyStyle(row.style(x), row.rawForeground(x),
    row.rawBackground(x), row.rawUnderlineColor(x), row.foreground(x), row.background(x), row.underlineColor(x))

private fun Int.rgb(separator: String) = "${this ushr 16 and 255}$separator${this ushr 8 and 255}$separator${this and 255}"
private fun Int.hexRgb() = toString(16).padStart(6, '0')

private fun StringBuilder.openCopyStyle(style: CopyStyle, html: Boolean) {
    if (!html) {
        append("\u001b[0m")
        for ((flag, sgr) in listOf(TerminalStyle.BOLD to 1, TerminalStyle.FAINT to 2, TerminalStyle.ITALIC to 3,
            TerminalStyle.BLINK to 5, TerminalStyle.INVERSE to 7, TerminalStyle.HIDDEN to 8,
            TerminalStyle.STRIKE to 9, TerminalStyle.OVERLINE to 53)) if (style.has(flag)) append("\u001b[${sgr}m")
        if (style.underlineKind == 1) append("\u001b[4m")
        else if (style.underlineKind > 1) append("\u001b[4:${style.underlineKind}m")
        for ((prefix, color) in listOf(38 to style.fg, 48 to style.bg, 58 to style.underline)) {
            if (color >= 0) append("\u001b[$prefix;2;${color.rgb(";")}m")
        }
        return
    }
    append("<div style=\"display: inline;")
    for ((property, color) in listOf("color" to style.fg, "background-color" to style.bg, "text-decoration-color" to style.underline)) {
        if (color >= 0) append("$property: rgb(${color.rgb(", ")});")
    }
    if (style.underlineKind > 0 || style.has(TerminalStyle.STRIKE or TerminalStyle.OVERLINE or TerminalStyle.BLINK)) {
        append("text-decoration-line:")
        if (style.underlineKind > 0) append(" underline")
        if (style.has(TerminalStyle.STRIKE)) append(" line-through")
        if (style.has(TerminalStyle.OVERLINE)) append(" overline")
        if (style.has(TerminalStyle.BLINK)) append(" blink")
        append(';')
    }
    if (style.underlineKind > 0) append("text-decoration-style: ${listOf("", "solid", "double", "wavy", "dotted", "dashed")[style.underlineKind]};")
    if (style.has(TerminalStyle.BOLD)) append("font-weight: bold;")
    if (style.has(TerminalStyle.ITALIC)) append("font-style: italic;")
    if (style.has(TerminalStyle.FAINT)) append("opacity: 0.5;")
    if (style.has(TerminalStyle.HIDDEN)) append("visibility: hidden;")
    if (style.has(TerminalStyle.INVERSE)) append("filter: invert(100%);")
    append("\">")
}

private fun StringBuilder.htmlCodepoint(cp: Int) {
    when (cp) {
        60 -> append("&lt;"); 62 -> append("&gt;"); 38 -> append("&amp;")
        34 -> append("&quot;"); 39 -> append("&#39;")
        else -> if (cp < 128) append(cp.toChar()) else append("&#$cp;")
    }
}

internal fun terminalStyledSelection(range: TerminalSelectionRange, line: (Int) -> TerminalLine, theme: TerminalTheme,
    html: Boolean, includeTheme: Boolean, trim: Boolean, mappings: List<TerminalClipboardCodepointMap>): String = buildString {
    if (html) {
        append("<div style=\"font-family: monospace; white-space: pre;")
        if (includeTheme) append("background-color: #${theme.background.hexRgb()};color: #${theme.foreground.hexRgb()};")
        append("\">")
    } else if (includeTheme) {
        fun oscColor(command: Int, color: Int) {
            val value = color.hexRgb()
            append("\u001b]$command;rgb:${value.take(2)}/${value.substring(2, 4)}/${value.takeLast(2)}\u001b\\")
        }
        oscColor(10, theme.foreground); oscColor(11, theme.background)
    }
    var style = CopyStyle()
    var link: TerminalLink? = null
    var blankRows = 0
    var blankCells = 0
    fun closeStyle() { if (!style.default) append(if (html) "</div>" else "\u001b[0m"); style = CopyStyle() }
    fun closeLink() { if (link != null) append(if (html) "</a>" else "\u001b]8;;\u001b\\"); link = null }
    for (y in range.start.line..range.end.line) {
        val row = line(y)
        row.readPreservingCompression {
            var from = if (range.rectangle || y == range.start.line) range.start.column else 0
            val to = if (range.rectangle || y == range.end.line) range.end.column else row.columns
            if ((range.rectangle || y == range.start.line) && row.isSpacerHead(from)) return@readPreservingCompression
            if (from < row.columns && row.width(from) == 0) from--
            val nonblank = (from until to).any { row.hasText(it) || !html && row.rawBackground(it) >= 0 }
            if (!nonblank) { blankRows++; return@readPreservingCompression }
            if (blankRows > 0) { closeStyle(); repeat(blankRows) { append(if (html) "\n" else "\r\n") }; blankRows = 0 }
            blankRows = if (row.wrapped) 0 else 1
            if (y == range.start.line || !line(y - 1).wrapped) blankCells = 0
            var x = from
            while (x < to) {
                if (row.width(x) == 0 || row.isSpacerHead(x)) { x++; continue }
                val nextStyle = copyStyle(row, x)
                if (!row.hasText(x) && nextStyle.default && row.width(x) == 1) { blankCells++; x++; continue }
                if (!html && blankCells > 0 && blankCells <= x - from) { append("\u001b[${blankCells}C"); blankCells = 0 }
                if (blankCells > 0) {
                    if (!html) { closeStyle(); closeLink() }
                    repeat(blankCells) { append(' ') }; blankCells = 0
                }
                if (style != nextStyle) {
                    if (html || nextStyle.default) closeStyle()
                    style = nextStyle
                    if (!style.default) openCopyStyle(style, html)
                }
                val nextLink = row.hyperlinkIdentity(x)
                if (!(link === nextLink || link?.sameIdentity(nextLink) == true)) {
                    if (html || nextLink == null) closeLink()
                    link = nextLink
                    nextLink?.value?.let { value ->
                        if (html) {
                            append("<a href=\"")
                            // Match Ghostty's URI byte escaping independently of cell codepoint maps.
                            value.uri.encodeToByteArray().forEach { htmlCodepoint(it.toInt() and 255) }
                            append("\">")
                        } else append("\u001b]8;${if (value.id.isEmpty()) "" else "id=${value.id}"};${value.uri}\u001b\\")
                    }
                }
                if (!row.hasText(x) && row.rawBackground(x) >= 0 && !html) {
                    var count = 1
                    while (x + count < to && !row.hasText(x + count) && row.width(x + count) == 1 &&
                        row.rawBackground(x + count) == row.rawBackground(x)) count++
                    append("\u001b[${count}X\u001b[${count}C"); x += count
                    continue
                }
                val text = if (!row.hasText(x)) " " else row.text(x).let {
                    if (mappings.isEmpty()) it else TerminalCopyContent(it, intArrayOf(0, it.length)).format(mappings)
                }
                if (html) text.terminalCodepoints().forEach { htmlCodepoint(it) } else append(text)
                x++
            }
        }
    }
    closeStyle(); closeLink()
    if (html) append("</div>") else if (!trim) repeat((blankRows - 1).coerceAtLeast(0)) { append("\r\n") }
}
