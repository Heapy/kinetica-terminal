@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class TerminalUsabilityTest {
    @Test fun menusZoomFindSelectTabsAndNewTabInheritsTheShellDirectory() {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        application.activateIgnoringOtherApps(true)
        NSWindow.setAllowsAutomaticWindowTabbing(false)
        val store = TerminalSettingsStore(null)
        var terminated = false
        val owner = TerminalApplication(application, completeTermination = { terminated = true }, settings = store)
        val previousMenu = application.mainMenu
        application.mainMenu = owner.menu
        val windows = mutableListOf<NSWindow>()
        val directory = "/private/tmp/kinetica-tab-русский ' ${NSUUID().UUIDString}"
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val directoryInode = checkNotNull(NSFileManager.defaultManager.attributesOfItemAtPath(directory, null)?.get(NSFileSystemFileNumber))
        fun sameDirectory(path: String?): Boolean = path != null &&
            NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSystemFileNumber) == directoryInode
        fun shortcut(key: String, shift: Boolean = false) {
            val characters = if (shift) key.uppercase() else key
            val event = checkNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                NSEventModifierFlagCommand or if (shift) NSEventModifierFlagShift else 0uL, 0.0,
                application.keyWindow?.windowNumber ?: 0, null, characters, characters, false, 0u))
            assertTrue(owner.menu.performKeyEquivalent(event), "Missing menu shortcut: $key")
        }
        fun await(description: String = "Native application action", condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) autoreleasepool {
                application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)?.let(application::sendEvent)
                application.updateWindows()
            }
            assertTrue(condition(), "$description timed out")
        }
        fun capture(window: NSWindow, name: String) {
            val directory = NSProcessInfo.processInfo.environment["KINETICA_USABILITY_CAPTURE_DIR"] as? String ?: return
            application.updateWindows()
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.2))
            assertEquals(0, platform.posix.system("/usr/sbin/screencapture -x -o -l ${window.windowNumber} ${terminalShellQuote("$directory/$name.png")}"))
        }
        try {
            shortcut("n")
            val first = owner.terminals.single(); windows += first.window
            await { application.keyWindow == first.window }
            first.session.sendInput("cd -- ${terminalShellQuote(directory)}\r")
            // proc_pidinfo returns macOS filesystem normalization (NFD); compare the actual directory.
            await("Shell cd") { sameDirectory(first.currentWorkingDirectory()) }
            shortcut("t")
            val second = owner.terminals.last(); windows += second.window
            await { application.keyWindow == second.window }
            assertTrue(sameDirectory(second.currentWorkingDirectory()))
            shortcut("1"); await { application.keyWindow == first.window }
            shortcut("2"); await { application.keyWindow == second.window }
            val initialSize = second.pane.terminal.currentFontSize
            val initialColumns = second.session.columns
            second.session.write("\u001b]11;#123456\u0007")
            shortcut("=")
            assertEquals(initialSize + 1.0, second.pane.terminal.currentFontSize)
            assertTrue(second.session.columns < initialColumns)
            shortcut("+")
            assertEquals(initialSize + 2.0, second.pane.terminal.currentFontSize)
            shortcut("-")
            assertEquals(initialSize + 1.0, second.pane.terminal.currentFontSize)
            shortcut("0")
            assertEquals(initialSize, second.pane.terminal.currentFontSize)
            assertEquals(initialSize, first.pane.terminal.currentFontSize)
            assertEquals(0x123456, second.session.theme.background, "Zoom must preserve application color overrides")
            shortcut("f")
            assertTrue(second.pane.finding)
            second.session.write("\r\nSEARCH_ONE\r\nSEARCH_TWO\r\nSEARCH_THREE")
            second.pane.find("SEARCH_")
            assertEquals(3, second.pane.resultCount)
            capture(second.window, "search")
            shortcut("g"); assertEquals(0, second.pane.resultIndex)
            shortcut("g", shift = true); assertEquals(2, second.pane.resultIndex)
            application.postEvent(checkNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                0uL, 0.0, second.window.windowNumber, null, "\u001b", "\u001b", false, 53u)), atStart = true)
            await("Escape closes search") { !second.pane.finding }
            shortcut("a")
            assertContains(second.pane.terminal.accessibilitySelectedText().orEmpty(), "SEARCH_ONE")
            shortcut(",")
            val panel = application.windows.filterIsInstance<NSPanel>().single { it.title == "Terminal Settings" }
            val controls = panel.contentView!!.subviews.filterIsInstance<NSView>()
            val fontTitle = checkNotNull(NSFont.fontWithName("Menlo-Regular", 18.0)?.displayName)
            val fontMenu = controls.filterIsInstance<NSPopUpButton>().single { fontTitle in it.itemTitles }
            fontMenu.selectItemWithTitle(fontTitle)
            controls.filterIsInstance<NSTextField>().single { it.editable }.stringValue = "18"
            controls.filterIsInstance<NSPopUpButton>().single { "Light" in it.itemTitles }.selectItemWithTitle("Light")
            capture(panel, "settings")
            controls.filterIsInstance<NSButton>().single { it.title == "Apply" }.performClick(null)
            assertFalse(panel.visible)
            assertEquals(TerminalSettings("Menlo-Regular", 18.0, TerminalColorTheme.LIGHT), store.settings)
            for (terminal in owner.terminals) {
                assertEquals(18.0, terminal.pane.terminal.currentFontSize)
                assertEquals("Menlo-Regular", terminal.pane.terminal.currentFontFamily)
                assertEquals(TerminalColorTheme.LIGHT.colors, terminal.session.theme)
            }
            shortcut("t")
            val third = owner.terminals.last(); windows += third.window
            assertEquals(18.0, third.pane.terminal.currentFontSize)
            assertTrue(sameDirectory(third.currentWorkingDirectory()))
            await { application.keyWindow == third.window }
            repeat(80) { third.session.write("old-$it\r\n") }
            shortcut("k", shift = true); assertEquals(0, third.session.historySize)
            third.session.write("prompt> ")
            shortcut("k"); assertEquals(0, third.session.cursorRow)
            assertTrue(third.session.screenLine(0).text().contains("prompt>"))
            assertEquals(NSTerminateLater, owner.applicationShouldTerminate(application))
            await { terminated }
        } finally {
            owner.dispose(); windows.forEach { it.close() }; application.mainMenu = previousMenu
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }

    @Test fun preferencesAndGeometrySurviveReloadAndOffscreenFramesRemainReachable() {
        NSApplication.sharedApplication()
        val domain = "io.heapy.kinetica.settings-test.${NSUUID().UUIDString}"
        val defaults = checkNotNull(NSUserDefaults(suiteName = domain))
        val window = NSWindow(NSMakeRect(100.0, 100.0, 800.0, 500.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false)
        try {
            val store = TerminalSettingsStore(defaults)
            val settings = TerminalSettings("Menlo-Regular", 17.0, TerminalColorTheme.SOLARIZED)
            store.save(settings)
            store.saveFrame(NSMakeRect(120.0, 120.0, 820.0, 530.0))
            val reloaded = TerminalSettingsStore(defaults)
            assertEquals(settings, reloaded.settings)
            assertTrue(reloaded.restoreFrame(window))
            window.frame.useContents { assertEquals(820.0, size.width); assertEquals(530.0, size.height) }
            store.saveFrame(NSMakeRect(-100000.0, -100000.0, 80000.0, 80000.0))
            assertTrue(TerminalSettingsStore(defaults).restoreFrame(window))
            assertTrue(NSScreen.screens.filterIsInstance<NSScreen>().any { screen -> NSContainsRect(screen.visibleFrame, window.frame) })
            defaults.setDouble(Double.NaN, forKey = "terminal.fontSize")
            assertEquals(14.0, TerminalSettingsStore(defaults).settings.fontSize)
        } finally { window.close(); defaults.removePersistentDomainForName(domain) }
    }
}
