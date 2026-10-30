// Glyph protocol and glyf decoding adapted from Ghostty 1.4's terminal/apc/glyph
// and font/opentype/glyf.zig. Copyright (c) 2024 Mitchell Hashimoto, contributors.
// MIT license and pinned source: ../third-party/GHOSTTY-LICENSE and ghostty.json.
package io.heapy.kinetica.terminal

/** Experimental protocol state, as in Ghostty 1.4: outlines are registered but not rendered. */
internal class TerminalGlyphProtocol(private val reply: (ByteArray) -> Unit, private val changed: () -> Unit) {
    internal data class Entry(val contours: ShortArray, val points: IntArray, val upm: Long,
        val advance: Long, val height: Long, val width: Int, val size: String, val align: String, val pad: List<Double>)
    private val entries = linkedMapOf<Int, Entry>()
    val count: Int get() = entries.size
    fun entry(codepoint: Int): Entry? = entries[codepoint]
    fun reset() { entries.clear() }

    fun command(raw: String) {
        val value = if (raw.length == 1) "$raw;" else raw
        if (value.length < 2 || value[1] != ';') return
        val verb = value[0]
        if (verb == 's') { reply("\u001b_25a1;s;fmt=glyf\u001b\\".encodeToByteArray()); return }
        if (verb != 'q' && verb != 'r' && verb != 'c') return
        val last = value.lastIndexOf(';')
        if (verb == 'r' && last <= 1) return
        val options = mutableMapOf<String, String>()
        val end = if (verb == 'r') last else value.length
        var start = 2
        while (start < end) {
            val next = value.indexOf(';', start).let { if (it < 0 || it > end) end else it }
            val equals = value.indexOf('=', start)
            if (equals in start until next) {
                val key = value.substring(start, equals)
                if (key in OPTION_KEYS) options[key] = value.substring(equals + 1, next)
            }
            start = next + 1
        }
        val cp = options["cp"]?.let { integer(it, 16, 0x1fffff) }?.toInt()
        val response = when (verb) {
            's' -> "s;fmt=glyf"
            'q' -> if (cp == null) null else "q;cp=${cp.toString(16)};status=${if (cp in entries) "glossary" else ""}"
            'r' -> {
                val reason = try {
                    if (cp == null) fail()
                    val entry = decode(options, value.substring(last + 1))
                    if (!privateUse(cp)) fail("out_of_namespace")
                    entries.remove(cp); entries[cp] = entry
                    if (entries.size > 1024) entries.remove(entries.keys.first())
                    null
                } catch (error: GlyphFailure) { error.reason }
                changed()
                val verbosity = options["reply"]
                if (verbosity == "0" || verbosity == "2" && reason == null) null
                else "r;cp=${(cp ?: 0).toString(16)};status=${if (reason == null) 0 else 1}" +
                    (reason?.let { ";reason=$it" } ?: "")
            }
            'c' -> {
                val reason = when {
                    cp != null && !privateUse(cp) -> "out_of_namespace"
                    cp == null && "cp" in options -> "malformed_payload"
                    else -> { if (cp == null) entries.clear() else entries.remove(cp); null }
                }
                changed()
                "c;status=${if (reason == null) 0 else 1}" + (reason?.let { ";reason=$it" } ?: "")
            }
            else -> null
        }
        response?.let { reply("\u001b_25a1;$it\u001b\\".encodeToByteArray()) }
    }

    private fun decode(options: Map<String, String>, payload: String): Entry {
        val fmt = options["fmt"] ?: "glyf"
        if (fmt !in listOf("glyf", "colrv0", "colrv1")) fail()
        val upm = options["upm"]?.let { integer(it, 10, 0xffffffffL) ?: fail() } ?: 1000L
        val aw = options["aw"]?.let { integer(it, 10, 0xffffffffL) ?: fail() } ?: upm
        val lh = options["lh"]?.let { integer(it, 10, 0xffffffffL) ?: fail() } ?: upm
        if (upm == 0L || aw == 0L || lh == 0L) fail()
        val width = when (options["width"]) { null, "1" -> 1; "2" -> 2; else -> fail() }
        val size = options["size"] ?: "height"
        if (size !in listOf("height", "advance", "contain", "cover", "stretch")) fail()
        val align = options["align"] ?: "center,center"
        val axes = align.split(',', limit = 3)
        if (axes.size != 2 || axes[0] !in listOf("start", "center", "end") ||
            axes[1] !in listOf("start", "center", "end", "baseline")) fail()
        val padParts = (options["pad"] ?: "0,0,0,0").split(',', limit = 5)
        if (padParts.size != 4) fail()
        var pad = padParts.map { fraction(it) ?: fail() }
        if (pad[0] + pad[2] >= 1 || pad[1] + pad[3] >= 1) pad = listOf(0.0, 0.0, 0.0, 0.0)
        if (fmt != "glyf") fail()
        val data = base64(payload)
        val reader = GlyphReader(data)
        val contoursCount = reader.word().toShort().toInt()
        repeat(4) { reader.word() }
        if (contoursCount < 0) fail("composite_unsupported")
        val contours = ShortArray(contoursCount)
        var previous = -1
        for (i in contours.indices) {
            val end = reader.word()
            if (end <= previous) fail()
            contours[i] = end.toShort(); previous = end
        }
        if (contoursCount != 0 || data.size >= 12) {
            if (reader.word() != 0) fail("hinting_unsupported")
        }
        val pointCount = previous + 1
        // Original Outline.Point is 12 bytes and its individual allocation is limited to 64 KiB.
        if (pointCount > 65536 / 12) fail("payload_too_large")
        val points = IntArray(pointCount * 3)
        val flags = IntArray(pointCount)
        var i = 0
        while (i < pointCount) {
            val flag = reader.byte()
            val copies = if (flag and 8 != 0) reader.byte() + 1 else 1
            if (copies > pointCount - i) fail()
            repeat(copies) { flags[i++] = flag }
        }
        for (axis in 0..1) {
            var coordinate = 0
            val short = 2 shl axis
            val sign = 16 shl axis
            for (p in 0 until pointCount) {
                val flag = flags[p]
                val delta = if (flag and short != 0) reader.byte() * (if (flag and sign != 0) 1 else -1)
                    else if (flag and sign == 0) reader.word().toShort().toInt() else 0
                coordinate += delta
                points[p * 3 + axis] = coordinate
                points[p * 3 + 2] = flag and 1
            }
        }
        return Entry(contours, points, upm, aw, lh, width, size, align, pad)
    }

