@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.io.encoding.Base64
import kotlin.test.*

class MacOsNativeMimeClipboardTest {
    @Test fun nativeFormatsAndCustomBinaryMimeRoundTripInOneItem() = isolated { board ->
        val clipboard = TerminalNativeMimeClipboard(board)
        val contents = listOf(
            content("text/plain", "hello 世界"), content("text/html", "<b>hello 世界</b>"),
            TerminalMimeClipboardContent("image/png", Base64.Default.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")),
            TerminalMimeClipboardContent("application/x-kinetica-binary", byteArrayOf(0, -1, 127, 0)),
        )
        assertEquals(TerminalClipboardResult.SUCCESS, clipboard.put(contents))
        assertEquals(1, board.pasteboardItems?.size)
        assertEquals("hello 世界", board.stringForType(NSPasteboardTypeString))
        assertEquals("<b>hello 世界</b>", board.stringForType(NSPasteboardTypeHTML))
        val result = clipboard.get(contents.map { it.mime }, list = true)
        assertEquals(TerminalClipboardResult.SUCCESS, result.result)
        // AppKit can advertise additional convertible types, e.g. TIFF for a PNG item.
        assertTrue(result.available.containsAll(contents.map { it.mime }))
        assertEquals(result.available.distinct(), result.available)
        assertEquals(contents.map { it.mime }, result.contents.map { it.mime })
        contents.zip(result.contents).forEach { (expected, actual) -> assertContentEquals(expected.data, actual.data) }
        assertEquals(TerminalClipboardResult.SUCCESS, clipboard.put(emptyList()))
        assertTrue(board.types.isNullOrEmpty())
        assertTrue(clipboard.get(listOf("text/plain"), list = true).contents.isEmpty())
    }

    @Test fun listingDoesNotLoadProvidersAndReadsOnlyRequestedTypesOnce() = isolated { board ->
        val calls = mutableListOf<String>()
        val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
            override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                calls += checkNotNull(provideDataForType)
                item.setString(if (provideDataForType == NSPasteboardTypeHTML) "<b>lazy</b>" else "lazy", provideDataForType)
            }
        }
        val item = NSPasteboardItem()
        assertTrue(item.setDataProvider(provider, listOf(NSPasteboardTypeString, NSPasteboardTypeHTML)))
        assertTrue(board.writeObjects(listOf(item)))
        val clipboard = TerminalNativeMimeClipboard(board)
        val listing = clipboard.get(emptyList(), list = true)
        assertEquals(listOf("text/plain", "text/html"), listing.available)
        assertTrue(listing.contents.isEmpty())
        assertTrue(calls.isEmpty(), "Listing must not invoke lazy providers")
        val read = clipboard.get(listOf("text/html", "application/x-absent", "text/html"))
        assertEquals(listOf(checkNotNull(NSPasteboardTypeHTML)), calls)
        assertEquals(listOf("text/html"), read.contents.map { it.mime })
        assertEquals("<b>lazy</b>", read.contents.single().data.decodeToString())
        assertTrue(read.available.isEmpty())
    }

    @Test fun fileCopiesExposeEscapedPathsAndCrLfUriList() = isolated { board ->
        val paths = listOf("/tmp/каталог с пробелом", "/tmp/a'b\\c\t$", "/tmp/[x];(y)")
        assertTrue(board.writeObjects(paths.map { NSURL.fileURLWithPath(it) }))
        val result = TerminalNativeMimeClipboard(board).get(listOf("text/plain", "text/uri-list"), list = true)
        assertEquals(TerminalClipboardResult.SUCCESS, result.result)
        assertEquals(listOf("text/plain", "text/uri-list"), result.available.take(2))
        assertEquals("/tmp/каталог\\ с\\ пробелом /tmp/a\\'b\\\\c\\\t\\$ /tmp/\\[x\\]\\;\\(y\\)", result.contents[0].data.decodeToString())
        assertEquals(paths.joinToString("") { NSURL.fileURLWithPath(it).absoluteString + "\r\n" }, result.contents[1].data.decodeToString())
    }

    @Test fun textFromMultipleItemsIsJoinedAndNonFileUrlsAreNotUriLists() = isolated { board ->
        val first = NSPasteboardItem().apply { setString("one", NSPasteboardTypeString) }
        val second = NSPasteboardItem().apply { setString("two", NSPasteboardTypeString); setString("https://example.org", NSPasteboardTypeFileURL) }
        assertTrue(board.writeObjects(listOf(first, second)))
        val result = TerminalNativeMimeClipboard(board).get(listOf("text/plain", "text/uri-list"))
        assertEquals(listOf("text/plain"), result.contents.map { it.mime })
        assertEquals("one two", result.contents.single().data.decodeToString())
    }

    @Test fun rejectedWritePreservesAllPreviousFormats() = isolated { board ->
        val clipboard = TerminalNativeMimeClipboard(board, writeLimit = 16)
        assertEquals(TerminalClipboardResult.SUCCESS, clipboard.put(listOf(content("text/plain", "old"), content("text/html", "<b>old</b>"))))
        val version = board.changeCount
        assertEquals(TerminalClipboardResult.INVALID_DATA, clipboard.put(listOf(content("text/html", "new"), TerminalMimeClipboardContent("text/plain", byteArrayOf(-1)))))
        assertEquals(TerminalClipboardResult.IO_ERROR, clipboard.put(listOf(content("text/plain", "123456789"), content("text/html", "123456789"))))
        assertEquals(version, board.changeCount)
        assertEquals("old", board.stringForType(NSPasteboardTypeString))
        assertEquals("<b>old</b>", board.stringForType(NSPasteboardTypeHTML))
    }

    @Test fun aggregateReadLimitIncludesUtf8AndReturnsNoPartialContents() = isolated { board ->
        val writer = TerminalNativeMimeClipboard(board)
        assertEquals(TerminalClipboardResult.SUCCESS, writer.put(listOf(content("text/plain", "世界"), content("text/html", "1234"))))
        val reader = TerminalNativeMimeClipboard(board, readLimit = 6)
        assertEquals("世界", reader.get(listOf("text/plain")).contents.single().data.decodeToString())
        val tooLarge = reader.get(listOf("text/html", "text/plain"))
        assertEquals(TerminalClipboardResult.IO_ERROR, tooLarge.result)
        assertTrue(tooLarge.contents.isEmpty())
        assertEquals(TerminalClipboardResult.IO_ERROR, TerminalNativeMimeClipboard(board, readLimit = 1).get(listOf("text/plain")).result)
    }

    @Test fun unsupportedLocationsDoNotAccessStandardClipboard() = isolated { board ->
        val clipboard = TerminalNativeMimeClipboard(board)
        board.setString("untouched", NSPasteboardTypeString)
        val version = board.changeCount
        for (location in TerminalClipboardLocation.entries.filter { it != TerminalClipboardLocation.STANDARD }) {
            var read: TerminalMimeClipboardRead? = null
            clipboard.read(location, listOf("text/plain"), true) { read = it }
            assertEquals(TerminalClipboardResult.UNSUPPORTED, read?.result)
            var write: TerminalClipboardResult? = null
            clipboard.write(location, emptyList()) { write = it }
            assertEquals(TerminalClipboardResult.UNSUPPORTED, write)
        }
        assertEquals(version, board.changeCount)
    }

    @Test fun clipboardReplacementDuringLazyReadReturnsBusyWithoutStaleData() = isolated { board ->
        val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
            override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                item.setString("old", provideDataForType)
                board.clearContents()
                board.setString("new owner", NSPasteboardTypeString)
            }
        }
        val item = NSPasteboardItem()
        assertTrue(item.setDataProvider(provider, listOf(NSPasteboardTypeHTML)))
        assertTrue(board.writeObjects(listOf(item)))
        val result = TerminalNativeMimeClipboard(board).get(listOf("text/html", "text/plain"), list = true)
        assertEquals(TerminalClipboardResult.BUSY, result.result)
        assertTrue(result.contents.isEmpty())
        assertTrue(result.available.isEmpty())
        assertEquals("new owner", board.stringForType(NSPasteboardTypeString))
    }

    @Test fun equivalentNativeRepresentationsAreDeduplicatedAndConflictsRejectTheWrite() = isolated { board ->
        val clipboard = TerminalNativeMimeClipboard(board)
        assertEquals(TerminalClipboardResult.SUCCESS, clipboard.put(listOf(content("text/plain", "one"), content("text/plain", "one"))))
        val version = board.changeCount
        assertEquals(TerminalClipboardResult.INVALID_DATA, clipboard.put(listOf(content("text/plain", "one"), content("text/plain", "two"))))
        assertEquals(version, board.changeCount)
        assertEquals("one", board.stringForType(NSPasteboardTypeString))
    }

    @Test fun viewPolicyAndDisposalControlActualMimeClipboardAccess() = isolated { board ->
        NSApplication.sharedApplication()
        board.setString("original", NSPasteboardTypeString)
        val session = TerminalSession()
        var allowed = false
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE,
            mimeClipboard = TerminalNativeMimeClipboard(board),
            mimeClipboardPolicy = TerminalMimeClipboardPolicy { _, done -> done(TerminalClipboardDecision(allowed)) })
        val replies = StringBuilder()
        val input = session.onInput {
            val delivered = (it as? TerminalInputData.ProtocolReply)?.trackDelivery()
            replies.append(it.toByteArray().decodeToString())
            delivered?.invoke(true)
        }
        fun write(value: String) {
            session.write(osc("type=write") + osc("type=wdata:mime=${b64("text/plain")};${b64(value)}") + osc("type=wdata"))
        }
        try {
            write("denied")
            assertEquals("original", board.stringForType(NSPasteboardTypeString))
            assertContains(replies.toString(), "status=EPERM")
            allowed = true; replies.clear()
            write("allowed")
            assertEquals("allowed", board.stringForType(NSPasteboardTypeString))
            assertEquals(osc("type=write:status=DONE"), replies.toString())
            replies.clear()
            session.write(osc("type=read;${b64("text/plain")}"))
            assertEquals(osc("type=read:status=OK") + osc("type=read:status=DATA:mime=${b64("text/plain")};${b64("allowed")}") + osc("type=read:status=DONE"), replies.toString())
            view.dispose(); replies.clear()
            write("detached")
            assertEquals("allowed", board.stringForType(NSPasteboardTypeString))
            assertContains(replies.toString(), "status=ENOSYS")
        } finally { view.dispose(); input.dispose() }
    }

    @Test fun userPasteListsLazyFormatsAndAuthorizesExactlyOneRead() = isolated { board ->
        NSApplication.sharedApplication()
        val loads = mutableListOf<String>()
        val provider = object : NSObject(), NSPasteboardItemDataProviderProtocol {
            override fun pasteboard(pasteboard: NSPasteboard?, item: NSPasteboardItem, provideDataForType: String?) {
                loads += checkNotNull(provideDataForType)
                item.setString("private clipboard", provideDataForType)
            }
        }
        val item = NSPasteboardItem()
        assertTrue(item.setDataProvider(provider, listOf(NSPasteboardTypeString, NSPasteboardTypeHTML)))
        assertTrue(board.writeObjects(listOf(item)))
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE,
            mimeClipboard = TerminalNativeMimeClipboard(board),
            mimeClipboardPolicy = TerminalMimeClipboardPolicy.WRITE_AND_PASTE)
        view.readPasteText = { error("Mode 5522 must not read the ordinary clipboard text") }
        view.pasteConfirmation = TerminalPasteConfirmation { _, _ -> error("Mode 5522 and DnD do not confirm text paste") }
        val replies = StringBuilder()
        val input = session.onInput {
            val delivered = (it as? TerminalInputData.ProtocolReply)?.trackDelivery()
            replies.append(it.toByteArray().decodeToString())
            delivered?.invoke(true)
        }
        try {
            session.write("\u001b[?5522h\u001b[?2004h")
            view.paste(null)
            assertTrue(loads.isEmpty(), "Paste event must only list metadata")
            val event = replies.toString()
            val password = event.substringAfter(":pw=").substringBefore("\u001b\\")
            assertEquals(22, Base64.decode(password).size)
            assertContains(event, b64("text/plain text/html\n"))
            assertFalse(event.contains("private clipboard"))
            assertFalse(event.contains("\u001b[200~"))
            val request = osc("type=read:name=${b64("Paste event")}:pw=$password;${b64("text/html")}")
            replies.clear(); session.write(request)
            assertEquals(listOf(checkNotNull(NSPasteboardTypeHTML)), loads)
            assertEquals(osc("type=read:status=OK") +
                osc("type=read:status=DATA:mime=${b64("text/html")};${b64("private clipboard")}") +
                osc("type=read:status=DONE"), replies.toString())
            replies.clear(); session.write(request)
            assertEquals(osc("type=read:status=EPERM"), replies.toString())
            assertEquals(1, loads.size)
            replies.clear(); view.pasteFilePaths(listOf("/tmp/file name"))
            assertEquals("\u001b[200~'/tmp/file name' \u001b[201~", replies.toString())
            assertEquals(1, loads.size, "Dropped paths must not access clipboard")
        } finally { view.dispose(); input.dispose() }
    }

    private fun isolated(test: (NSPasteboard) -> Unit) {
        val board = NSPasteboard.pasteboardWithUniqueName()
        try { test(board) } finally { board.releaseGlobally() }
    }
    private fun TerminalNativeMimeClipboard.get(types: List<String>, list: Boolean = false): TerminalMimeClipboardRead {
        var result: TerminalMimeClipboardRead? = null
        read(TerminalClipboardLocation.STANDARD, types, list) { assertNull(result); result = it }
        return assertNotNull(result)
    }
    private fun TerminalNativeMimeClipboard.put(contents: List<TerminalMimeClipboardContent>): TerminalClipboardResult {
        var result: TerminalClipboardResult? = null
        write(TerminalClipboardLocation.STANDARD, contents) { assertNull(result); result = it }
        return assertNotNull(result)
    }
    private fun content(mime: String, value: String) = TerminalMimeClipboardContent(mime, value.encodeToByteArray())
    private fun b64(value: String) = Base64.Default.encode(value.encodeToByteArray())
    private fun osc(payload: String) = "\u001b]5522;$payload\u0007"
}
