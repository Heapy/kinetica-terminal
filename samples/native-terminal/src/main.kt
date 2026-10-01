@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import platform.AppKit.*

// AppKit delegate references are weak; retain the application owner through its run loop.
private var retainedApplication: TerminalApplication? = null

fun main(args: Array<String>) {
    if (args.firstOrNull()?.startsWith("--benchmark-") == true) {
        benchmarkTerminal(args)
        return
    }
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    NSWindow.setAllowsAutomaticWindowTabbing(false)
    val controller = TerminalApplication(application)
    retainedApplication = controller
    application.delegate = controller
    application.mainMenu = controller.menu
    controller.newWindow(null)
    application.activateIgnoringOtherApps(true)
    application.run()
    controller.dispose()
    retainedApplication = null
}
