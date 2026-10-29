@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.UniformTypeIdentifiers.UTType

/** MIME mapping and file representations follow the pinned Ghostty macOS clipboard adapter. */
internal class TerminalNativeMimeClipboard(
    private val pasteboard: NSPasteboard = NSPasteboard.generalPasteboard,
    private val readLimit: Int = 8 * 1024 * 1024,
    private val writeLimit: Int = 64 * 1024 * 1024,
) : TerminalMimeClipboard {
    override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
        if (location != TerminalClipboardLocation.STANDARD) {
            complete(TerminalMimeClipboardRead(TerminalClipboardResult.UNSUPPORTED)); return
        }
        val version = pasteboard.changeCount
        val available = if (list) availableMimes() else emptyList()
        val contents = mutableListOf<TerminalMimeClipboardContent>()
        var remaining = readLimit
        for (mime in mimeTypes.distinct()) {
            val bytes = when (mime) {
                "text/plain", "text/uri-list" -> {
                    val text = try { textRepresentation(mime, remaining) } catch (_: ReadLimitExceeded) {
                        complete(TerminalMimeClipboardRead(TerminalClipboardResult.IO_ERROR)); return
                    }
                    if (text == null) continue
                    if (text.length > remaining) {
                        complete(TerminalMimeClipboardRead(TerminalClipboardResult.IO_ERROR)); return
                    }
                    text.encodeToByteArray()
                }
                else -> {
                    val data = pasteboard.dataForType(pasteboardType(mime)) ?: continue
                    if (data.length > remaining.toULong()) {
                        complete(TerminalMimeClipboardRead(TerminalClipboardResult.IO_ERROR)); return
                    }
                    if (data.length == 0uL) byteArrayOf() else data.bytes!!.reinterpret<ByteVar>().readBytes(data.length.toInt())
                }
            }
            if (bytes.size > remaining) {
                complete(TerminalMimeClipboardRead(TerminalClipboardResult.IO_ERROR)); return
            }
            remaining -= bytes.size
            contents += TerminalMimeClipboardContent(mime, bytes)
        }
        complete(if (version != pasteboard.changeCount) TerminalMimeClipboardRead(TerminalClipboardResult.BUSY)
            else TerminalMimeClipboardRead(available = available, contents = contents))
    }

    override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
        if (location != TerminalClipboardLocation.STANDARD) { complete(TerminalClipboardResult.UNSUPPORTED); return }
        // Stage the whole item before claiming the pasteboard. Validation never reads or clears
        // the previous owner's clipboard, including when the policy only permits writes.
        val item = NSPasteboardItem()
        val representations = mutableMapOf<String, ByteArray>()
        var remaining = writeLimit
        for ((mime, bytes) in contents) {
            val type = pasteboardType(mime)
            val previous = representations[type]
            if (previous != null) {
                if (!previous.contentEquals(bytes)) { complete(TerminalClipboardResult.INVALID_DATA); return }
                continue
            }
            if (bytes.size > remaining) { complete(TerminalClipboardResult.IO_ERROR); return }
            remaining -= bytes.size
            if (type == NSPasteboardTypeString) {
                try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: CharacterCodingException) { complete(TerminalClipboardResult.INVALID_DATA); return }
            }
            val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
                NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
            }
            if (!item.setData(data, type)) { complete(TerminalClipboardResult.INVALID_DATA); return }
            representations[type] = bytes
        }
        // AppKit has no atomic replace API: an OS failure after clearContents may leave an
        // empty clipboard. Publish one prepared item so it cannot contain a partial format set.
        pasteboard.clearContents()
        complete(if (contents.isEmpty() || pasteboard.writeObjects(listOf(item))) TerminalClipboardResult.SUCCESS
            else TerminalClipboardResult.IO_ERROR)
    }

    private fun availableMimes(): List<String> {
        val types = pasteboard.types.orEmpty().filterIsInstance<String>()
        fun mime(type: String): String? = UTType.typeWithIdentifier(type)?.preferredMIMEType?.let {
            if (it == "text/plain;charset=utf-8") "text/plain" else it
        }
        val result = linkedSetOf<String>()
        val files = NSPasteboardTypeFileURL in types
        if (files || types.any { mime(it) == "text/plain" }) result += "text/plain"
        if (files) result += "text/uri-list"
        for (type in types) mime(type)?.let { result += it }
        return result.toList()
    }

    private fun textRepresentation(mime: String, limit: Int): String? {
        val result = StringBuilder()
        var found = false
        for (item in pasteboard.pasteboardItems.orEmpty().filterIsInstance<NSPasteboardItem>()) {
            val url = (item.propertyListForType(NSPasteboardTypeFileURL) as? String)
                ?.let { NSURL.URLWithString(it) }?.takeIf { it.fileURL }
            val text = if (mime == "text/uri-list") url?.absoluteString?.plus("\r\n")
                else url?.path?.let { path ->
                    if (path.length > limit) throw ReadLimitExceeded()
                    escapePath(path)
                } ?: item.stringForType(NSPasteboardTypeString)
            if (text == null) continue
            if (mime == "text/plain" && found) result.append(' ')
            if (text.length > limit - result.length) throw ReadLimitExceeded()
            result.append(text)
            found = true
        }
        return if (found) result.toString() else null
    }

    private class ReadLimitExceeded : Exception()

    private fun pasteboardType(mime: String): String = if (mime == "text/plain") checkNotNull(NSPasteboardTypeString)
        else UTType.typeWithMIMEType(mime)?.identifier ?: mime

    private fun escapePath(path: String): String = buildString {
        for (char in path) {
            if (char in "\\ ()[]{}<>\"'`!#${'$'}&;|*?\t") append('\\')
            append(char)
        }
    }
}
