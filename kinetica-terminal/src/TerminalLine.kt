package io.heapy.kinetica.terminal


/** RGB colors use 0xRRGGBB; -1 means the terminal's default color. */
public object TerminalStyle {
    public const val DEFAULT: Int = -1
    public const val BOLD: Int = 1
    public const val FAINT: Int = 2
    public const val ITALIC: Int = 4
    public const val UNDERLINE: Int = 8
    public const val INVERSE: Int = 16
    public const val HIDDEN: Int = 32
    public const val STRIKE: Int = 64
    public const val DOUBLE_UNDERLINE: Int = 128
    public const val UNDERCURL: Int = 256
    public const val DOTTED_UNDERLINE: Int = 512
    public const val DASHED_UNDERLINE: Int = 1024
    public const val UNDERLINES: Int = UNDERLINE or DOUBLE_UNDERLINE or UNDERCURL or DOTTED_UNDERLINE or DASHED_UNDERLINE
}

public data class TerminalHyperlink(val id: String, val uri: String)
// Identity, rather than string hashing, makes retaining the same OSC 8 pen O(1) per cell.
internal class TerminalLink(val value: TerminalHyperlink) {
    val storageBytes: Long = 96L + 2L * (value.id.length.toLong() + value.uri.length)
}
internal data class CellAttributes(val underlineColor: Int = -1, val hyperlink: TerminalLink? = null)

