package io.heapy.kinetica.terminal

import kotlin.time.TimeSource

/**
 * Application-owned, nonblocking transport for a terminal byte stream.
 * Methods run on the session's owning thread and must return promptly.
 */
public interface TerminalByteTransport {
    /**
     * Consume/copy a prefix of this range before returning its length, or return zero when full.
     * Never retain or modify the borrowed array. Throw on a permanent transport failure.
     */
    public fun write(bytes: ByteArray, offset: Int, length: Int): Int
    public fun resize(columns: Int, rows: Int)
    /** Return this many output credits to the remote producer, after parsing and publishing damage. */
    public fun consumed(bytes: Int)
}

public data class TerminalStreamLimits(
    public val outputBytes: Int = 1024 * 1024,
    public val inputBytes: Int = 1024 * 1024,
) {
    init {
        require(outputBytes > 0)
        require(inputBytes >= TERMINAL_REPLY_RESERVE) { "Input capacity must include the terminal reply reserve" }
    }
}

/**
 * Bounded, cooperative duplex stream. [receive] copies accepted UTF-8 bytes and never parses
 * inline. The producer must limit unacknowledged output to [TerminalStreamLimits.outputBytes].
 * A false return admits no bytes; the caller must retry later or close a misbehaving producer.
 */
public class TerminalStreamConnection internal constructor(
    private val session: TerminalSession,
    private val transport: TerminalByteTransport,
    private val scheduler: TerminalScheduler,
    private val onInputRejected: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    public val limits: TerminalStreamLimits,
    private val nowMillis: () -> Double,
) : TerminalDisposable {
    public constructor(
        session: TerminalSession,
        transport: TerminalByteTransport,
        scheduler: TerminalScheduler,
        onInputRejected: () -> Unit,
        onFailure: (Throwable) -> Unit,
        limits: TerminalStreamLimits = TerminalStreamLimits(),
    ) : this(session, transport, scheduler, onInputRejected, onFailure, limits, monotonicMillis())

    private val output = TerminalInputBuffer(limits.outputBytes)
    private val input = TerminalInputBuffer(limits.inputBytes)
    private var scheduled: TerminalDisposable? = null
    private var running = false
    private var endingOutput = false
    private var inputSubscription: TerminalDisposable? = null
    private var resizeSubscription: TerminalDisposable? = null
    public var disposed: Boolean = false
        private set
    public var outputFinished: Boolean = false
        private set
    public var failure: Throwable? = null
        private set
    public var rejectedInputCount: Long = 0
        private set
    public val queuedOutputBytes: Int get() = output.size
    public val pendingInputBytes: Int get() = input.size
    public val outputPausedForInput: Boolean get() = input.remaining < TERMINAL_REPLY_RESERVE

    init {
        inputSubscription = session.onInput { text ->
            if (!disposed) guarded {
                if (!input.tryAdd(text)) { rejectedInputCount++; onInputRejected() }
                else requestWork(0)
            }
        }
        resizeSubscription = session.onResize { columns, rows ->
            if (!disposed) guarded { transport.resize(columns, rows) }
        }
        guarded { transport.resize(session.columns, session.rows) }
    }

    public fun receive(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (disposed || endingOutput || !output.tryAdd(bytes, offset, length)) return false
        if (length > 0) requestWork(0)
        return !disposed
    }

    /** Deliver EOF after all accepted output, including a final incomplete UTF-8 character. */
    public fun finishOutput() {
        if (disposed || endingOutput) return
        endingOutput = true
        requestWork(0)
    }

    /** Optional readiness hint. A blocked writer is otherwise retried once per 8 ms timer. */
    public fun writable() { if (!disposed && input.size > 0) requestWork(0) }

    private fun guarded(action: () -> Unit) {
        try { action() } catch (error: Throwable) { fail(error) }
    }

    private fun fail(error: Throwable) {
        if (disposed) return
        failure = error
        dispose()
        onFailure(error)
    }

    private fun requestWork(delay: Int) {
        if (disposed || running || scheduled != null) return
        guarded {
            scheduled = scheduler.schedule(delay) {
                scheduled = null
                pump()
            }
        }
    }

    private fun pump() {
        if (disposed || running) return
        running = true
        val deadline = nowMillis() + 4.0
        var written = 0
        var parsed = 0
        var blocked = false
        fun hasTime() = nowMillis() < deadline
        fun flushInput() {
            while (!disposed && !blocked && input.size > 0 && written < TURN_BYTES && hasTime()) {
                val count = minOf(input.contiguousSize, 16 * 1024, TURN_BYTES - written)
                val accepted = transport.write(input.buffer!!, input.offset, count)
                if (disposed) return
                check(accepted in 0..count) { "Transport accepted an invalid byte count: $accepted of $count" }
                if (accepted == 0) { blocked = true; return }
                input.consume(accepted)
                written += accepted
            }
        }
        try {
            flushInput()
            while (!disposed && output.size > 0 && parsed < TURN_BYTES && hasTime() && !outputPausedForInput) {
                val count = session.writeAvailable(output.buffer!!, output.offset,
                    minOf(output.contiguousSize, 16 * 1024, TURN_BYTES - parsed)) {
                    if (input.size >= 4096) flushInput()
                    !disposed && !outputPausedForInput && hasTime()
                }
                if (disposed) return
                output.consume(count)
                parsed += count
                if (count > 0) transport.consumed(count)
                if (disposed) return
            }
            if (!disposed && endingOutput && output.size == 0 && !outputFinished && !outputPausedForInput && hasTime()) {
                session.finishInput()
                if (!disposed) outputFinished = true
            }
            flushInput()
        } catch (error: Throwable) {
            fail(error)
        } finally {
            running = false
            if (!disposed && (input.size > 0 || output.size > 0 || endingOutput && !outputFinished))
                requestWork(if (blocked && (outputPausedForInput || output.size == 0)) 8 else 0)
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        val task = scheduled; scheduled = null
        inputSubscription?.dispose(); inputSubscription = null
        resizeSubscription?.dispose(); resizeSubscription = null
        output.clear(); input.clear()
        task?.dispose()
    }

    private companion object {
        const val TURN_BYTES = 256 * 1024
        fun monotonicMillis(): () -> Double {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeNanoseconds / 1_000_000.0 }
        }
    }
}
