package io.heapy.kinetica.terminal

/** DOM drag data is readable only during drop. Copy strings then, and retain immutable Files
 * for bounded, abortable reads. No clipboard API or local filesystem paths are consulted. */
internal class BrowserTerminalDnd(
    private val session: TerminalSession,
    private val root: dynamic,
    private val position: (dynamic, Int) -> TerminalDndPosition?,
    private val accepted: () -> Unit,
) : TerminalDisposable {
    private val window: dynamic = js("globalThis.window")
    private var disposed = false
    private var inside = false
    private class Source(val mime: String, val bytes: ByteArray?, val file: dynamic, val size: Int)
    private class Read(val ticket: TerminalDndHostBinding.Ticket, val position: TerminalDndPosition, val sources: List<Source>) {
        val items = mutableListOf<TerminalDndItem>()
        var index = 0
        var reader: dynamic = null
    }
    private var reading: Read? = null
    private val binding: TerminalDndHostBinding = session.attachDndHost(TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    }) {
        val pending = reading
        if (!bindingRegistered() || pending != null && !bindingValid(pending.ticket)) cancel()
    }
    private fun bindingRegistered(): Boolean = binding.registered
    private fun bindingValid(ticket: TerminalDndHostBinding.Ticket): Boolean = binding.valid(ticket)
    private val listeners = mutableListOf<Pair<String, (dynamic) -> Unit>>()
    private val dragEnd: (dynamic) -> Unit = { event -> if (event.isTrusted == true && inside) cancel() }

    init {
        listen("dragenter", ::over)
        listen("dragover", ::over)
        listen("dragleave") { event ->
            if (event.isTrusted == true && inside && (event.relatedTarget == null || root.contains(event.relatedTarget) != true)) cancel()
        }
        listen("drop", ::drop)
        window.addEventListener("dragend", dragEnd, false)
    }
    private fun listen(type: String, action: (dynamic) -> Unit) {
        root.addEventListener(type, action, false); listeners += type to action
    }
    private fun over(event: dynamic) {
        if (disposed || event.isTrusted != true || root.isConnected != true) return
        val transfer = event.dataTransfer ?: return
        val point = position(event, operations(transfer.effectAllowed as? String)) ?: return
        val types = types(transfer)
        if (reading != null) cancel() // A new gesture supersedes an unfinished old file read.
        val operation = if (inside) binding.move(point, types) else binding.enter(point, types)
        inside = true
        // Unregistered terminals accept plain text through ordinary paste. Files have no shell
        // path in browsers, so decline them rather than navigating the terminal page away.
        val chosen = operation ?: if ("text/plain" in types && point.operations and 1 != 0) 1 else 0
        transfer.dropEffect = effect(chosen)
        event.preventDefault(); event.stopPropagation()
    }
    private fun drop(event: dynamic) {
        if (disposed || event.isTrusted != true || root.isConnected != true) return
        val transfer = event.dataTransfer ?: return
        val point = position(event, operations(transfer.effectAllowed as? String)) ?: return
        event.preventDefault(); event.stopPropagation()
        inside = false
        if (!binding.registered) {
            if (point.operations and 1 == 0) return
            val text = transfer.getData("text/plain") as? String ?: return
            if (text.isNotEmpty() && text.length <= TerminalDndHosts.MAX_DROP_BYTES &&
                text.encodeToByteArray().size <= TerminalDndHosts.MAX_DROP_BYTES) {
                session.paste(text); accepted()
            }
            return
        }
        val operation = binding.operation(point.operations)
        transfer.dropEffect = effect(operation)
        if (operation == 0 || reading != null) { cancel(); return }
        val ticket = binding.ticket() ?: return
        val sources = capture(transfer) ?: run { cancel(); return }
        if (sources.isEmpty()) { cancel(); return }
        val read = Read(ticket, point, sources)
        reading = read
        readNext(read)
    }
    private fun types(transfer: dynamic): List<String> {
        val result = mutableListOf<String>()
        val items = transfer.items
        if (items != null) {
            for (i in 0 until minOf(items.length as Int, 16)) {
                val item = items[i]
                if (item.kind == "string") result += (item.type as? String).orEmpty()
                else if (item.kind == "file") result += (item.type as? String).orEmpty().ifEmpty { "application/octet-stream" }
            }
        } else {
            for (i in 0 until minOf(transfer.types.length as Int, 16)) {
                val mime = transfer.types[i] as String
                if (mime != "Files") result += mime
            }
        }
        return result
    }
    private fun capture(transfer: dynamic): List<Source>? {
        val result = mutableListOf<Source>()
        var remaining = TerminalDndHosts.MAX_DROP_BYTES
        val items = transfer.items
        val count = minOf((items?.length ?: transfer.types.length) as Int, 16)
        for (i in 0 until count) {
            val item = if (items != null) items[i] else null
            val file = if (item?.kind == "file") item.getAsFile() else null
            val mime = if (item != null) (item.type as? String).orEmpty().ifEmpty { "application/octet-stream" }
                else transfer.types[i] as String
            if (mime == "Files") continue
            if (terminalDndHostMimes(listOf(mime)) == null) return null
            if (item?.kind == "file") {
                if (file == null) return null // Directories/unreadable entries are not an empty file.
                val size = (file.size as Number).toDouble()
                if (!size.isFinite() || size < 0 || size > remaining) return null
                remaining -= size.toInt(); result += Source(mime, null, file, size.toInt())
            } else {
                val text = transfer.getData(mime) as? String ?: return null
                if (text.length > remaining) return null
                val bytes = text.encodeToByteArray()
                if (bytes.size > remaining) return null
                remaining -= bytes.size; result += Source(mime, bytes, null, bytes.size)
            }
        }
        return result
    }
    private fun current(read: Read): Boolean = !disposed && root.isConnected == true && reading === read && binding.valid(read.ticket)
    private fun readNext(read: Read) {
        if (!current(read)) { if (reading === read) cancel(); return }
        while (read.index < read.sources.size) {
            val source = read.sources[read.index]
            val bytes = source.bytes
            if (bytes != null) {
                read.items += TerminalDndItem(source.mime.encodeToByteArray(), bytes); read.index++; continue
            }
            val reader: dynamic = js("new FileReader()")
            read.reader = reader
            reader.onload = { _: dynamic ->
                if (current(read) && read.reader === reader) {
                    val data = dndBufferBytes(reader.result)
                    clearReader(read, abort = false)
                    if (data.size != source.size) cancel()
                    else {
                        read.items += TerminalDndItem(source.mime.encodeToByteArray(), data)
                        read.index++; readNext(read)
                    }
                }
            }
            reader.onerror = { _: dynamic -> if (reading === read) cancel() }
            reader.onabort = { _: dynamic -> if (reading === read) cancel() }
            try { reader.readAsArrayBuffer(source.file) } catch (_: Throwable) { cancel() }
            return
        }
        reading = null
        if (binding.drop(read.ticket, read.position, read.items)) accepted() else binding.leave()
    }
    private fun clearReader(read: Read, abort: Boolean) {
        val reader = read.reader ?: return
        read.reader = null
        reader.onload = null; reader.onerror = null; reader.onabort = null
        if (abort && reader.readyState == 1) reader.abort()
    }
    fun cancel() {
        inside = false
        val read = reading; reading = null
        if (read != null) clearReader(read, abort = true)
        binding.leave()
    }
    override fun dispose() {
        if (disposed) return
        disposed = true; cancel(); binding.dispose()
        listeners.forEach { (type, action) -> root.removeEventListener(type, action, false) }; listeners.clear()
        window.removeEventListener("dragend", dragEnd, false)
    }
    private fun operations(value: String?): Int = when (value) {
        "copy", "copyLink" -> 1
        "move", "linkMove" -> 2
        "copyMove", "all", "uninitialized", null -> 3
        else -> 0
    }
    private fun effect(operation: Int): String = when (operation) { 1 -> "copy"; 2 -> "move"; else -> "none" }
}

private fun dndBufferBytes(buffer: dynamic): ByteArray = js("new Int8Array(buffer)")
