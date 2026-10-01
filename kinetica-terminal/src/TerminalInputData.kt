package io.heapy.kinetica.terminal

/** Outgoing input/reply. Legacy mouse reports contain bytes that are not UTF-8 text. */
public sealed class TerminalInputData {
    /** A fresh wire representation. Transports should prefer the bounded stream/PTY adapters. */
    public abstract fun toByteArray(): ByteArray
    internal abstract fun enqueueTo(buffer: TerminalInputBuffer): Boolean

    public class Text(public val text: String) : TerminalInputData() {
        override fun toByteArray(): ByteArray = text.encodeToByteArray()
        override fun enqueueTo(buffer: TerminalInputBuffer): Boolean = buffer.tryAdd(text)
    }

    /** Immutable protocol bytes; the internal constructor takes ownership of its array. */
    public class Bytes internal constructor(private val bytes: ByteArray) : TerminalInputData() {
        override fun toByteArray(): ByteArray = bytes.copyOf()
        override fun enqueueTo(buffer: TerminalInputBuffer): Boolean = buffer.tryAdd(bytes)
    }
}
