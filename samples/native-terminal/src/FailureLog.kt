@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package app.native.terminal

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/** Install before AppKit starts so exceptions at main, worker and interop boundaries are retained. */
internal fun installFailureLogging(directory: String = "${NSHomeDirectory()}/Library/Logs/Kinetica Terminal") {
    val log = FailureLog(directory)
    setUnhandledExceptionHook { failure ->
        try {
            val path = log.record(failure)
            fputs("Kinetica Terminal failure saved to $path\n", stderr)
        } catch (_: Throwable) {
            // Diagnostics must never replace the original exception, even on a full/unwritable disk.
            fputs("Kinetica Terminal could not persist the failure; original exception follows.\n", stderr)
        } finally {
            terminateWithUnhandledException(failure)
        }
    }
}

internal class FailureLog(private val directory: String) {
    private val launchedAt = NSDate().description

    fun record(failure: Throwable): String {
        val bundle = NSBundle.mainBundle
        val report = buildString {
            appendLine("Kinetica Terminal — unhandled Kotlin exception")
            appendLine("Failure time: ${NSDate().description}")
            appendLine("Launch time: $launchedAt")
            appendLine("PID: ${getpid()}; main thread: ${NSThread.isMainThread}")
            appendLine("Bundle: ${bundle.bundleIdentifier ?: "unbundled"}")
            appendLine("Version: ${bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") ?: "unknown"}; build: ${bundle.objectForInfoDictionaryKey("CFBundleVersion") ?: "unknown"}")
            appendLine("Executable: ${bundle.executablePath ?: "unknown"}")
            appendLine("OS: ${NSProcessInfo.processInfo.operatingSystemVersionString}")
            appendLine()
            appendLine(failure.stackTraceToString())
        }.encodeToByteArray()
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true,
            mapOf(NSFilePosixPermissions to 448), null)) { "Cannot create failure log directory" }
        val path = "$directory/failure-${getpid()}-${NSUUID().UUIDString}.log"
        // Exclusive creation also avoids following an existing file/symlink. 0600 protects error text.
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 384)
        check(fd >= 0) { "Cannot create failure log (errno=$errno)" }
        try {
            report.usePinned { bytes ->
                var offset = 0
                while (offset < report.size) {
                    val count = write(fd, bytes.addressOf(offset), (report.size - offset).toULong()).toInt()
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "Cannot write failure log (errno=$errno)" }
                    offset += count
                }
            }
            var result: Int
            do { result = fsync(fd) } while (result < 0 && errno == EINTR)
            check(result == 0) { "Cannot flush failure log (errno=$errno)" }
        } finally { close(fd) }
        return path
    }
}
