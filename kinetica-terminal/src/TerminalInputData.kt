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

    /** Bounded clipboard reply, admitted atomically using the adapters' extra protocol budget. */
    public class ProtocolReply internal constructor(
        private val bytes: ByteArray,
        complete: ((Boolean) -> Unit)? = null,
    ) : TerminalInputData() {
        private val delivery = complete?.let(::Delivery)
        override fun toByteArray(): ByteArray = bytes.copyOf()
        override fun enqueueTo(buffer: TerminalInputBuffer): Boolean {
            val receipt = delivery?.register()
            val accepted = buffer.tryAddProtocolReply(bytes, receipt)
            if (!accepted) receipt?.invoke(false)
            return accepted
        }
        internal fun dispatchFinished(success: Boolean) { delivery?.seal(success) }

        /** Observers do not acknowledge delivery; every accepting transport must drain its copy. */
        private class Delivery(private val complete: (Boolean) -> Unit) {
            private var registered = 0
            private var pending = 0
            private var sealed = false
            private var failed = false
            private var finished = false

            fun register(): (Boolean) -> Unit {
                check(!sealed) { "A tracked protocol reply must be enqueued during input dispatch" }
                registered++; pending++
                var delivered = false
                return { success ->
                    if (!delivered) {
                        delivered = true; pending--
                        if (!success) failed = true
                        finish()
                    }
                }
            }

            fun seal(success: Boolean) {
                sealed = true
                if (!success || registered == 0) failed = true
                finish()
            }

            private fun finish() {
                if (sealed && !finished && (failed || pending == 0)) {
                    finished = true
                    complete(!failed)
                }
            }
        }
    }
}
