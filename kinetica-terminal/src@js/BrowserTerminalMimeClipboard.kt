package io.heapy.kinetica.terminal

/** Uses the browser's permission checks and native sanitization; custom formats use Web Custom Formats. */
internal class BrowserTerminalMimeClipboard(
    private val navigator: dynamic = js("globalThis.navigator"),
    private val itemConstructor: dynamic = js("globalThis.ClipboardItem"),
    private val readLimit: Int = 8 * 1024 * 1024,
    private val writeLimit: Int = 64 * 1024 * 1024,
) : TerminalMimeClipboard {
    private var disposed = false
    fun dispose() { disposed = true }

    override fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit) {
        if (disposed || location != TerminalClipboardLocation.STANDARD || navigator?.clipboard?.read == null) {
            complete(TerminalMimeClipboardRead(TerminalClipboardResult.UNSUPPORTED)); return
        }
        val operation: dynamic = try {
            navigator.clipboard.read().then({ items: dynamic -> readItems(items, mimeTypes, list) })
        } catch (failure: Throwable) {
            complete(TerminalMimeClipboardRead(result(failure))); return
        }
        operation.then({ value: TerminalMimeClipboardRead -> complete(value) },
            { failure: dynamic -> complete(TerminalMimeClipboardRead(result(failure))) })
    }

    private class Representation(val item: dynamic, val type: String)

    private fun readItems(items: dynamic, requested: List<String>, list: Boolean): dynamic {
        checkActive()
        val available = linkedMapOf<String, Representation>()
        for (index in 0 until (items.length as Int)) {
            val item = items[index]
            for (type in item.types.unsafeCast<Array<String>>()) {
                val mime = type.removePrefix("web ")
                val previous = available[mime]
                // Prefer native representations when both native and web-custom data advertise
                // the same MIME type; fetch only the first item providing that representation.
                if (previous == null || previous.type.startsWith("web ") && !type.startsWith("web "))
                    available[mime] = Representation(item, type)
            }
        }
        val contents = mutableListOf<TerminalMimeClipboardContent>()
        var remaining = readLimit
        var pending: dynamic = js("Promise.resolve()")
        for (mime in requested.distinct()) {
            val representation = available[mime] ?: continue
            pending = pending.then({ _: dynamic ->
                checkActive()
                representation.item.getType(representation.type).then({ blob: dynamic ->
                    checkActive()
                    if ((blob.size as Double) > remaining) throw ClipboardFailure(TerminalClipboardResult.IO_ERROR)
                    blob.arrayBuffer().then({ buffer: dynamic ->
                        checkActive()
                        val bytes = bufferBytes(buffer)
                        if (bytes.size > remaining) throw ClipboardFailure(TerminalClipboardResult.IO_ERROR)
                        remaining -= bytes.size
                        contents += TerminalMimeClipboardContent(mime, bytes)
                    })
                })
            })
        }
        return pending.then({ _: dynamic ->
            TerminalMimeClipboardRead(available = if (list) available.keys.toList() else emptyList(), contents = contents)
        })
    }

    override fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit) {
        if (disposed || location != TerminalClipboardLocation.STANDARD || navigator?.clipboard?.write == null || itemConstructor == null) {
            complete(TerminalClipboardResult.UNSUPPORTED); return
        }
        // Clipboard.write([]) is a successful no-op, not a clear. The browser has no async
        // clear operation; do not report DONE while retaining the previous owner's contents.
        if (contents.isEmpty()) { complete(TerminalClipboardResult.UNSUPPORTED); return }
        val operation: dynamic = try {
            val data: dynamic = js("Object.create(null)")
            val representations = mutableMapOf<String, ByteArray>()
            var remaining = writeLimit
            for ((mime, bytes) in contents) {
                val type = writableType(mime) ?: throw ClipboardFailure(TerminalClipboardResult.UNSUPPORTED)
                val previous = representations[type]
                if (previous != null) {
                    if (!previous.contentEquals(bytes)) throw ClipboardFailure(TerminalClipboardResult.INVALID_DATA)
                    continue
                }
                if (bytes.size > remaining) throw ClipboardFailure(TerminalClipboardResult.IO_ERROR)
                remaining -= bytes.size
                if (!type.startsWith("web ") && mime.startsWith("text/")) {
                    try { bytes.decodeToString(throwOnInvalidSequence = true) }
                    catch (_: CharacterCodingException) { throw ClipboardFailure(TerminalClipboardResult.INVALID_DATA) }
                }
                data[type] = contentBlob(bytes, mime)
                representations[type] = bytes
            }
            // One ClipboardItem carries every format. No permission request or clipboard write
            // happens until validation and construction of the complete item have succeeded.
            navigator.clipboard.write(arrayOf(newItem(itemConstructor, data)))
        } catch (failure: Throwable) {
            complete(result(failure)); return
        }
        operation.then({ _: dynamic -> complete(TerminalClipboardResult.SUCCESS) },
            { failure: dynamic -> complete(result(failure)) })
    }

    private fun writableType(mime: String): String? {
        if (itemConstructor.supports != null) {
            if (itemConstructor.supports(mime) as Boolean) return mime
            val custom = "web $mime"
            return if (itemConstructor.supports(custom) as Boolean) custom else null
        }
        // Older browsers have no supports(). Try custom formats through the normal API and
        // propagate rejection; never silently drop an unsupported representation.
        return if (mime in listOf("text/plain", "text/html", "image/png")) mime else "web $mime"
    }

    private class ClipboardFailure(val result: TerminalClipboardResult) : Exception()
    private fun checkActive() {
        if (disposed) throw ClipboardFailure(TerminalClipboardResult.BUSY)
    }
    private fun result(failure: dynamic): TerminalClipboardResult {
        if (failure is ClipboardFailure) return failure.result
        return when (failure?.name as? String) {
            "NotAllowedError", "SecurityError" -> TerminalClipboardResult.DENIED
            "NotSupportedError" -> TerminalClipboardResult.UNSUPPORTED
            "InvalidStateError" -> TerminalClipboardResult.BUSY
            "DataError", "TypeError" -> TerminalClipboardResult.INVALID_DATA
            else -> TerminalClipboardResult.IO_ERROR
        }
    }
}

private fun bufferBytes(buffer: dynamic): ByteArray = js("new Int8Array(buffer)")
private fun contentBlob(bytes: ByteArray, mime: String): dynamic = js("new Blob([bytes], {type: mime})")
private fun newItem(constructor: dynamic, data: dynamic): dynamic = js("new constructor(data)")
