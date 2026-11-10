@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.native.terminal

import platform.AppKit.NSApplication
import platform.posix.fputs
import platform.posix.stderr
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--version"))) {
        println("Kinetica ${terminalApplicationVersion()}")
        return
    }
    installFailureLogging()
    registerTerminalFonts()
    if (args.firstOrNull()?.startsWith("--benchmark-") == true) {
        try {
            benchmarkTerminal(args)
        } catch (failure: Exception) {
            // A failed diagnostic (for example an occluded test window) is a CLI
            // failure, not an application crash. Benchmark cleanup runs first.
            fputs("Terminal benchmark failed: ${failure.message}\n", stderr)
            exitProcess(1)
        }
        return
    }
    TerminalApplication(NSApplication.sharedApplication()).run()
}
