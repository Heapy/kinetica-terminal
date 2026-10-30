@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.UniformTypeIdentifiers.UTType

/** Reads only the drag's own pasteboard, after the user drops. File URLs are representations,
 * never permission to open a path or to fetch the underlying file's contents. */
internal class AppKitTerminalDnd(
    private val session: TerminalSession,
    private val view: NSView,
    private val position: (NSDraggingInfoProtocol, Int) -> TerminalDndPosition?,
    private val accepted: () -> Unit,
) : TerminalDisposable {
    private var disposed = false
    private var sequence: Long? = null
    private val binding = session.attachDndHost(TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    })
    private val registrationObservation = session.onDnd { if (it == TerminalDndEvent.REGISTRATION) refreshRegistration() }

    fun refreshRegistration() {
        if (disposed) return
        val types = linkedSetOf<String>(checkNotNull(NSPasteboardTypeFileURL))
        val state = session.kittyDnd.state
        if (state != null) {
            types += listOfNotNull(NSPasteboardTypeString, NSPasteboardTypeURL, NSPasteboardTypeHTML,
                NSPasteboardTypeRTF, NSPasteboardTypePNG, NSPasteboardTypeTIFF)
            // Registered MIME names add exotic native types; they do not filter the offers.
            val bytes = state.registered.snapshot()
            var start = 0
            for (end in 0..bytes.size) if (end == bytes.size || bytes[end] == 32.toByte()) {
                if (end > start) {
                    val mime = try { bytes.decodeToString(start, end, throwOnInvalidSequence = true) }
                        catch (_: CharacterCodingException) { "" }
                    if (terminalDndHostMimes(listOf(mime)) != null) {
                        types += if ('/' in mime) UTType.typeWithMIMEType(mime)?.identifier ?: mime else mime
                    }
                }
                start = end + 1
            }
        }
        // AppKit adds to the existing registration; remove types owned by the old client.
        view.unregisterDraggedTypes()
        view.registerForDraggedTypes(types.toList())
    }

    /** Null preserves the view's legacy file-path fallback for an unregistered client. */
    fun move(sender: NSDraggingInfoProtocol, entering: Boolean): NSDragOperation? {
        if (disposed) return NSDragOperationNone
        if (!binding.registered) return null
        val point = position(sender, operations(sender.draggingSourceOperationMask)) ?: return NSDragOperationNone
        val mimes = representations(sender.draggingPasteboard).map { it.mime }
        val fresh = entering || sequence != sender.draggingSequenceNumber
        sequence = sender.draggingSequenceNumber
        val result = if (fresh) binding.enter(point, mimes) else binding.move(point, mimes)
        return operation(result ?: 0)
    }
    fun prepare(sender: NSDraggingInfoProtocol): Boolean? {
        if (disposed) return false
        if (!binding.registered) return null
        val point = position(sender, operations(sender.draggingSourceOperationMask)) ?: return false
        return binding.operation(point.operations) != 0 && representations(sender.draggingPasteboard).isNotEmpty()
    }
    fun drop(sender: NSDraggingInfoProtocol): Boolean? {
        if (disposed) return false
        if (!binding.registered) return null
        val point = position(sender, operations(sender.draggingSourceOperationMask)) ?: return false
        if (binding.operation(point.operations) == 0) { cancel(); return false }
        val ticket = binding.ticket() ?: return false
        val window = view.window
        val board = sender.draggingPasteboard
        val version = board.changeCount
        val offered = representations(board)
        val data = read(board, offered)
        // A lazy provider may reenter the application and detach the view, replace the
        // pasteboard or unregister the client. Never publish a partial/stale captured drop.
        // Objective-C getters can return distinct Kotlin wrappers for the same NSWindow.
        if (!binding.valid(ticket) || view.window != window || position(sender, point.operations) == null) return false
        if (data == null || version != board.changeCount) { cancel(); return false }
        val result = binding.drop(ticket, point, data)
        sequence = null
        if (result) accepted() else binding.leave()
        return result
    }
    fun cancel() { sequence = null; binding.leave() }
    override fun dispose() {
        if (disposed) return
        disposed = true; sequence = null; registrationObservation.dispose(); binding.dispose(); view.unregisterDraggedTypes()
    }

    private data class Representation(val mime: String, val type: String?)
    private fun representations(board: NSPasteboard): List<Representation> {
        val native = board.types.orEmpty().filterIsInstance<String>()
        val result = linkedMapOf<String, Representation>()
        if (NSPasteboardTypeFileURL in native || NSPasteboardTypeString in native)
            result["text/plain"] = Representation("text/plain", null)
        if (NSPasteboardTypeFileURL in native || NSPasteboardTypeURL in native)
            result["text/uri-list"] = Representation("text/uri-list", null)
        for (type in native) {
            if (type == NSPasteboardTypeFileURL || type == NSPasteboardTypeURL || type == NSPasteboardTypeString) continue
            val mime = if ('/' in type) type else UTType.typeWithIdentifier(type)?.preferredMIMEType?.let {
                if (it == "text/plain;charset=utf-8") "text/plain" else it
            } ?: continue
            if (mime == "text/plain" || mime == "text/uri-list" || mime in result) continue
            if (terminalDndHostMimes(listOf(mime)) != null) result[mime] = Representation(mime, type)
            if (result.size >= 16) break
        }
        return result.values.take(16)
    }
    private fun read(board: NSPasteboard, offered: List<Representation>): List<TerminalDndItem>? {
        if (offered.isEmpty()) return null
        var remaining = TerminalDndHosts.MAX_DROP_BYTES
        val result = mutableListOf<TerminalDndItem>()
        for ((mime, type) in offered) {
            val bytes = if (mime == "text/plain") {
                var response: TerminalMimeClipboardRead? = null
                TerminalNativeMimeClipboard(board, readLimit = remaining).read(TerminalClipboardLocation.STANDARD,
                    listOf(mime), false) { response = it }
                val read = response ?: return null
                if (read.result != TerminalClipboardResult.SUCCESS) return null
                read.contents.singleOrNull()?.data ?: return null
            } else if (mime == "text/uri-list") uriList(board, remaining) ?: return null
            else {
                val data = board.dataForType(checkNotNull(type)) ?: return null
                if (data.length > remaining.toULong()) return null
                if (data.length == 0uL) byteArrayOf() else data.bytes!!.reinterpret<ByteVar>().readBytes(data.length.toInt())
            }
            if (bytes.size > remaining) return null
            remaining -= bytes.size; result += TerminalDndItem(mime.encodeToByteArray(), bytes)
        }
        return result
    }
    private fun uriList(board: NSPasteboard, limit: Int): ByteArray? {
        val text = StringBuilder()
        for (item in board.pasteboardItems.orEmpty().filterIsInstance<NSPasteboardItem>()) {
            val value = (item.propertyListForType(NSPasteboardTypeFileURL) as? String)
                ?: item.stringForType(NSPasteboardTypeURL) ?: continue
            val url = NSURL.URLWithString(value)?.absoluteString ?: return null
            if (url.length.toLong() + 2 > limit - text.length) return null
            text.append(url).append("\r\n")
        }
        if (text.isEmpty()) return null
        val bytes = text.toString().encodeToByteArray()
        return bytes.takeIf { it.size <= limit }
    }
    private fun operations(value: NSDragOperation): Int =
        (if (value and NSDragOperationCopy != 0uL) 1 else 0) or (if (value and NSDragOperationMove != 0uL) 2 else 0)
    private fun operation(value: Int): NSDragOperation = when (value) {
        1 -> NSDragOperationCopy; 2 -> NSDragOperationMove; else -> NSDragOperationNone
    }
}
