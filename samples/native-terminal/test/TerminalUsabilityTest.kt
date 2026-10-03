@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import io.heapy.kinetica.appkit.*
import io.heapy.kinetica.application.*
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
            application.activateIgnoringOtherApps(true)
            window.makeKeyAndOrderFront(null)
            await("Focus after screenshot") { application.keyWindow == window }
        }
        try {
            shortcut("n")
            val first = owner.terminals.single(); windows += first.window
            application.activateIgnoringOtherApps(true)
            first.window.makeKeyAndOrderFront(null)
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
            second.window.contentView!!.layoutSubtreeIfNeeded()
            val initialSize = second.view.currentFontSize
            val initialColumns = second.session.columns
            second.session.write("\u001b]11;#123456\u0007")
            shortcut("=")
            assertEquals(initialSize + 1.0, second.view.currentFontSize)
            assertTrue(second.session.columns < initialColumns)
            shortcut("+")
            assertEquals(initialSize + 2.0, second.view.currentFontSize)
            shortcut("-")
            assertEquals(initialSize + 1.0, second.view.currentFontSize)
            shortcut("0")
            assertEquals(initialSize, second.view.currentFontSize)
            assertEquals(initialSize, first.view.currentFontSize)
            assertEquals(0x123456, second.session.theme.background, "Zoom must preserve application color overrides")
            val retainedSurface = second.view
            shortcut("f")
            assertEquals(retainedSurface, second.view)
            assertTrue(second.search.value.visible)
            // Produce output through the PTY. Writing into the session beside a live shell
            // races its prompt redraw after the find bar resizes the terminal.
            val output = "\r\nSEARCH_ONE\r\nSEARCH_TWO\r\nSEARCH_THREE\r\n"
                .map { "\\${it.code.toString(8).padStart(3, '0')}" }.joinToString("")
            second.session.sendInput("printf '$output'\r")
            val findField = (second.window.firstResponder as NSTextView).delegate as NSTextField
            findField.stringValue = "SEARCH_"
            findField.delegate!!.controlTextDidChange(NSNotification.notificationWithName(NSControlTextDidChangeNotification, findField))
            await("Search matches") { second.search.value.count == 3 }
            capture(second.window, "search")
            shortcut("g"); assertEquals(0, second.search.value.index)
            shortcut("g", shift = true); assertEquals(2, second.search.value.index)
            application.postEvent(checkNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                0uL, 0.0, second.window.windowNumber, null, "\u001b", "\u001b", false, 53u)), atStart = true)
            await("Escape closes search") { !second.search.value.visible }
            assertEquals(retainedSurface, second.view)
            assertEquals(second.view, second.window.firstResponder)
            shortcut("a")
            assertContains(second.view.accessibilitySelectedText().orEmpty(), "SEARCH_ONE")
            shortcut(",")
            val panel = checkNotNull(owner.shell.window("settings")).nativeWindow
            await { application.keyWindow == panel }
            assertFalse(owner.shell.execute("screen.clear"), "Settings must not route commands into a background terminal")
            fun flatten(view: NSView): List<NSView> = listOf(view) + view.subviews.filterIsInstance<NSView>().flatMap(::flatten)
            val controls = flatten(panel.contentView!!)
            val fontTitle = checkNotNull(NSFont.fontWithName("Menlo-Regular", 18.0)?.displayName)
            val fontMenu = controls.filterIsInstance<NSPopUpButton>().single { fontTitle in it.itemTitles }
            fontMenu.selectItemWithTitle(fontTitle)
            assertTrue(application.sendAction(fontMenu.action!!, to = fontMenu.target, from = fontMenu))
            val sizeField = controls.filterIsInstance<NSTextField>().single { it.identifier == "font-size" }
            sizeField.stringValue = "18"
            sizeField.delegate!!.controlTextDidChange(NSNotification.notificationWithName(NSControlTextDidChangeNotification, sizeField))
            val themeMenu = controls.filterIsInstance<NSPopUpButton>().single { "Light" in it.itemTitles }
            themeMenu.selectItemWithTitle("Light")
            assertTrue(application.sendAction(themeMenu.action!!, to = themeMenu.target, from = themeMenu))
            await("Settings choice events") { themeMenu.titleOfSelectedItem == "Light" && fontMenu.titleOfSelectedItem == fontTitle }
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.02))
            capture(panel, "settings")
            controls.filterIsInstance<NSButton>().single { it.identifier == "apply" }.performClick(null)
            assertFalse(panel.visible)
            assertEquals(TerminalSettings("Menlo-Regular", 18.0, TerminalColorTheme.LIGHT), store.settings)
            for (terminal in owner.terminals) {
                assertEquals(18.0, terminal.view.currentFontSize)
                assertEquals("Menlo-Regular", terminal.view.currentFontFamily)
                assertEquals(TerminalColorTheme.LIGHT.colors, terminal.session.theme)
            }
            await { application.keyWindow == second.window }
            shortcut("t")
            val third = owner.terminals.last(); windows += third.window
            assertEquals(18.0, third.view.currentFontSize)
            assertTrue(sameDirectory(third.currentWorkingDirectory()))
            await { application.keyWindow == third.window }
            repeat(80) { third.session.write("old-$it\r\n") }
            shortcut("k", shift = true); assertEquals(0, third.session.historySize)
            third.session.write("prompt> ")
            shortcut("k"); assertEquals(0, third.session.cursorRow)
            assertTrue(third.session.screenLine(0).text().contains("prompt>"))
            assertEquals(NSTerminateLater, owner.shell.applicationShouldTerminate(application))
            await { terminated }
        } finally {
            owner.dispose(); await { owner.shell.closingWindowCount == 0 }; windows.forEach { it.close() }; application.mainMenu = previousMenu
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }

    @Test fun preferencesAndGeometrySurviveReloadAndOffscreenFramesRemainReachable() {
        NSApplication.sharedApplication()
        val domain = "io.heapy.kinetica.settings-test.${NSUUID().UUIDString}"
        val defaults = checkNotNull(NSUserDefaults(suiteName = domain))
        val application = AppKitApplication("Geometry Test", quitAfterLastWindow = false)
        val window = application.openWindow(ApplicationWindow("geometry", "Geometry")) {}
        try {
            val store = TerminalSettingsStore(defaults)
            val settings = TerminalSettings("Menlo-Regular", 17.0, TerminalColorTheme.SOLARIZED)
            store.save(settings)
            store.saveBounds(WindowBounds(120.0, 120.0, 820.0, 530.0))
            val reloaded = TerminalSettingsStore(defaults)
            assertEquals(settings, reloaded.settings)
            assertTrue(window.restoreBounds(checkNotNull(reloaded.bounds)))
            window.nativeWindow.frame.useContents { assertEquals(820.0, size.width); assertEquals(530.0, size.height) }
            store.saveBounds(WindowBounds(-100000.0, -100000.0, 80000.0, 80000.0))
            assertTrue(window.restoreBounds(checkNotNull(TerminalSettingsStore(defaults).bounds)))
            assertTrue(NSScreen.screens.filterIsInstance<NSScreen>().any { screen -> NSContainsRect(screen.visibleFrame, window.nativeWindow.frame) })
            defaults.setDouble(Double.NaN, forKey = "terminal.fontSize")
            assertEquals(14.0, TerminalSettingsStore(defaults).settings.fontSize)
        } finally { application.dispose(); defaults.removePersistentDomainForName(domain) }
    }
}