/** Packed primitive storage; no object allocation per ordinary character. */
public class TerminalLine internal constructor(public val columns: Int, internal val colors: TerminalColors? = null,
    internal val hyperlinkLimit: Int = 64 * 1024) {
    private val data = IntArray(columns * 4)
    private var combined: Array<String?>? = null
    private var attributes: Array<CellAttributes?>? = null
    private var combinedCount = 0
    private var attributeCount = 0
    private var combinedBytes = 0L
    private var links: MutableMap<TerminalLink, Int>? = null
    private var linkHighWater = 0
    // Only selected rows have listeners. Normal output allocates no mutation events.
    private var textObservers: MutableList<(Int, Int) -> Unit>? = null
    internal var historySlot: Int = -1
    internal var recycleEpoch: Long = 0
    internal fun observeText(observer: (Int, Int) -> Unit): TerminalDisposable {
        val listeners = textObservers ?: mutableListOf<(Int, Int) -> Unit>().also { textObservers = it }
        listeners += observer
        return TerminalDisposable { listeners.remove(observer); if (listeners.isEmpty()) textObservers = null }
    }
    private fun changed(first: Int, end: Int) { textObservers?.forEach { it(first, end) } }
    internal var hyperlinkBytes: Long = 0
        private set
    internal val storageBytes: Long get() = 128L + data.size * 4L + combinedBytes + hyperlinkBytes +
        (if (combined == null) 0 else 32L + columns * 8L) +
        (if (attributes == null) 0 else 32L + columns * 8L + attributeCount * 32L) + linkHighWater * 64L
    public var wrapped: Boolean = false
        internal set(value) {
            if (field != value) changed(columns, columns + 1)
            field = value
        }
    internal var wrapContinuation: Boolean = false

    init { clear(0, columns, TerminalStyle.DEFAULT, TerminalStyle.DEFAULT, 0) }

    public fun codePoint(column: Int): Int = data[index(column)] and 0x1fffff
    public fun width(column: Int): Int = (data[index(column)] ushr 21) and 3
    public fun foreground(column: Int): Int = resolveColor(rawForeground(column))
    internal fun rawForeground(column: Int): Int = data[index(column) + 1]
    public fun background(column: Int): Int = resolveColor(rawBackground(column))
    internal fun rawBackground(column: Int): Int = data[index(column) + 2]
    public fun style(column: Int): Int = data[index(column) + 3]
    public fun isProtected(column: Int): Boolean = data[index(column)] and PROTECTED != 0
    public fun underlineColor(column: Int): Int = resolveColor(rawUnderlineColor(column))
    internal fun rawUnderlineColor(column: Int): Int { index(column); return attributes?.get(column)?.underlineColor ?: -1 }
    private fun resolveColor(value: Int): Int = if (value >= INDEXED_COLOR)
        colors?.palette(value and 255) ?: terminalPalette(value and 255) else value
    public fun hyperlink(column: Int): TerminalHyperlink? { index(column); return attributes?.get(column)?.hyperlink?.value }
    internal fun isCombined(column: Int): Boolean = combined?.get(column) != null
    internal fun isSpacerHead(column: Int): Boolean = data[index(column)] and SPACER_HEAD != 0
    internal fun hasText(column: Int): Boolean = data[index(column)] and WRITTEN != 0 && width(column) != 0
    internal fun isEmpty(column: Int): Boolean = !hasText(column) && width(column) == 1 &&
        !isSpacerHead(column) && rawBackground(column) == TerminalStyle.DEFAULT
    internal fun hasText(): Boolean = (0 until columns).any { hasText(it) }
    internal fun appendTextTo(builder: StringBuilder, column: Int) {
        val value = combined?.get(column)
        if (value != null) builder.append(value)
        else {
            val code = codePoint(column)
            if (code in 1..0xffff) builder.append(code.toChar())
            else if (code > 0xffff) {
                builder.append(((code - 0x10000) / 0x400 + 0xd800).toChar())
                builder.append(((code - 0x10000) % 0x400 + 0xdc00).toChar())
            }
        }
    }
    public fun text(column: Int): String { index(column); return combined?.get(column) ?: codePointString(codePoint(column)) }

    public fun text(trimEnd: Boolean = true): String = buildString {
        for (x in 0 until columns) if (width(x) != 0) appendTextTo(this, x)
    }.let { if (trimEnd) it.trimEnd(' ') else it }

    private fun index(column: Int): Int {
        require(column in 0 until columns)
        return column * 4
    }

    internal fun put(x: Int, code: Int, width: Int, fg: Int, bg: Int, style: Int, extra: CellAttributes? = null, protectedCell: Boolean = false) {
        changed(x, x + 1)
        val i = x * 4
        data[i] = code or (width shl 21) or WRITTEN or (if (protectedCell) PROTECTED else 0)
        data[i + 1] = fg
        data[i + 2] = bg
        data[i + 3] = style
        setCombined(x, null)
        setAttributes(x, extra)
    }

    internal fun combine(x: Int, code: Int) {
        val old = text(x)
        // Ghostty's limit is 64 suffix codepoints, independent of UTF-16 storage length.
        var count = 0; var offset = 0
        while (offset < old.length) { offset += if (old[offset] in '\ud800'..'\udbff') 2 else 1; count++ }
        if (count <= 64) setCombined(x, old + codePointString(code))
    }

    private fun setCombined(x: Int, value: String?) {
        val old = combined?.get(x)
        if (old === value) return
        changed(x, x + 1)
        if (old != null) { combinedBytes -= 32L + old.length * 2L; combinedCount-- }
        if (value != null) {
            if (combined == null) combined = arrayOfNulls(columns)
            combinedBytes += 32L + value.length * 2L; combinedCount++
        }
        combined?.set(x, value)
        if (combinedCount == 0) combined = null
    }

    private fun setAttributes(x: Int, value: CellAttributes?) {
        val old = attributes?.get(x)
        if (old === value) return
        val oldLink = old?.hyperlink
        var next = value
        if (oldLink !== value?.hyperlink) {
            if (oldLink != null) {
                val count = checkNotNull(links?.get(oldLink))
                if (count == 1) { links!!.remove(oldLink); hyperlinkBytes -= oldLink.storageBytes }
                else links!![oldLink] = count - 1
            }
            val link = value?.hyperlink
            if (link != null) {
                val count = links?.get(link) ?: 0
                if (count == 0 && link.storageBytes > hyperlinkLimit.toLong() - hyperlinkBytes) {
                    // Drop only the destination metadata, never truncate it into another URI.
                    next = if (value.underlineColor == -1) null else CellAttributes(value.underlineColor)
                } else {
                    if (links == null) links = mutableMapOf()
                    links!![link] = count + 1
                    if (count == 0) hyperlinkBytes += link.storageBytes
                    linkHighWater = maxOf(linkHighWater, links!!.size)
                }
            }
            if (links?.isEmpty() == true) { links = null; linkHighWater = 0 }
        }
        if (old != null) attributeCount--
        if (next != null) {
            if (attributes == null) attributes = arrayOfNulls(columns)
            attributeCount++
        }
        attributes?.set(x, next)
        if (attributeCount == 0) attributes = null
    }

    internal fun setWidth(x: Int, width: Int) {
        changed(x, x + 1)
        val i = x * 4
        val wasTail = this.width(x) == 0
        data[i] = (data[i] and (3 shl 21).inv()) or (width shl 21)
        if (wasTail && width == 1) data[i] = (data[i] and WRITTEN.inv()) or 32
    }

    internal fun copyGrapheme(from: TerminalLine, source: Int, target: Int) {
        setCombined(target, from.combined?.get(source))
    }

    internal fun clear(start: Int, end: Int, fg: Int, bg: Int, style: Int) {
        for (x in start until end) {
            put(x, 32, 1, fg, bg, style)
            data[x * 4] = data[x * 4] and WRITTEN.inv()
        }
    }

    internal fun putSpacerHead(x: Int, fg: Int = -1, bg: Int = -1, style: Int = 0,
        extra: CellAttributes? = null, protectedCell: Boolean = false) {
        put(x, 32, 1, fg, bg, style, extra, protectedCell)
        data[x * 4] = (data[x * 4] and WRITTEN.inv()) or SPACER_HEAD
    }

    internal fun replaceWithSpacerHead(x: Int) {
        changed(x, x + 1)
        data[x * 4] = (data[x * 4] and PROTECTED) or 32 or (1 shl 21) or SPACER_HEAD
        setCombined(x, null)
    }

    internal fun copyCell(from: TerminalLine, source: Int, target: Int) {
        changed(target, target + 1)
        from.data.copyInto(data, target * 4, source * 4, source * 4 + 4)
        setCombined(target, from.combined?.get(source))
        setAttributes(target, from.attributes?.get(source))
    }

    internal fun snapshot(colors: TerminalColors): TerminalLine = TerminalLine(columns, colors, hyperlinkLimit).also {
        for (x in 0 until columns) it.copyCell(this, x, x)
        it.wrapped = wrapped; it.wrapContinuation = wrapContinuation
    }

    internal fun resized(columns: Int): TerminalLine = TerminalLine(columns, colors, hyperlinkLimit).also { next ->
        for (x in 0 until minOf(this.columns, columns)) next.copyCell(this, x, x)
        next.wrapped = wrapped && columns <= this.columns
        next.wrapContinuation = wrapContinuation && columns <= this.columns
        next.repairWideCells()
    }

    internal fun repairWideCells() {
        for (x in 0 until columns) {
            if (width(x) == 2 && (x == columns - 1 || width(x + 1) != 0) ||
                width(x) == 0 && (x == 0 || width(x - 1) != 2)) {
                clear(x, x + 1, rawForeground(x), rawBackground(x), style(x))
            }
        }
    }

    private companion object {
        const val WRITTEN = 1 shl 23
        const val SPACER_HEAD = 1 shl 24
        const val PROTECTED = 1 shl 25
    }
}

