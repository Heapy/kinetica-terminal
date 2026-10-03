@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.posix.kill
import platform.posix.errno
import platform.posix.ESRCH
import kotlin.test.*
import kotlin.time.TimeSource

class TerminalApplicationTest {
    @Test fun shellExitClosesOnlyItsOwnTabOrWindowIncludingEofAndFailure() {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        application.activateIgnoringOtherApps(true)
        NSWindow.setAllowsAutomaticWindowTabbing(false)
        val owner = TerminalApplication(application, settings = TerminalSettingsStore(null))
        val windows = mutableListOf<NSWindow>()
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) {
                application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)
                    ?.let { application.sendEvent(it) }
                application.updateWindows()
            }
            assertTrue(condition(), "Shell lifecycle timed out: windows=${owner.terminals.map { it.handle.id to it.window.visible }}, closing=${owner.shell.closingWindowCount}")
        }
        fun text(terminal: TerminalWindow) = (0 until terminal.session.rows).joinToString("\n") { terminal.session.screenLine(it).text() }
        fun pid(terminal: TerminalWindow): Int {
            terminal.session.sendInput("printf '\\nKINETICA_PID:%s\\n' \"${'$'}${'$'}\"\r")
            fun found() = Regex("KINETICA_PID:([0-9]+)").find(text(terminal))
            await { found() != null }
            return found()!!.groupValues[1].toInt()
        }
        fun gone(pid: Int) = kill(pid, 0) != 0 && errno == ESRCH
        try {
            owner.open()
            val first = owner.terminals.single(); windows += first.window
            val firstPid = pid(first)
            application.activateIgnoringOtherApps(true)
            first.window.makeKeyAndOrderFront(null)
            await { application.keyWindow == first.window }
            owner.open(tabbed = true)
            val second = owner.terminals.last(); windows += second.window
            val secondPid = pid(second)
            assertEquals(2, first.window.tabGroup?.windows?.size)
            first.session.sendInput("exit\r") // Exit the background tab, not the active one.
            await { owner.terminals == listOf(second) && !first.window.visible }
            assertTrue(gone(firstPid))
            assertFalse(gone(secondPid))
            assertTrue(second.window.visible)

            owner.open()
            val failed = owner.terminals.last(); windows += failed.window
            val failedPid = pid(failed)
            failed.session.sendInput("exit 7\r")
            await { owner.terminals == listOf(second) && !failed.window.visible }
            assertTrue(gone(failedPid))
            assertFalse(gone(secondPid))

            second.session.sendInput("unsetopt IGNORE_EOF; printf '\\nKINETICA_READY:%s\\n' EOF\r")
            await { "KINETICA_READY:EOF" in text(second) }
            second.session.sendInput("\u0004")
            await { owner.terminals.isEmpty() && !second.window.visible }
            assertTrue(gone(secondPid))
            assertTrue(owner.shell.applicationShouldTerminateAfterLastWindowClosed(application))
        } finally { owner.dispose(); await { owner.shell.closingWindowCount == 0 }; windows.forEach { it.close() } }
    }

    @Test fun menuShortcutsCreateIndependentTabsAndCloseTheirShellProcesses() {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        application.activateIgnoringOtherApps(true)
        val previousMenu = application.mainMenu
        NSWindow.setAllowsAutomaticWindowTabbing(false)
        var quit = false
        var terminated = false
        lateinit var owner: TerminalApplication
        owner = TerminalApplication(application, quit = {
            quit = true
            assertEquals(NSTerminateLater, owner.shell.applicationShouldTerminate(application))
        }, completeTermination = { terminated = true }, settings = TerminalSettingsStore(null))
        application.mainMenu = owner.menu
        val windows = mutableListOf<NSWindow>()
        fun shortcut(char: String, shift: Boolean = false) {
            val target = application.keyWindow ?: application.mainWindow
            application.activateIgnoringOtherApps(true)
            target?.makeKeyAndOrderFront(null)
            if (target != null) {
                val focusDeadline = TimeSource.Monotonic.markNow()
                while (application.keyWindow != target && focusDeadline.elapsedNow().inWholeSeconds < 5) {
                    application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)?.let(application::sendEvent)
                }
                assertEquals(target, application.keyWindow, "Synthetic shortcut requires its application window to be key")
            }
            val flags = NSEventModifierFlagCommand or if (shift) NSEventModifierFlagShift else 0uL
            val characters = if (shift) when (char) { "[" -> "{"; "]" -> "}"; else -> char.uppercase() } else char
            val event = requireNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                flags, 0.0, application.keyWindow?.windowNumber ?: 0, null, characters, characters, false, 0u))
            assertTrue(owner.menu.performKeyEquivalent(event), "Unmatched shortcut: $char")
        }
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) {
                application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)
                    ?.let { application.sendEvent(it) }
            }
            assertTrue(condition(), "Desktop lifecycle timed out")
        }
        fun pid(terminal: TerminalWindow): Int {
            terminal.session.sendInput("printf '\\nKINETICA_PID:%s\\n' \"${'$'}${'$'}\"\r")
            fun found() = Regex("KINETICA_PID:([0-9]+)").find((0 until terminal.session.rows).joinToString("\n") { terminal.session.screenLine(it).text() })
            await { found() != null }
            return found()!!.groupValues[1].toInt()
        }
        fun gone(pid: Int) = kill(pid, 0) != 0 && errno == ESRCH
        try {
            shortcut("n")
            assertEquals(1, owner.terminals.size)
            val first = owner.terminals.single(); windows.add(first.window)
            val firstPid = pid(first)
            application.activateIgnoringOtherApps(true)
            first.window.makeKeyAndOrderFront(null)
            await { application.keyWindow == first.window }
            shortcut("t")
            assertEquals(2, owner.terminals.size)
            val second = owner.terminals.last(); windows.add(second.window)
            val secondPid = pid(second)
            assertNotEquals(firstPid, secondPid)
            assertTrue(first.session !== second.session)
            assertEquals(2, first.window.tabGroup?.windows?.size)
            assertEquals(first.window.tabGroup, second.window.tabGroup)
            await { application.keyWindow == second.window }
            shortcut("[", shift = true)
            await { application.keyWindow == first.window }
            assertIs<io.heapy.kinetica.terminal.AppKitTerminalView>(first.window.firstResponder)
            shortcut("]", shift = true)
            await { application.keyWindow == second.window }
            shortcut("w")
            assertEquals(listOf(first), owner.terminals)
            await { gone(secondPid) }
            assertFalse(gone(firstPid))
            shortcut("q")
            assertTrue(quit)
            assertTrue(owner.terminals.isEmpty())
            await { terminated }
            assertTrue(gone(firstPid))
        } finally {
            owner.dispose(); await { owner.shell.closingWindowCount == 0 }; windows.forEach { it.close() }
            application.mainMenu = previousMenu
        }
    }
}
