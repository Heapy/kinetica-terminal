@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.CoreGraphics.*
import platform.CoreFoundation.CFRelease
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsUsabilityTest {
    @Test fun scrollbarAppearsOnlyDuringNavigationWithoutResizingTheGrid() {
        val application = NSApplication.sharedApplication()
        application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        application.finishLaunching()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.GPU)
        val pane = AppKitTerminalPane(view)
        val window = NSWindow(NSMakeRect(100.0, 100.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = pane
        fun wheel() {
            val cg = checkNotNull(CGEventCreateScrollWheelEvent2(null, kCGScrollEventUnitPixel, 1u, 1, 0, 0))
            try { pane.scrollWheel(checkNotNull(NSEvent.eventWithCGEvent(cg))) } finally { CFRelease(cg) }
        }
        fun awaitHidden(output: Boolean = false) {
            val started = TimeSource.Monotonic.markNow()
            while (!pane.scroller.hidden && started.elapsedNow().inWholeSeconds < 3) autoreleasepool {
                if (output) session.write("background output\r\n")
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.04))
                application.updateWindows()
            }
            assertTrue(pane.scroller.hidden, "Idle scroller must hide even while output continues")
        }
        fun capture(name: String) {
            val directory = NSProcessInfo.processInfo.environment["KINETICA_SCROLLBAR_CAPTURE_DIR"] as? String ?: return
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.2))
            application.updateWindows()
            assertEquals(0, platform.posix.system("/usr/sbin/screencapture -x -o -l ${window.windowNumber} ${terminalShellQuote("$directory/$name.png")}"))
        }
        try {
            window.makeKeyAndOrderFront(null)
            assertTrue(pane.scroller.hidden)
            wheel(); assertTrue(pane.scroller.hidden, "No scrollbar without history")
            repeat(100) { session.write("line-$it\r\n") }
            assertTrue(pane.scroller.hidden, "Output alone must not reveal the scrollbar")
            val columns = session.columns
            val width = view.frame.useContents { size.width }
            assertEquals(pane.bounds.useContents { size.width }, width)
            capture("idle")
            wheel(); assertFalse(pane.scroller.hidden, "Even a fractional trackpad movement reveals it")
            view.scrollTo(40)
            assertEquals(columns, session.columns); assertEquals(width, view.frame.useContents { size.width })
            capture("scrolling")
            awaitHidden(output = true)
            assertEquals(columns, session.columns); assertEquals(width, view.frame.useContents { size.width })
            assertSame(view, pane.hitTest(NSMakePoint(width - 3.0, 100.0)), "Hidden overlay must not intercept terminal input")
            capture("hidden")
            pane.showFind(null); pane.find("line-")
            awaitHidden(output = true)
            pane.hideFind(null)
            view.scrollTo(0); awaitHidden()
            session.write("\u001b[?1000h")
            wheel(); assertTrue(pane.scroller.hidden, "TUI mouse reports are not scrollback navigation")
            session.write("\u001b[?1000l")
            wheel(); assertFalse(pane.scroller.hidden)
            session.clearHistory(); assertTrue(pane.scroller.hidden)
            repeat(100) { session.write("again\r\n") }
            view.scrollTo(10); assertFalse(pane.scroller.hidden)
            session.write("\u001b[?1049h"); assertTrue(pane.scroller.hidden)
            session.write("\u001b[?1049l"); assertTrue(pane.scroller.hidden)
            view.scrollTo(10)
        } finally { pane.dispose(); assertTrue(pane.scroller.hidden); window.close() }
    }

    @Test fun preciseWheelScrollbarSearchAndSelectAllNavigateTheSameHistory() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val pane = AppKitTerminalPane(view)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = pane
        try {
            repeat(100) { session.write("line-$it needle\r\n") }
            assertTrue(pane.scroller.enabled)
            assertTrue(pane.scroller.knobProportion in 0.01..0.99)
            assertEquals(1.0, pane.scroller.doubleValue)
            fun wheel(pixels: Int) {
                val cg = checkNotNull(CGEventCreateScrollWheelEvent2(null, kCGScrollEventUnitPixel, 1u, pixels, 0, 0))
                try {
                    val event = checkNotNull(NSEvent.eventWithCGEvent(cg))
                    assertTrue(event.hasPreciseScrollingDeltas)
                    view.scrollWheel(event)
                } finally { CFRelease(cg) }
            }
            wheel(1)
            assertEquals(0, view.scrollOffset, "A single trackpad pixel must not jump three rows")
            repeat(19) { wheel(1) }
            assertEquals(1, view.scrollOffset)
            pane.scroller.doubleValue = 0.0; pane.scrollChanged(pane.scroller)
            assertEquals(session.historySize, view.scrollOffset)
            assertContains(view.accessibilityValue().toString(), "line-0")
            pane.showFind(null); pane.find("line-5 needle")
            assertEquals(1, pane.resultCount)
            assertEquals("line-5 needle", view.accessibilitySelectedText())
            assertContains(view.accessibilityValue().toString(), "line-5 needle")
            pane.find("needle")
            assertEquals(100, pane.resultCount)
            val previousIndex = pane.resultIndex
            pane.findPrevious(null)
            assertEquals(previousIndex - 1, pane.resultIndex)
            pane.hideFind(null)
            assertFalse(pane.finding)
            assertSame(view, window.firstResponder)
            view.selectAll(null)
            assertContains(view.accessibilitySelectedText().orEmpty(), "line-0 needle")
            assertContains(view.accessibilitySelectedText().orEmpty(), "line-99 needle")
            view.clearHistory(null)
            assertEquals(0, session.historySize); assertFalse(pane.scroller.enabled)
        } finally { pane.dispose(); window.close() }
    }

    @Test fun commandClickUsesLinksAndFilePathsUseBracketedPaste() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val opened = mutableListOf<String>()
        view.openLink = { opened += it; true }
        val input = mutableListOf<String>()
        val subscription = session.onInput { input += it.toByteArray().decodeToString() }
        try {
            session.write("https://example.org/\r\n\u001b]8;;https://example.org/osc8\u001b\\click\u001b]8;;\u001b\\")
            for (y in listOf(5.0, 24.0)) {
                val point = view.convertPoint(NSMakePoint(12.0, y), toView = null)
                view.mouseDown(checkNotNull(NSEvent.mouseEventWithType(NSEventTypeLeftMouseDown, point,
                    NSEventModifierFlagCommand, 0.0, window.windowNumber, null, 0, 1, 1.0f)))
            }
            assertEquals(listOf("https://example.org/", "https://example.org/osc8"), opened)
            session.write("\u001b[?2004h")
            val filePasteboard = NSPasteboard.pasteboardWithUniqueName()
            try {
                assertTrue(filePasteboard.writeObjects(listOf(NSURL.fileURLWithPath("/tmp/каталог с пробелом"), NSURL.fileURLWithPath("/tmp/a'b"))))
                view.pasteFilePaths(terminalDroppedFilePaths(filePasteboard))
                filePasteboard.clearContents()
                assertTrue(filePasteboard.writeObjects(listOf(checkNotNull(NSURL.URLWithString("https://example.org/")))))
                assertTrue(terminalDroppedFilePaths(filePasteboard).isEmpty())
            } finally { filePasteboard.releaseGlobally() }
            assertEquals("\u001b[200~'/tmp/каталог с пробелом' '/tmp/a'\\''b' \u001b[201~", input.last())
            assertFailsWith<IllegalArgumentException> { view.pasteFilePaths(listOf("relative")) }
            val rightClick = checkNotNull(NSEvent.mouseEventWithType(NSEventTypeRightMouseDown,
                NSMakePoint(0.0, 0.0), 0u, 0.0, window.windowNumber, null, 0, 1, 1.0f))
            val menu = view.menuForEvent(rightClick)
            assertTrue(menu.itemArray.filterIsInstance<NSMenuItem>().any { it.title == "Select All" })
            val clipboard = NSPasteboard.generalPasteboard
            val saved = clipboard.pasteboardItems.orEmpty().filterIsInstance<NSPasteboardItem>().map { source ->
                NSPasteboardItem().apply {
                    source.types.filterIsInstance<String>().forEach { type -> source.dataForType(type)?.let { setData(it, type) } }
                }
            }
            try {
                menu.performActionForItemAtIndex(2) // Select All through the actual context menu target/action.
                view.menuForEvent(rightClick).performActionForItemAtIndex(0)
                assertContains(clipboard.stringForType(NSPasteboardTypeString).orEmpty(), "https://example.org/")
                clipboard.clearContents(); clipboard.setString("вставка", NSPasteboardTypeString)
                view.menuForEvent(rightClick).performActionForItemAtIndex(1)
                assertEquals("\u001b[200~вставка\u001b[201~", input.last())
            } finally { clipboard.clearContents(); if (saved.isNotEmpty()) clipboard.writeObjects(saved) }
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun russianControlCInterruptsAnActualForegroundPtyProgram() {
        NSApplication.sharedApplication()
        val session = TerminalSession(100, 8)
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        var exit: Int? = null
        val pty = MacOsPty(session, "/bin/sh", listOf("-c", "trap 'printf INTERRUPTED; exit 0' INT; printf READY; while :; do sleep 1; done"), onExit = { exit = it })
        fun text() = (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) autoreleasepool {
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
            }
            assertTrue(condition(), "PTY control-key test timed out")
        }
        try {
            await { "READY" in text() }
            view.keyDown(checkNotNull(NSEvent.keyEventWithType(NSEventTypeKeyDown, NSMakePoint(0.0, 0.0),
                NSEventModifierFlagControl, 0.0, 0, null, "с", "с", false, 8u)))
            await { exit != null }
            assertEquals(0, exit); assertContains(text(), "INTERRUPTED")
        } finally { pty.dispose(); view.dispose() }
    }
}
