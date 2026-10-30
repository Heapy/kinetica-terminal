@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.io.encoding.Base64
import kotlin.math.floor
import kotlin.test.*

class MacOsDndTest {
    @Test fun actualDestinationCallbacksNegotiateAndServeLazyNativeAndCustomFormats() {
        for (backend in listOf(TerminalRendering.SOFTWARE, TerminalRendering.GPU)) isolated(backend) { h ->
            val mime = "application/x-kinetica-dnd"
            val customType = checkNotNull(UTType.typeWithMIMEType(mime)?.identifier)
            val calls = mutableListOf<String>()
            val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
                override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                    val type = checkNotNull(provideDataForType); calls += type
                    if (type == customType) item.setData(data(byteArrayOf(0, -1, -128, 65)), type)
                    else item.setString("<b>hello 世界</b>", type)
                }
            }
            val item = NSPasteboardItem().apply {
                setString("hello 世界", NSPasteboardTypeString)
                assertTrue(setDataProvider(provider, listOf(NSPasteboardTypeHTML, customType)))
            }
            assertTrue(h.board.writeObjects(listOf(item)))
            h.register(mime)
            assertTrue(UTType.typeWithMIMEType(mime)?.identifier in h.view.registeredDraggedTypes)
            assertEquals(NSDragOperationCopy, h.view.draggingEntered(h.drag), h.diagnostic())
            assertTrue(calls.isEmpty(), "Hover must not load native pasteboard providers")
            val move = h.messages().last()
            val scale = h.window.backingScaleFactor
            assertContains(move, ":X=${floor(45 * scale).toInt()}:Y=${floor(38 * scale).toInt()}:o=3:i=7:")
            h.session.write("\u001b]72;t=m:o=2:m=1;text/\u0007")
            assertEquals(NSDragOperationCopy, h.view.draggingUpdated(h.drag))
            h.session.write("\u001b]72;m=0;plain\u0007")
            assertEquals(NSDragOperationMove, h.view.draggingUpdated(h.drag))
            h.drag.mask = NSDragOperationCopy
            assertEquals(NSDragOperationNone, h.view.draggingUpdated(h.drag))
            assertFalse(h.view.prepareForDragOperation(h.drag))
            h.drag.mask = NSDragOperationCopy or NSDragOperationMove
            assertTrue(h.view.prepareForDragOperation(h.drag)); assertTrue(calls.isEmpty())
            assertTrue(h.view.performDragOperation(h.drag))
            assertEquals(listOf(checkNotNull(NSPasteboardTypeHTML), customType), calls)
            val dropped = h.messages().last { it.startsWith("t=M:") }
            val types = dropped.substringAfter(';').trim().split(' ')
            assertEquals(listOf("text/plain", "text/html", mime), types)
            h.view.draggingEnded(h.drag)
            assertContentEquals("hello 世界".encodeToByteArray(), h.request(1))
            assertContentEquals("<b>hello 世界</b>".encodeToByteArray(), h.request(2))
            assertContentEquals(byteArrayOf(0, -1, -128, 65), h.request(3))
            h.session.write("\u001b]72;t=r:o=2\u0007")
            h.wire.clear(); h.session.write("\u001b]72;t=r:x=1\u0007")
            assertContains(h.wire.toString(), "ENOENT:no drop data available")
            h.session.write("\u001b]72;t=A\u0007")
            assertEquals(listOf(NSPasteboardTypeFileURL), h.view.registeredDraggedTypes)
        }
    }

    @Test fun fileUrlsRemainMetadataAndUnregisteredClientsKeepShellQuotedPaste() = isolated { h ->
        val paths = listOf("/tmp/no such kinetica file", "/tmp/a'b-世界")
        assertTrue(h.board.writeObjects(paths.map { NSURL.fileURLWithPath(it) }))
        h.session.write("\u001b[?2004h")
        assertEquals(NSDragOperationCopy, h.view.draggingEntered(h.drag), h.diagnostic())
        assertTrue(h.view.prepareForDragOperation(h.drag))
        assertTrue(h.view.performDragOperation(h.drag))
        assertEquals("\u001b[200~" + paths.joinToString(" ", postfix = " ", transform = ::terminalShellQuote) + "\u001b[201~", h.wire.toString())
        h.register(); h.wire.clear(); h.drag.number++
        assertEquals(NSDragOperationCopy, h.view.draggingEntered(h.drag))
        assertTrue(h.view.performDragOperation(h.drag))
        val types = h.messages().last { it.startsWith("t=M:") }.substringAfter(';').trim().split(' ')
        assertEquals(listOf("text/plain", "text/uri-list"), types)
        assertEquals("/tmp/no\\ such\\ kinetica\\ file /tmp/a\\'b-世界", h.request(1).decodeToString())
        assertEquals(paths.joinToString("") { NSURL.fileURLWithPath(it).absoluteString + "\r\n" }, h.request(2).decodeToString())
    }

    @Test fun ordinaryUrlsAndEmptyBinaryDataHaveCompleteRepresentations() = isolated { h ->
        val mime = "application/x-empty-dnd"
        val item = NSPasteboardItem().apply {
            setString("https://example.org/a%20b", NSPasteboardTypeURL)
            assertTrue(setData(NSData(), checkNotNull(UTType.typeWithMIMEType(mime)?.identifier)))
        }
        assertTrue(h.board.writeObjects(listOf(item)))
        h.register(mime)
        h.view.draggingEntered(h.drag)
        assertTrue(h.view.performDragOperation(h.drag))
        assertEquals("https://example.org/a%20b\r\n", h.request(1).decodeToString())
        h.wire.clear(); h.session.write("\u001b]72;t=r:x=2\u0007")
        assertEquals("\u001b]72;t=r:x=2:i=7\u0007", h.wire.toString())
    }

    @Test fun clientRejectionAndLeaveDoNotLoadDataAndNewGesturesResetAcceptance() = isolated { h ->
        var calls = 0
        val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
            override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                calls++; item.setString("private", provideDataForType)
            }
        }
        val item = NSPasteboardItem().apply { setDataProvider(provider, listOf(NSPasteboardTypeHTML)) }
        assertTrue(h.board.writeObjects(listOf(item))); h.register()
        h.view.draggingEntered(h.drag)
        h.session.write("\u001b]72;t=m:o=0\u0007")
        assertEquals(NSDragOperationNone, h.view.draggingUpdated(h.drag))
        assertFalse(h.view.prepareForDragOperation(h.drag)); assertFalse(h.view.performDragOperation(h.drag))
        assertEquals(0, calls)
        h.drag.number++
        assertEquals(NSDragOperationCopy, h.view.draggingEntered(h.drag))
        h.view.draggingExited(h.drag)
        assertTrue(h.messages().last().startsWith("t=m:x=-1:y=-1:"))
        assertEquals(0, calls)
    }

    @Test fun providerReentrancyCannotPublishDataToANewOwnerOrDetachedWindow() {
        for (change in listOf("pasteboard", "registration", "detach", "dispose")) isolated { h ->
            var calls = 0
            val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
                override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                    calls++
                    item.setString("stale", provideDataForType)
                    when (change) {
                        "pasteboard" -> { h.board.clearContents(); h.board.setString("new owner", NSPasteboardTypeString) }
                        "registration" -> { h.session.write("\u001b]72;t=A\u0007"); h.register() }
                        "detach" -> { h.view.removeFromSuperview(); h.container.addSubview(h.view) }
                        else -> h.view.dispose()
                    }
                }
            }
            val item = NSPasteboardItem().apply { setDataProvider(provider, listOf(NSPasteboardTypeHTML)) }
            assertTrue(h.board.writeObjects(listOf(item))); h.register()
            h.view.draggingEntered(h.drag); h.wire.clear()
            assertFalse(h.view.performDragOperation(h.drag), change)
            assertEquals(1, calls, "$change must reach the lazy provider")
            assertTrue(h.messages().none { it.startsWith("t=M:") }, change)
            assertNull(h.session.kittyDnd.state?.items, change)
        }
    }

    @Test fun overBudgetDataIsRejectedAndBlockedDeliveryResumesTheCapturedDrop() = isolated { h ->
        val mime = "application/x-dnd-limit"
        val large = NSPasteboardItem().apply { assertTrue(setData(data(ByteArray(17 * 1024 * 1024)), checkNotNull(UTType.typeWithMIMEType(mime)?.identifier))) }
        assertTrue(h.board.writeObjects(listOf(large))); h.register(mime)
        h.view.draggingEntered(h.drag); h.wire.clear()
        assertFalse(h.view.performDragOperation(h.drag)); assertTrue(h.messages().none { it.startsWith("t=M:") })
        h.board.clearContents(); h.board.setString("held", NSPasteboardTypeString)
        h.drag.number++; h.tracked = true
        h.view.draggingEntered(h.drag)
        repeat(100) { h.view.draggingUpdated(h.drag) }
        assertTrue(h.session.outputPaused)
        assertTrue(h.view.performDragOperation(h.drag))
        assertTrue(h.messages().none { it.startsWith("t=M:") })
        h.board.clearContents(); h.board.setString("replacement", NSPasteboardTypeString)
        while (h.receipts.isNotEmpty()) { h.receipts.removeFirst()(true); h.pump() }
        assertFalse(h.session.outputPaused)
        assertEquals(1, h.messages().count { it.startsWith("t=M:") })
        assertTrue(h.messages().count { it.startsWith("t=m:") } <= 2)
        h.tracked = false
        assertEquals("held", h.request(1).decodeToString())
        h.view.dispose()
        assertNull(h.session.kittyDnd.state!!.items)
        assertTrue(h.view.registeredDraggedTypes.isEmpty())
        h.session.write("alive")
        assertEquals("alive", h.session.screenLine(0).text())
    }

    private class Harness(backend: TerminalRendering) {
        val app = NSApplication.sharedApplication()
        val board = NSPasteboard.pasteboardWithUniqueName()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = backend)
        val window = NSWindow(NSMakeRect(100.0, 100.0, 600.0, 400.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        val container = NSView(NSMakeRect(0.0, 0.0, 600.0, 400.0))
        val wire = StringBuilder()
        val receipts = ArrayDeque<(Boolean) -> Unit>()
        var tracked = false
        val observation = session.onInput {
            wire.append(it.toByteArray().decodeToString())
            if (tracked) receipts += (it as TerminalInputData.ProtocolReply).trackDelivery()
        }
        val drag: DragInfo
        init {
            window.setReleasedWhenClosed(false); window.contentView = container
            view.setFrame(NSMakeRect(5.0, 10.0, 590.0, 380.0)); container.addSubview(view)
            window.orderFrontRegardless(); pump()
            drag = DragInfo(board, window, view.convertPoint(NSMakePoint(45.0, 38.0), toView = null))
        }
        fun pump() { NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.03)); app.updateWindows() }
        fun register(mime: String = "text/plain text/html") = session.write("\u001b]72;t=a:i=7;$mime\u0007")
        fun diagnostic(): String = "windowEqual=${view.window == window}, windowSame=${view.window === window}, " +
            "senderSame=${drag.draggingDestinationWindow === view.window}, hidden=${view.hiddenOrHasHiddenAncestor}, " +
            "mask=${drag.draggingSourceOperationMask}, board=${board.name}, senderBoard=${drag.draggingPasteboard.name}, " +
            "types=${board.types}, registered=${session.kittyDnd.state != null}, wire=$wire"
        fun messages() = Regex("\u001b]72;([^\u0007\u001b]*)(?:\u0007|\u001b\\\\)").findAll(wire).map { it.groupValues[1] }.toList()
        fun request(index: Int): ByteArray {
            wire.clear(); session.write("\u001b]72;t=r:x=$index\u0007")
            val frames = messages()
            assertTrue(frames.last() == "t=r:x=$index:i=7")
            return frames.dropLast(1).flatMap { Base64.decode(it.substringAfter(';')).toList() }.toByteArray()
        }
        fun close() {
            view.dispose(); observation.dispose()
            while (receipts.isNotEmpty()) receipts.removeFirst()(false)
            window.orderOut(null); window.close(); board.releaseGlobally()
        }
    }
    private fun isolated(backend: TerminalRendering = TerminalRendering.SOFTWARE, test: (Harness) -> Unit) {
        val h = Harness(backend)
        try { test(h) } finally { h.close() }
    }
    private class DragInfo(val board: NSPasteboard, val destination: NSWindow, val point: CValue<NSPoint>) : NSObject(), NSDraggingInfoProtocol {
        var mask: NSDragOperation = NSDragOperationCopy or NSDragOperationMove
        var number = 1L
        override fun draggingDestinationWindow(): NSWindow = destination
        override fun draggingSourceOperationMask(): NSDragOperation = mask
        override fun draggingLocation(): CValue<NSPoint> = point
        override fun draggedImageLocation(): CValue<NSPoint> = point
        override fun draggedImage(): NSImage? = null
        override fun draggingPasteboard(): NSPasteboard = board
        override fun draggingSource(): Any? = null
        override fun draggingSequenceNumber(): Long = number
        override fun slideDraggedImageTo(screenPoint: CValue<NSPoint>) {}
        override fun namesOfPromisedFilesDroppedAtDestination(dropDestination: NSURL): List<*>? = null
        override fun draggingFormation(): NSDraggingFormation = NSDraggingFormationDefault
        override fun setDraggingFormation(draggingFormation: NSDraggingFormation) {}
        override fun animatesToDestination(): Boolean = false
        override fun setAnimatesToDestination(animatesToDestination: Boolean) {}
        override fun numberOfValidItemsForDrop(): Long = 1
        override fun setNumberOfValidItemsForDrop(numberOfValidItemsForDrop: Long) {}
        override fun enumerateDraggingItemsWithOptions(enumOpts: NSDraggingItemEnumerationOptions, forView: NSView?, classes: List<*>,
            searchOptions: Map<Any?, *>, usingBlock: (NSDraggingItem?, Long, CPointer<BooleanVar>?) -> Unit) {}
        override fun springLoadingHighlight(): NSSpringLoadingHighlight = NSSpringLoadingHighlightNone
        override fun resetSpringLoading() {}
    }
    companion object {
        private fun data(bytes: ByteArray): NSData = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
    }
}
