// OSC 5522 decoding and write accumulation adapted from Ghostty 1.4.0 (MIT):
// terminal/kitty/clipboard_command.zig, clipboard_write.zig and simd/base64.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal enum class KittyClipboardOperation { READ, WRITE, WDATA, WALIAS }
internal enum class KittyClipboardFailure { EINVAL, EFBIG }
internal class KittyClipboardException(val failure: KittyClipboardFailure) : Exception(failure.name)

internal data class KittyClipboardMetadata(
    val operation: KittyClipboardOperation,
    val location: TerminalClipboardLocation,
    val id: String,
    val mime: String,
    val password: String,
    val name: String,
)

internal sealed interface KittyClipboardParse {
    data object Dropped : KittyClipboardParse
    data class Invalid(val operation: KittyClipboardOperation) : KittyClipboardParse
    data class Valid(val metadata: KittyClipboardMetadata) : KittyClipboardParse
}

/** Validate the complete structure before decoding: the last duplicate field wins. */
internal fun parseKittyClipboardMetadata(raw: String): KittyClipboardParse {
    var type = ""; var location = ""; var id = ""; var mime = ""; var password = ""; var name = ""
    var start = 0
    while (true) {
        val end = raw.indexOf(':', start).let { if (it < 0) raw.length else it }
        val equal = raw.indexOf('=', start)
        if (equal < 0 || equal >= end) return KittyClipboardParse.Dropped
        when (raw.substring(start, equal)) {
            "type" -> type = raw.substring(equal + 1, end)
            "loc" -> location = raw.substring(equal + 1, end)
            "id" -> id = raw.substring(equal + 1, end)
            "mime" -> mime = raw.substring(equal + 1, end)
            "pw" -> password = raw.substring(equal + 1, end)
            "name" -> name = raw.substring(equal + 1, end)
        }
        if (end == raw.length) break
        start = end + 1
    }
    val operation = when (type) {
        "read" -> KittyClipboardOperation.READ
        "write" -> KittyClipboardOperation.WRITE
        "wdata" -> KittyClipboardOperation.WDATA
        "walias" -> KittyClipboardOperation.WALIAS
        else -> return KittyClipboardParse.Dropped
    }
    fun decode(value: String, limit: Int, ignoreOverflow: Boolean = false): String {
        if (value.length > (limit + 2) / 3 * 4) {
            if (ignoreOverflow) return ""
            throw KittyClipboardException(KittyClipboardFailure.EINVAL)
        }
        val bytes = decodeKittyClipboardPayload(value)
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw KittyClipboardException(KittyClipboardFailure.EINVAL) }
        if (bytes.size > limit) {
            if (ignoreOverflow) return ""
            throw KittyClipboardException(KittyClipboardFailure.EINVAL)
        }
        return text
    }
    return try {
        KittyClipboardParse.Valid(KittyClipboardMetadata(operation,
            if (location == "primary") TerminalClipboardLocation.PRIMARY else TerminalClipboardLocation.STANDARD,
            buildString(minOf(id.length, 512)) {
                for (char in id) {
                    if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_+.") append(char)
                    if (length == 512) break
                }
            },
            decode(mime, 256), decode(password, 128, ignoreOverflow = true), decode(name, 256)))
    } catch (_: KittyClipboardException) { KittyClipboardParse.Invalid(operation) }
}

/** Strict alphabet and required padding; unused trailing bits match Ghostty's permissive bits. */
internal fun decodeKittyClipboardPayload(encoded: String): ByteArray {
    val decoder = KittyClipboardDecoder()
    val output = ByteArray(encoded.length / 4 * 3)
    val size = decoder.feed(encoded, output)
    decoder.finish()
    return output.copyOf(size)
}

internal fun kittyClipboardMimeList(bytes: ByteArray): Sequence<String> {
    val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
    catch (_: CharacterCodingException) { throw KittyClipboardException(KittyClipboardFailure.EINVAL) }
    // Ghostty's iterator uses ASCII whitespace, including VT and FF.
    return sequence {
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start] in " \t\n\r\u000b\u000c") start++
            var end = start
            while (end < text.length && text[end] !in " \t\n\r\u000b\u000c") end++
            if (end > start) yield(text.substring(start, end))
            start = end
        }
    }
}

/** Retains only one incomplete quartet; padding ends a feed but permits a fresh next feed. */
private class KittyClipboardDecoder {
    private val carry = CharArray(4)
    private var count = 0
    fun maximum(encoded: String): Int = (encoded.length + count) / 4 * 3

