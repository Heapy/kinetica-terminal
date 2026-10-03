@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.native.terminal

import platform.AppKit.NSApplication

fun main(args: Array<String>) {
    if (args.firstOrNull()?.startsWith("--benchmark-") == true) { benchmarkTerminal(args); return }
    TerminalApplication(NSApplication.sharedApplication()).run()
}
