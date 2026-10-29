@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.UserNotifications.UNMutableNotificationContent
import kotlin.test.*

class MacOsEffectsTest {
    private class Center : NativeNotificationCenter {
        val checks = ArrayDeque<(Boolean) -> Unit>()
        val completions = ArrayDeque<() -> Unit>()
        val added = mutableListOf<Pair<String, UNMutableNotificationContent>>()
        val removed = mutableListOf<String>()
        var permission: ((Boolean) -> Unit)? = null
        override fun authorize(complete: (Boolean) -> Unit) { permission = complete }
        override fun authorized(complete: (Boolean) -> Unit) { checks.addLast(complete) }
        override fun add(identifier: String, content: UNMutableNotificationContent, complete: () -> Unit) {
            added += identifier to content; completions.addLast(complete)
        }
        override fun remove(identifier: String) { removed += identifier }
    }

    @Test fun notificationDeliveryCoalescesFloodsAndCleansUpLateCompletions() {
        val center = Center(); val delivery = AppKitTerminalNotifications(center)
        delivery.show(TerminalNotification("First", "first"))
        repeat(1000) { delivery.show(TerminalNotification("Latest", "$it")) }
        assertEquals(1, center.checks.size); assertTrue(center.added.isEmpty())
        center.checks.removeFirst()(true)
        assertEquals("first", center.added.single().second.body)
        center.completions.removeFirst()()
        assertEquals(1, center.checks.size)
        center.checks.removeFirst()(true)
        assertEquals("999", center.added.last().second.body)
        assertNotEquals(center.added.first().first, center.added.last().first)
        val lastId = center.added.last().first
        delivery.clear()
        delivery.show(TerminalNotification("New", "new"))
        center.completions.removeFirst()()
        assertEquals(lastId, center.removed.last())
        assertEquals(1, center.checks.size)
        delivery.dispose()
        center.checks.removeFirst()(true)
        assertEquals(2, center.added.size)
        assertTrue(center.completions.isEmpty())
    }

    @Test fun deniedOrDisposedNotificationRequestsCannotDeliver() {
        val center = Center(); val delivery = AppKitTerminalNotifications(center)
        delivery.show(TerminalNotification("", "denied"))
        center.checks.removeFirst()(false)
        assertTrue(center.added.isEmpty())
        var granted: Boolean? = null
        delivery.requestPermission { granted = it }
        delivery.dispose(); center.permission!!(true)
        assertEquals(false, granted)
        delivery.show(TerminalNotification("", "disposed"))
        assertTrue(center.checks.isEmpty())
    }

    @Test fun progressOverlayDrawsProportionalColoredPixelsAndResizes() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val window = NSWindow(NSMakeRect(0.0, 0.0, 400.0, 240.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        try {
            val progress = view.progressIndicator
            session.write("\u001b]9;4;2;50\u0007")
            fun pixels(): NSBitmapImageRep {
                val bitmap = checkNotNull(progress.bitmapImageRepForCachingDisplayInRect(progress.bounds))
                progress.cacheDisplayInRect(progress.bounds, bitmap)
                return bitmap
            }
            val half = pixels()
            val filled = checkNotNull(half.colorAtX(half.pixelsWide / 4, half.pixelsHigh / 2))
            val empty = checkNotNull(half.colorAtX(half.pixelsWide * 3 / 4, half.pixelsHigh / 2))
            assertNotEquals(filled, empty)
            assertTrue(filled.redComponent > filled.greenComponent)
            session.write("\u001b]9;4;4\u0007")
            val paused = pixels()
            assertEquals(paused.colorAtX(paused.pixelsWide / 4, paused.pixelsHigh / 2),
                paused.colorAtX(paused.pixelsWide * 3 / 4, paused.pixelsHigh / 2))
            window.setContentSize(NSMakeSize(600.0, 240.0))
            assertTrue(pixels().pixelsWide > half.pixelsWide)
            assertNull(progress.hitTest(NSMakePoint(1.0, 1.0)))
            session.write("\u001b]9;4;0\u0007")
            assertTrue(progress.hidden)
        } finally { view.dispose(); window.close() }
    }

    private class Delivery : TerminalNotificationDelivery {
        val notifications = mutableListOf<TerminalNotification>()
        var permissionRequests = 0; var clears = 0; var disposed = false
        override fun requestPermission(complete: (Boolean) -> Unit) { permissionRequests++; complete(true) }
        override fun show(notification: TerminalNotification) { notifications += notification }
        override fun clear() { clears++ }
        override fun dispose() { disposed = true }
    }

    @Test fun nativeEffectsRespectHostPolicyAndDisposeWithoutDesktopSideEffects() {
        NSApplication.sharedApplication()
        val session = TerminalSession(); val delivery = Delivery(); var allowed = false
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE,
            notificationPolicy = { allowed }, notifications = delivery)
        try {
            session.write("\u001b]9;denied\u0007")
            assertTrue(delivery.notifications.isEmpty()); assertEquals(0, delivery.permissionRequests)
            view.requestNotificationPermission { allowed = it }
            session.write("\u001b]777;notify;Title;世界\u0007")
            assertEquals(listOf(TerminalNotification("Title", "世界")), delivery.notifications)
            val content = nativeNotificationContent(delivery.notifications.single())
            assertEquals("Title", content.title); assertEquals("世界", content.body)
            session.write("\u001b[?2026h\u001b]9;4;1;50\u0007")
            assertFalse(view.progressIndicator.hidden)
            assertEquals(50, view.progressIndicator.presentation.percentage)
            session.write("\u001b]9;4;4\u0007")
            assertEquals(100, view.progressIndicator.presentation.percentage)
            session.write("\u001b]9;4;3\u0007")
            assertNull(view.progressIndicator.presentation.percentage)
            view.becomeFirstResponder(); assertEquals(1, delivery.clears)
            session.reset(); assertTrue(view.progressIndicator.hidden)
            view.dispose(); assertTrue(delivery.disposed)
            session.write("\u001b]9;after disposal\u0007\u001b]9;4;1;80\u0007")
            assertEquals(1, delivery.notifications.size); assertTrue(view.progressIndicator.hidden)
        } finally { view.dispose() }
    }
}
