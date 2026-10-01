@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*
import kotlin.time.TimeSource

/** Explicit CLI diagnostics; no terminal output is echoed or interpreted by the parent shell. */
internal fun benchmarkTerminal(args: Array<String>) {
    if (args.firstOrNull() == "--benchmark-renderer") {
        benchmarkRenderer(args)
        return
    }
    require(args.size == 2 && args[0] in listOf("--benchmark-parser", "--benchmark-pty")) {
        "Usage: native-terminal --benchmark-parser|--benchmark-pty DIRECTORY"
    }
    val directory = args[1]
    val files = requireNotNull(NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null))
        .filterIsInstance<String>().sorted().map { "$directory/$it" }.filter {
            NSFileManager.defaultManager.attributesOfItemAtPath(it, null)?.get(NSFileType) == NSFileTypeRegular
        }
    require(files.isNotEmpty()) { "No regular files in directory" }
    val session = TerminalSession(80, 24)
    var replies = 0L
    val subscription = session.onInput { replies += it.toByteArray().size }
    val start = TimeSource.Monotonic.markNow()
    try {
        if (args[0] == "--benchmark-parser") {
            val bytes = ByteArray(16 * 1024)
            var count = 0L
            var parseNanos = 0L
            for (file in files) {
                val fd = open(file, O_RDONLY)
                check(fd >= 0) { "Cannot read $file" }
                try {
                    while (true) {
                        val n = bytes.usePinned { read(fd, it.addressOf(0), bytes.size.toULong()) }.toInt()
                        if (n < 0 && errno == EINTR) continue
                        check(n >= 0) { "Read failed for $file" }
                        if (n == 0) break
                        val parseStart = TimeSource.Monotonic.markNow()
                        session.write(bytes, 0, n)
                        parseNanos += parseStart.elapsedNow().inWholeNanoseconds
                        count += n
                    }
                } finally { close(fd) }
            }
            session.finishInput()
            println("{\"mode\":\"parser\",\"files\":${files.size},\"bytes\":$count,\"parseSeconds\":${parseNanos / 1e9},\"wallSeconds\":${start.elapsedNow().inWholeMilliseconds / 1000.0},\"replyBytes\":$replies,\"historyRows\":${session.historySize}}")
        } else {
            var exit: Int? = null
            var ticks = 0L
            val timer = NSTimer.timerWithTimeInterval(0.001, repeats = true) { ticks++ }
            NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
            val pty = MacOsPty(session, "/bin/cat", files, onExit = { exit = it })
            try {
                while (exit == null && start.elapsedNow().inWholeSeconds < 180) {
                    NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
                }
                check(exit != null) { "PTY timed out: pending=${pty.pendingInputBytes}, paused=${pty.outputPausedForInput}" }
                println("{\"mode\":\"pty-no-renderer\",\"files\":${files.size},\"wallSeconds\":${start.elapsedNow().inWholeMilliseconds / 1000.0},\"exitCode\":$exit,\"replyBytes\":$replies,\"timerTicks\":$ticks,\"rejectedInputs\":${pty.rejectedInputCount},\"receivedBytes\":${pty.receivedBytes},\"readOperations\":${pty.readOperations}}")
                check(exit == 0) { "cat failed with $exit" }
            } finally { pty.dispose(); timer.invalidate() }
        }
    } finally { subscription.dispose() }
}
