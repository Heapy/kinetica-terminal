// Kitty graphics command parsing adapted from Ghostty's kitty/graphics_command.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64

/** Internal wire representation. Numeric fields preserve all 32 bits, including signed z/H/V. */
internal class TerminalGraphicsCommand(
    val action: Char,
    private val values: IntArray,
    private val present: Long,
    val data: ByteArray,
) {
    fun value(key: Char): Long? {
        val index = graphicsKeyIndex(key.code)
        return if (index < 0 || present and (1L shl index) == 0L) null else values[index].toLong() and 0xffffffffL
    }
    fun unsigned(key: Char, default: Long = 0): Long = value(key) ?: default
    fun signed(key: Char): Int = unsigned(key).toInt()
    val quiet: Int get() = unsigned('q').coerceAtMost(2).toInt()
    val imageId: Long get() = unsigned('i')
    val imageNumber: Long get() = unsigned('I')
    val placementId: Long get() = unsigned('p')
    val format: Int get() = when (unsigned('f', 32)) { 0L, 32L -> 32; 24L -> 24; 100L -> 100; else -> -1 }
    val medium: Char get() = unsigned('t', 'd'.code.toLong()).toInt().toChar()
    val compressed: Boolean get() = value('o') != null
    val moreChunks: Boolean get() = medium == 'd' && unsigned('m') != 0L
    val moveCursor: Boolean get() = unsigned('C') != 1L
    val virtual: Boolean get() = unsigned('U') != 0L
    val deleteAction: Char get() = unsigned('d', 'a'.code.toLong()).toInt().toChar()
    /** Keep transmission/display metadata without retaining the first payload chunk. */
    fun withoutData(): TerminalGraphicsCommand = TerminalGraphicsCommand(action, values, present, ByteArray(0))
}

private fun graphicsKeyIndex(key: Int): Int = when (key) {
    in 97..122 -> key - 97
    in 65..90 -> key - 65 + 26
    else -> -1
}

/**
 * One command after the APC `G` identifier. Header storage is constant; payload storage is
 * bounded and released at completion, failure or reset. Failed commands ignore their suffix.
 */
internal class TerminalGraphicsCommandParser(private val maxEncodedBytes: Int) {
    init { require(maxEncodedBytes >= 0) }
    private enum class State { KEY, KEY_IGNORE, VALUE, VALUE_IGNORE, DATA, FAILED, COMPLETE }
    private var state = State.KEY
    private val values = IntArray(52)
    private var present = 0L
    private val temporary = ByteArray(11)
    private var temporarySize = 0
    private var key = 0
    private var payload = EMPTY
    private var payloadSize = 0
    val retainedPayloadBytes: Int get() = payload.size
    val failed: Boolean get() = state == State.FAILED

