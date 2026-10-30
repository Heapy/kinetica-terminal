@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class MacOsAccessibilityTest {
    @Test fun commandJRevealsExistingSelectionWithoutSendingKittyEvents() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 640.0, 320.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        val output = StringBuilder()
        val subscription = session.onInput { output.append(it.toByteArray().decodeToString()) }
        var scrolls = 0
        view.onScrollActivity = { scrolls++ }
        fun key(flags: ULong = NSEventModifierFlagCommand, repeat: Boolean = false, release: Boolean = false) =
            requireNotNull(NSEvent.keyEventWithType(if (release) NSEventTypeKeyUp else NSEventTypeKeyDown,
                NSMakePoint(0.0, 0.0), flags, 0.0, window.windowNumber, null, "j", "j", repeat, 38u))
        try {
            assertTrue(window.makeFirstResponder(view))
            repeat(80) { session.write("row-$it\r\n") }
            assertTrue(view.reveal(session.search("row-3").matches.first()))
            view.scrollTo(0)
            session.write("later\r\n\u001b[>31u")
            scrolls = 0
            window.sendEvent(key()); window.sendEvent(key(repeat = true)); window.sendEvent(key(release = true))
            assertEquals(session.historySize - 3, view.scrollOffset)
            assertTrue((view.accessibilityValue() as String).startsWith("row-3\n"))
            assertEquals("row-3", view.accessibilitySelectedText())
            assertEquals(1, scrolls, "Only an actual viewport change reports scroll activity")
            assertEquals("", output.toString())
            // The binding is performable and exact: extra modifiers or no selection reach the application.
            view.scrollTo(0)
            view.keyDown(key(NSEventModifierFlagCommand or NSEventModifierFlagControl))
            view.keyUp(key(NSEventModifierFlagCommand or NSEventModifierFlagControl, release = true))
            assertEquals(0, view.scrollOffset)
            assertTrue(output.isNotEmpty())
            output.clear(); view.clearSelection()
            view.keyDown(key()); view.keyUp(key(release = true))
            assertTrue(output.contains("\u001b[106;9"))
            assertTrue(output.contains(":3"))
            assertFalse(view.scrollToSelection())
        } finally { subscription.dispose(); view.dispose(); window.close() }
    }

    @Test fun textQueriesUseOneUtf16CoordinateSpaceAndRejectInvalidRanges() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        try {
            session.write("a😀e\u0301界\r\nsecond")
            val value = view.accessibilityValue() as String
            assertTrue(value.startsWith("a😀e\u0301界\nsecond\n"))
            assertEquals(value.length.toLong(), view.accessibilityNumberOfCharacters())
            assertEquals(0uL to value.length.toULong(), view.accessibilityVisibleCharacterRange().pair())
            assertEquals("Terminal content area", view.accessibilityHelp())
            assertEquals("😀e\u0301界", view.accessibilityStringForRange(NSMakeRange(1u, 5u)))
            val attributed = assertNotNull(view.accessibilityAttributedStringForRange(NSMakeRange(1u, 5u)))
            assertEquals("😀e\u0301界", attributed.string)
            assertTrue(attributed.attribute(NSFontAttributeName, 0u, null) is NSFont)
            assertEquals(0L, view.accessibilityLineForIndex(5))
            assertEquals(1L, view.accessibilityLineForIndex(7))
            assertEquals(value.count { it == '\n' }.toLong(), view.accessibilityLineForIndex(value.length.toLong()))
            assertEquals(NSNotFound, view.accessibilityLineForIndex(-1))
            assertEquals(NSNotFound, view.accessibilityLineForIndex(Long.MAX_VALUE))
            assertEquals("", view.accessibilityStringForRange(NSMakeRange(value.length.toULong(), 0u)))
            for (range in listOf(NSMakeRange(2u, 1u), NSMakeRange(1u, 1u), NSMakeRange(ULong.MAX_VALUE, 0u),
                NSMakeRange(0u, ULong.MAX_VALUE), NSMakeRange(value.length.toULong(), 1u))) {
                assertNull(view.accessibilityStringForRange(range))
                assertNull(view.accessibilityAttributedStringForRange(range))
            }
        } finally { view.dispose() }
    }

    @Test fun selectionQueriesFollowSearchScrollAndHeldFrames() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        try {
            session.write("a😀e\u0301界\r\na😀e\u0301界")
            assertNull(view.accessibilitySelectedText())
            assertTrue(view.accessibilitySelectedTextRanges().isEmpty())
            assertTrue(view.reveal(session.search("😀e\u0301界").matches[1]))
            assertEquals(8uL to 5uL, view.accessibilitySelectedTextRange().pair())
            val ranges = view.accessibilitySelectedTextRanges()
            assertEquals(1, ranges.size)
            assertEquals(8uL to 5uL, (ranges.single() as NSValue).rangeValue.pair())
            assertEquals("😀e\u0301界", view.accessibilitySelectedText())
            val value = view.accessibilityValue()
            session.write("\u001b[?2026h\u001b[2;1Hnew text")
            assertEquals(value, view.accessibilityValue())
            assertEquals(8uL to 5uL, view.accessibilitySelectedTextRange().pair())
            session.write("\u001b[?2026l")
            assertNull(view.accessibilitySelectedText())
            assertEquals(0uL to 0uL, view.accessibilitySelectedTextRange().pair())
            repeat(60) { session.write("\r\nrow-$it") }
            view.scrollTo(session.historySize)
            assertTrue((view.accessibilityValue() as String).startsWith("a😀e\u0301界\nnew text"))
            view.selectAll(null)
            assertEquals(view.accessibilityVisibleCharacterRange().pair(), view.accessibilitySelectedTextRange().pair())
        } finally { view.dispose() }
    }

    @Test fun selectionNotificationsDebounceAndCancelOnDisposal() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        var notifications = 0
        view.notifyAccessibilitySelectionChanged = { notifications++ }
        fun flush() = NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.2))
        try {
            session.write("abc")
            flush(); assertEquals(0, notifications, "Ordinary output is not a selection notification")
            view.selectAll(null); view.clearSelection(); view.selectAll(null)
            assertEquals(0, notifications)
            flush(); assertEquals(1, notifications)
            view.selectAll(null)
            flush(); assertEquals(1, notifications, "Unchanged selection is not announced again")
            view.clearSelection()
            flush(); assertEquals(2, notifications)
            view.selectAll(null)
            view.dispose()
            flush(); assertEquals(2, notifications, "No notification after view teardown")
        } finally { view.dispose() }
    }

    private fun CValue<NSRange>.pair(): Pair<ULong, ULong> = useContents { location to length }
}