    fun feed(encoded: String, output: ByteArray): Int {
        var written = 0
        for ((index, char) in encoded.withIndex()) {
            if (value(char) < 0 && !(count >= 2 && char == '=')) invalid()
            carry[count++] = char
            if (count != 4) continue
            count = 0
            val a = value(carry[0]); val b = value(carry[1])
            val c = value(carry[2]); val d = value(carry[3])
            if (c < 0 && carry[3] != '=') invalid()
            output[written++] = ((a shl 2) or (b shr 4)).toByte()
            if (c >= 0) output[written++] = ((b shl 4) or (c shr 2)).toByte()
            if (d >= 0) output[written++] = ((c shl 6) or d).toByte()
            if (carry[3] == '=' && index != encoded.lastIndex) invalid()
        }
        return written
    }

    fun finish() {
        val incomplete = count != 0
        count = 0
        if (incomplete) invalid()
    }

    private fun value(char: Char): Int = when (char) {
        in 'A'..'Z' -> char.code - 'A'.code
        in 'a'..'z' -> char.code - 'a'.code + 26
        in '0'..'9' -> char.code - '0'.code + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }
    private fun invalid(): Nothing = throw KittyClipboardException(KittyClipboardFailure.EINVAL)
}

internal data class KittyClipboardContent(val mime: String, val data: ByteArray)

/** One bounded write. No clipboard effect occurs until the owner accepts a successful commit. */
internal class KittyClipboardWrite(val metadata: KittyClipboardMetadata, private val limit: Int = 64 * 1024 * 1024) {
    init { require(metadata.operation == KittyClipboardOperation.WRITE); require(limit >= 0) }
    private class Entry(val mime: String) {
        var bytes = ByteArray(0)
        var size = 0
    }
    private val entries = linkedMapOf<String, Entry>()
    private val aliases = linkedMapOf<String, String>()
    private val decoder = KittyClipboardDecoder()
    private var current: Entry? = null
    private var total = 0
    private var failure: KittyClipboardFailure? = null
    private var finished = false

    /** All decoded regions count toward the budget, including representations later overwritten. */
    val accumulatedBytes: Int get() = total

    fun data(mime: String, payload: String) = guarded {
        require(mime.isNotEmpty() && mime.encodeToByteArray().size <= 256)
        if (current?.mime != mime) {
            if (current != null) decoder.finish()
            current = entries[mime]?.also { it.bytes = ByteArray(0); it.size = 0 }
                ?: if (entries.size < 64) Entry(mime).also { entries[mime] = it } else null
        }
        val entry = current ?: return@guarded
        // Decode only this packet before checking its actual decoded length, preserving error
        // precedence (invalid input beats EFBIG). The caller bounds raw OSC packet capture.
        val decoded = ByteArray(decoder.maximum(payload))
        val size = decoder.feed(payload, decoded)
        if (size > limit - total) throw KittyClipboardException(KittyClipboardFailure.EFBIG)
        if (size > entry.bytes.size - entry.size) {
            val needed = entry.size + size
            val capacity = maxOf(needed, minOf(limit.toLong(), maxOf(256L, entry.bytes.size.toLong() * 2)).toInt())
            entry.bytes = entry.bytes.copyOf(capacity)
        }
        decoded.copyInto(entry.bytes, entry.size, 0, size)
        entry.size += size; total += size
    }

    fun alias(mime: String, payload: String) = guarded {
        if (mime.isEmpty()) throw KittyClipboardException(KittyClipboardFailure.EINVAL)
        require(mime.encodeToByteArray().size <= 256)
        for (name in kittyClipboardMimeList(decodeKittyClipboardPayload(payload))) {
            if (name.encodeToByteArray().size > 256) continue
            if (name !in aliases && aliases.size == 64) break
            aliases[name] = mime
        }
    }

    fun commit(): List<KittyClipboardContent> = guarded {
        if (current != null) decoder.finish()
        val contents = linkedMapOf<String, ByteArray>()
        for (entry in entries.values) contents[entry.mime] = entry.bytes.copyOf(entry.size)
        // Resolve in insertion order against the evolving map: alias chains and collisions
        // intentionally follow Kitty/Ghostty dictionary behavior, not graph traversal.
        for ((alias, target) in aliases) contents[target]?.let { contents[alias] = it }
        finished = true
        entries.clear(); aliases.clear(); current = null
        contents.map { (mime, data) -> KittyClipboardContent(mime, data) }
    }

    private inline fun <T> guarded(action: () -> T): T {
        failure?.let { throw KittyClipboardException(it) }
        check(!finished) { "Clipboard transaction already committed" }
        return try { action() } catch (error: KittyClipboardException) {
            failure = error.failure
            entries.clear(); aliases.clear(); current = null
            throw error
        }
    }
}
