@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package app.native.terminal

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*
import kotlin.native.processUnhandledException
import kotlin.test.*
import kotlin.time.TimeSource

class FailureLogTest {
    @Test fun retainsSeparateReportsWithCausesSuppressedFailuresAndPrivatePermissions() = withDirectory { directory ->
        val log = FailureLog("$directory/logs")
        val first = log.record(sampleFailure())
        val original = read(first)
        val second = log.record(IllegalArgumentException("second failure"))
        assertNotEquals(first, second)
        assertEquals(original, read(first))
        assertContains(original, "close-tab failure 界")
        assertContains(original, "Caused by:")
        assertContains(original, "original cause")
        assertContains(original, "Suppressed:")
        assertContains(original, "cleanup failure")
        assertContains(original, "FailureLogTest")
        assertContains(original, "PID: ${getpid()}")
        assertContains(original, "OS:")
        assertContains(read(second), "second failure")
        val permissions = NSFileManager.defaultManager.attributesOfItemAtPath(first, null)
            ?.get(NSFilePosixPermissions) as NSNumber
        assertEquals(384, permissions.intValue)
    }

    @Test fun hookPersistsBeforeAbortAndPreservesOriginalFailureWhenWritingFails() {
        getenv("KINETICA_FAILURE_TEST_DIRECTORY")?.toKString()?.let { directory ->
            installFailureLogging(directory)
            processUnhandledException(sampleFailure())
            fail("The installed hook must terminate")
        }
        withDirectory { directory ->
            val blocked = "$directory/not-a-directory"
            assertTrue(NSFileManager.defaultManager.createFileAtPath(blocked, null, null))
            for ((index, destination) in listOf("$directory/logs", "$blocked/logs").withIndex()) {
                val stderrPath = "$directory/stderr-$index.log"
                assertTrue(NSFileManager.defaultManager.createFileAtPath(stderrPath, null, null))
                val output = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(stderrPath))
                val task = NSTask()
                task.launchPath = NSProcessInfo.processInfo.arguments.first() as String
                task.arguments = listOf("--ktest_filter=app.native.terminal.FailureLogTest.hookPersistsBeforeAbortAndPreservesOriginalFailureWhenWritingFails")
                task.environment = NSProcessInfo.processInfo.environment + mapOf("KINETICA_FAILURE_TEST_DIRECTORY" to destination)
                task.standardOutput = NSFileHandle.fileHandleWithNullDevice
                task.standardError = output
                try {
                    task.launch()
                    val started = TimeSource.Monotonic.markNow()
                    while (task.running && started.elapsedNow().inWholeSeconds < 20)
                        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
                    assertFalse(task.running, "Failure-reporting child did not terminate")
                    assertEquals(NSTaskTerminationReasonUncaughtSignal, task.terminationReason)
                    assertEquals(SIGABRT, task.terminationStatus)
                } finally {
                    if (task.running) { kill(task.processIdentifier, SIGKILL); task.waitUntilExit() }
                    output.closeFile()
                }
                val stderr = read(stderrPath)
                assertContains(stderr, "close-tab failure 界")
                if (index == 0) {
                    val names = checkNotNull(NSFileManager.defaultManager.contentsOfDirectoryAtPath(destination, null))
                    assertEquals(1, names.size)
                    val report = read("$destination/${names.single()}")
                    assertContains(report, "close-tab failure 界")
                    assertContains(report, "original cause")
                    assertContains(report, "cleanup failure")
                } else assertContains(stderr, "could not persist the failure")
            }
        }
    }

    private fun sampleFailure() = IllegalStateException("close-tab failure 界", IllegalArgumentException("original cause"))
        .also { it.addSuppressed(IllegalStateException("cleanup failure")) }

    private fun read(path: String): String {
        val data = checkNotNull(NSFileManager.defaultManager.contentsAtPath(path))
        return data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())?.decodeToString().orEmpty()
    }

    private fun withDirectory(test: (String) -> Unit) {
        val directory = memScoped { checkNotNull(mkdtemp("/private/tmp/kinetica-failure-XXXXXX".cstr.ptr)).toKString() }
        try { test(directory) } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }
}
