@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import io.heapy.kinetica.terminal.pty.*
import kotlinx.cinterop.*
import platform.AppKit.NSBeep
import platform.Foundation.*
import platform.darwin.*
import platform.posix.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * Local macOS PTY transport. Create, use and dispose on the main thread. Reads and writes are
 * nonblocking; each run-loop turn yields after a 4 ms work budget or 256 KiB of output.
 * Parsing pauses when the 1 MiB input ring lacks room for replies; the kernel PTY then provides
 * output backpressure. Closing the transport hangs up and reaps its child.
 */
public class MacOsPty(
    private val session: TerminalSession,
    executable: String = "/bin/zsh",
    arguments: List<String> = listOf("-l"),
    environment: Map<String, String> = emptyMap(),
    workingDirectory: String = NSHomeDirectory(),
    private val onInputRejected: () -> Unit = { NSBeep() },
    private val onExit: (Int) -> Unit = {},
) : TerminalDisposable {
    private var fd = -1
    private var pid = -1
    private var disposed = false
    private var ended = false
    private var timer: NSTimer? = null
    private val buffer = ByteArray(16 * 1024)
    private var outputOffset = 0
    private var outputSize = 0
    private val pending = TerminalInputBuffer()
    private var pumping = false
    private var writesBlocked = false
    private var childReaped = false
    private val closedCallbacks = mutableListOf<() -> Unit>()
    private val inputSubscription: TerminalDisposable
    private val resizeSubscription: TerminalDisposable
    public val pendingInputBytes: Int get() = pending.size
    public val bufferedOutputBytes: Int get() = outputSize - outputOffset
    public val outputPausedForInput: Boolean get() = pending.remaining < TERMINAL_REPLY_RESERVE
    public var rejectedInputCount: Long = 0
        private set
    /** Raw bytes received from the PTY before parsing, including an unread buffered tail. */
    public var receivedBytes: Long = 0
        private set
    public var readOperations: Long = 0
        private set

    /** Current directory of this owned shell, including `cd` changes without OSC integration. */
    public fun currentWorkingDirectory(): String? {
        if (disposed || ended || childReaped) return null
        return memScoped {
            val path = allocArray<kotlinx.cinterop.ByteVar>(4096)
            if (kinetica_pty_cwd(pid, path, 4096u) == 0) path.toKString() else null
        }
    }

    init {
        check(NSThread.isMainThread) { "MacOsPty must be created on the main thread" }
        require(executable.startsWith('/') && '\u0000' !in executable)
        require(arguments.none { '\u0000' in it })
        require(workingDirectory.startsWith('/') && '\u0000' !in workingDirectory)
        val env = NSProcessInfo.processInfo.environment.entries.associate { it.key.toString() to it.value.toString() } +
            mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor") + environment
        require(env.all { (k, v) -> k.isNotEmpty() && '=' !in k && '\u0000' !in k && '\u0000' !in v })
        memScoped {
            fun strings(values: List<String>): CPointer<CPointerVar<kotlinx.cinterop.ByteVar>> {
                val result = allocArray<CPointerVar<kotlinx.cinterop.ByteVar>>(values.size + 1)
                values.forEachIndexed { i, value -> result[i] = value.cstr.ptr }
                result[values.size] = null
                return result
            }
            val child = alloc<IntVar>()
            fd = kinetica_pty_spawn(executable, strings(listOf(executable) + arguments), strings(env.map { "${it.key}=${it.value}" }), workingDirectory, session.columns, session.rows, child.ptr)
            check(fd >= 0) { "Unable to start $executable: ${strerror(errno)?.toKString()}" }
            pid = child.value
        }
        inputSubscription = session.onInput(::send)
        resizeSubscription = session.onResize { columns, rows -> if (!disposed && !ended) kinetica_pty_resize(fd, columns, rows) }
        timer = NSTimer.timerWithTimeInterval(0.008, repeats = true) { pump() }.also {
            NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes)
        }
    }

    private fun send(text: TerminalInputData) {
        if (disposed || ended) return
        val start = TimeSource.Monotonic.markNow()
        if (!pumping) {
            writesBlocked = false
            flushWrites(start)
        }
        if (disposed || ended) return
        if (!pending.tryAdd(text)) { rejectedInputCount++; onInputRejected(); return }
        if (!pumping) flushWrites(start)
    }

    private fun flushWrites(start: TimeSource.Monotonic.ValueTimeMark) {
        var writtenThisCall = 0
        while (pending.size > 0 && !disposed && !ended && !writesBlocked &&
            writtenThisCall < 64 * 1024 && start.elapsedNow() < 4.milliseconds) {
            val written = pending.buffer!!.usePinned {
                write(fd, it.addressOf(pending.offset), minOf(pending.contiguousSize, 64 * 1024 - writtenThisCall).toULong())
            }.toInt()
            if (written < 0) {
                if (errno == EINTR) continue
                if (errno == EAGAIN) writesBlocked = true else finish()
                return
            }
            if (written == 0) { writesBlocked = true; return }
            pending.consume(written)
            writtenThisCall += written
        }
    }

    private fun pump() {
        if (disposed || ended || pumping) return
        pumping = true; writesBlocked = false
        val start = TimeSource.Monotonic.markNow()
        var processed = 0
        try {
            flushWrites(start)
            while (!disposed && !ended && processed < 256 * 1024 && start.elapsedNow() < 4.milliseconds) {
                if (outputPausedForInput) return
                if (outputOffset == outputSize) {
                    // A PTY often returns small fragments even when the child is flooding it.
                    // Bound work by bytes and elapsed time, not an assumed 16 KiB per read.
                    readOperations++
                    val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.toULong()) }.toInt()
                    when {
                        count > 0 -> { receivedBytes += count; outputOffset = 0; outputSize = count }
                        count == 0 -> { finish(); return }
                        errno == EINTR -> continue
                        errno == EAGAIN -> return
                        else -> { finish(); return }
                    }
                }
                val count = session.writeAvailable(buffer, outputOffset,
                    minOf(outputSize - outputOffset, 256 * 1024 - processed)) {
                    if (pending.size >= 4096) flushWrites(start)
                    !disposed && !ended && !outputPausedForInput && start.elapsedNow() < 4.milliseconds
                }
                if (disposed || ended) return
                outputOffset += count
                processed += count
            }
        } finally {
            try { flushWrites(start) } finally { pumping = false }
        }
    }

    private fun finish() {
        if (ended || disposed) return
        ended = true
        pending.clear()
        outputOffset = 0; outputSize = 0
        session.finishInput()
        timer?.invalidate(); timer = null
        inputSubscription.dispose(); resizeSubscription.dispose()
        if (fd >= 0) { close(fd); fd = -1 }
        val child = pid; pid = -1
        // waitpid can block briefly after EOF; keep it off the AppKit thread.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val status = kinetica_pty_reap(child)
            dispatch_async(dispatch_get_main_queue()) {
                childReaped = true
                try { if (!disposed) onExit(status) } finally { notifyClosed() }
            }
        }
    }

    /** Dispose without blocking AppKit; notify on the main thread after the child is reaped. */
    public fun close(onClosed: () -> Unit) {
        check(NSThread.isMainThread) { "Close MacOsPty on the main thread" }
        if (childReaped) { dispose(); onClosed() }
        else { closedCallbacks.add(onClosed); dispose() }
    }

    private fun notifyClosed() {
        val callbacks = closedCallbacks.toList(); closedCallbacks.clear()
        callbacks.forEach { it() }
    }

    override fun dispose() {
        check(NSThread.isMainThread) { "Dispose MacOsPty on the main thread" }
        if (disposed) return
        disposed = true; timer?.invalidate(); timer = null
        inputSubscription.dispose(); resizeSubscription.dispose(); pending.clear()
        outputOffset = 0; outputSize = 0
        if (fd >= 0) { close(fd); fd = -1 }
        val child = pid; pid = -1
        if (child > 0) dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            kinetica_pty_reap(child)
            dispatch_async(dispatch_get_main_queue()) { childReaped = true; notifyClosed() }
        }
    }
}