    private class GlyphReader(private val data: ByteArray) {
        private var offset = 0
        fun byte(): Int { if (offset == data.size) fail(); return data[offset++].toInt() and 255 }
        fun word(): Int = byte() * 256 + byte()
    }

    private fun base64(value: String): ByteArray {
        if (value.length % 4 != 0) fail()
        val padding = if (value.endsWith("==")) 2 else if (value.endsWith('=')) 1 else 0
        val length = value.length / 4 * 3 - padding
        if (length > 65536) fail("payload_too_large")
        if (length < 0) fail()
        val data = ByteArray(length)
        var output = 0
        for (i in value.indices step 4) {
            var bits = 0
            for (j in 0..3) {
                val c = value[i + j]
                val digit = if (c == '=' && i + j >= value.length - padding) 0 else ALPHABET.indexOf(c)
                if (digit < 0) fail()
                bits = (bits shl 6) or digit
            }
            if (i + 4 == value.length && (padding == 1 && bits and 255 != 0 || padding == 2 && bits and 65535 != 0)) fail()
            for (part in 0..2) if (output < length) data[output++] = (bits shr (16 - part * 8)).toByte()
        }
        return data
    }

    private fun privateUse(cp: Int): Boolean = cp in 0xe000..0xf8ff || cp in 0xf0000..0xffffd || cp in 0x100000..0x10fffd
    private fun integer(value: String, radix: Int, maximum: Long): Long? {
        var text = value
        val negative = text.startsWith('-')
        if (text.startsWith('+') || negative) text = text.drop(1)
        if (text.isEmpty() || text.first() == '_' || text.last() == '_') return null
        var number = 0L
        for (c in text) {
            if (c == '_') continue
            val digit = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> return null }
            if (digit >= radix) return null
            if (number > (maximum - digit) / radix) return null
            number = number * radix + digit
        }
        return number.takeIf { !negative || it == 0L }
    }
    private fun fraction(value: String): Double? {
        var i = if (value.startsWith('+') || value.startsWith('-')) 1 else 0
        val negative = value.startsWith('-')
        var integer = 0.0
        var digits = 0
        while (i < value.length && value[i] != '.') {
            val c = value[i++]; if (c !in '0'..'9') return null
            integer = integer * 10 + (c - '0'); digits++
        }
        var frac = 0.0; var scale = 1.0
        if (i < value.length) {
            i++
            while (i < value.length) {
                val c = value[i++]; if (c !in '0'..'9') return null
                if (scale < 1e15) { frac = frac * 10 + (c - '0'); scale *= 10 }; digits++
            }
        }
        val result = (integer + frac / scale) * if (negative) -1 else 1
        return result.takeIf { digits > 0 && it >= 0 && it <= 1 }
    }
    private class GlyphFailure(val reason: String) : Exception(reason)
    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val OPTION_KEYS = setOf("cp", "fmt", "reply", "upm", "aw", "lh", "width", "size", "align", "pad")
        fun fail(reason: String = "malformed_payload"): Nothing = throw GlyphFailure(reason)
    }
}

/** Recognizes only the glyph prefix; Kitty and unknown APCs allocate no glyph payload. */
internal class TerminalGlyphApc(private val command: (String) -> Unit, private val limit: Int = 1024 * 1024) {
    private var prefix = -1
    private var data = ByteArray(0)
    private var size = 0
    val retainedBytes: Int get() = data.size
    fun start() { reset(); prefix = 0 }
    fun feed(byte: Int) {
        if (prefix < 0) return
        if (prefix < 5) {
            if (byte != "25a1;"[prefix].code) { reset(); return }
            prefix++; return
        }
        if (size == limit) { reset(); return }
        if (size == data.size) data = data.copyOf(maxOf(256, data.size * 2).coerceAtMost(limit))
        data[size++] = byte.toByte()
    }
    fun end(terminator: Int) {
        val value = if (prefix == 5 && (terminator == 27 || terminator == 0x9c)) data.decodeToString(0, size) else null
        reset()
        value?.let(command)
    }
    fun reset() { prefix = -1; data = ByteArray(0); size = 0 }
}