    fun reset() {
        state = State.KEY; present = 0; temporarySize = 0; key = 0
        payload = EMPTY; payloadSize = 0
    }

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var index = offset
        val end = offset + length
        while (index < end && state != State.FAILED && state != State.COMPLETE) {
            if (state == State.DATA) {
                if (!reserve(end - index)) return
                bytes.copyInto(payload, payloadSize, index, end)
                payloadSize += end - index
                return
            }
            feed(bytes[index++].toInt() and 255)
        }
    }

    fun feed(byte: Int) {
        require(byte in 0..255)
        when (state) {
            State.KEY -> when (byte) {
                61 -> {
                    if (temporarySize == 1) { key = temporary[0].toInt() and 255; state = State.VALUE }
                    else state = State.VALUE_IGNORE
                    temporarySize = 0
                }
                59 -> state = State.DATA
                else -> accumulate(byte, State.KEY_IGNORE)
            }
            State.KEY_IGNORE -> if (byte == 61) state = State.VALUE_IGNORE
            State.VALUE -> when (byte) {
                44 -> finishValue(State.KEY)
                59 -> finishValue(State.DATA)
                else -> accumulate(byte, State.VALUE_IGNORE)
            }
            State.VALUE_IGNORE -> when (byte) {
                44 -> state = State.KEY_IGNORE
                59 -> state = State.DATA
            }
            State.DATA -> if (reserve(1)) payload[payloadSize++] = byte.toByte()
            State.FAILED, State.COMPLETE -> Unit
        }
    }

    fun complete(): TerminalGraphicsCommand? {
        when (state) {
            State.KEY, State.KEY_IGNORE -> fail()
            State.VALUE -> finishValue(State.DATA)
            else -> Unit
        }
        if (state == State.FAILED || state == State.COMPLETE) return null
        fun value(key: Char): Long? {
            val index = graphicsKeyIndex(key.code)
            return if (present and (1L shl index) == 0L) null else values[index].toLong() and 0xffffffffL
        }
        val action = value('a') ?: 't'.code.toLong()
        if (action > 255 || action.toInt().toChar() !in "qtTpdfac") { fail(); return null }
        if (action.toInt().toChar() in "qtTf") {
            val medium = value('t')
            val compression = value('o')
            if (medium != null && (medium > 255 || medium.toInt().toChar() !in "dfts") ||
                compression != null && compression != 'z'.code.toLong()) { fail(); return null }
        }
        if (action == 'd'.code.toLong()) {
            val what = value('d') ?: 'a'.code.toLong()
            if (what > 255 || what.toInt().toChar() !in "aAiInNcCfFpPqQrRxXyYzZ") { fail(); return null }
        }
        val decoded = try {
            normalizeTail()
            Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload, 0, payloadSize)
        } catch (_: IllegalArgumentException) { fail(); return null }
        payload = EMPTY; payloadSize = 0; state = State.COMPLETE
        return TerminalGraphicsCommand(action.toInt().toChar(), values.copyOf(), present, decoded)
    }

    private fun reserve(additional: Int): Boolean {
        if (additional > maxEncodedBytes - payloadSize) { fail(); return false }
        val required = payloadSize + additional
        if (required > payload.size) payload = payload.copyOf(
            maxOf(required.toLong(), maxOf(256L, payload.size.toLong() * 2)).coerceAtMost(maxEncodedBytes.toLong()).toInt())
        return true
    }

    private fun accumulate(byte: Int, overflow: State) {
        if (temporarySize == temporary.size) { state = overflow; temporarySize = 0 }
        else temporary[temporarySize++] = byte.toByte()
    }

    private fun finishValue(next: State) {
        state = next
        val value = if (temporarySize == 1 && temporary[0].toInt() !in 48..57) temporary[0].toInt() and 255
        else parseNumber() ?: run { fail(); return }
        val index = graphicsKeyIndex(key)
        if (index >= 0) { values[index] = value; present = present or (1L shl index) }
        temporarySize = 0
    }

    private fun parseNumber(): Int? {
        if (temporarySize == 0) return null
        var index = 0
        val negative = temporary[0].toInt() == 45
        if (negative || temporary[0].toInt() == 43) index++
        if (index == temporarySize || temporary[index].toInt() == 95 || temporary[temporarySize - 1].toInt() == 95) return null
        val signed = key == 'z'.code || key == 'H'.code || key == 'V'.code
        val limit = if (signed) (if (negative) 2147483648L else 2147483647L) else (if (negative) 0L else 4294967295L)
        var number = 0L
        while (index < temporarySize) {
            val byte = temporary[index++].toInt()
            if (byte == 95) continue // Ghostty's graphics parser uses Zig's underscore-permitting integer parser.
            if (byte !in 48..57) return null
            number = number * 10 + byte - 48
            if (number > limit) return null
        }
        return (if (negative) -number else number).toInt()
    }

    private fun normalizeTail() {
        var length = 0
        while (length < payloadSize && payload[length] != 61.toByte()) length++
        val mask = when (length % 4) { 2 -> 0x30; 3 -> 0x3c; else -> return }
        val value = ALPHABET.indexOf((payload[length - 1].toInt() and 255).toChar())
        if (value >= 0) payload[length - 1] = ALPHABET[value and mask].code.toByte()
    }

    private fun fail() { state = State.FAILED; payload = EMPTY; payloadSize = 0 }

    private companion object {
        val EMPTY = ByteArray(0)
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}

/** Identifies only `G`; unsupported APC identifiers allocate no payload storage. */
internal class TerminalGraphicsApc(private val maxEncodedBytes: Int, private val command: (TerminalGraphicsCommand) -> Unit) {
    private var identifying = false
    private var parser: TerminalGraphicsCommandParser? = null
    val retainedPayloadBytes: Int get() = parser?.retainedPayloadBytes ?: 0
    fun start() { reset(); identifying = true }
    fun feed(byte: Int) {
        if (identifying) {
            identifying = false
            if (byte == 'G'.code) parser = TerminalGraphicsCommandParser(maxEncodedBytes)
        } else parser?.feed(byte)
    }
    fun end(terminator: Int) {
        val completed = if (terminator >= 0) parser?.complete() else null
        reset()
        // Ghostty executes complete Kitty commands even on CAN/SUB/C1 exits. An explicit
        // parser reset is different and must discard the unfinished command without dispatch.
        completed?.let(command)
    }
    fun reset() { parser = null; identifying = false }
}