internal class LineRing(private val capacity: Int, private val byteLimit: Long) {
    private val lines = arrayOfNulls<TerminalLine>(capacity)
    private var start = 0
    var size: Int = 0
        private set
    var storageBytes: Long = 0
        private set

    operator fun get(index: Int): TerminalLine {
        require(index in 0 until size)
        return lines[(start + index) % capacity]!!
    }

    fun indexOf(line: TerminalLine): Int {
        val slot = line.historySlot
        if (slot !in lines.indices || lines[slot] !== line) return -1
        val index = (slot - start + capacity) % capacity
        return if (index < size) index else -1
    }

    fun add(line: TerminalLine): TerminalLine? {
        var reused: TerminalLine? = null
        // An oversized newest row still retires older history, preserving a contiguous suffix.
        while (size > 0 && (size == capacity || line.storageBytes > byteLimit - storageBytes)) {
            val old = lines[start]!!
            lines[start] = null
            start = (start + 1) % capacity
            size--; storageBytes -= old.storageBytes
            reused = old
        }
        if (capacity == 0 || line.storageBytes > byteLimit) return line
        val slot = (start + size++) % capacity
        lines[slot] = line; line.historySlot = slot
        storageBytes += line.storageBytes
        return reused
    }

    fun clear() { lines.fill(null); start = 0; size = 0; storageBytes = 0 }
}

internal fun codePointString(code: Int): String = when {
    code <= 0 -> ""
    code <= 0xffff -> code.toChar().toString()
    else -> ((code - 0x10000) / 0x400 + 0xd800).toChar().toString() +
        ((code - 0x10000) % 0x400 + 0xdc00).toChar()
}

/** Pinned Unicode terminal width (0/1/2); ambiguous characters occupy one cell. */
public fun terminalCharacterWidth(code: Int): Int = TerminalUnicodeData.properties(code) and 3
