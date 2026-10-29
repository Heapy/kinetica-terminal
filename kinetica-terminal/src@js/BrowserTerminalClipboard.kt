package io.heapy.kinetica.terminal

/** Browser clipboard calls retain browser permission/secure-context restrictions. */
internal class BrowserTerminalClipboard : TerminalClipboard {
    private val navigator: dynamic = js("globalThis.navigator")

    override fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit) {
        val clipboard = navigator.clipboard
        if (location != TerminalClipboardLocation.STANDARD || clipboard?.readText == null) { complete(null); return }
        clipboard.readText().then({ text: String -> complete(text.encodeToByteArray()) }, { _: dynamic -> complete(null) })
    }

    override fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit) {
        val clipboard = navigator.clipboard
        if (location != TerminalClipboardLocation.STANDARD || clipboard?.writeText == null) { complete(); return }
        val text = try { data.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { complete(); return }
        clipboard.writeText(text).then({ _: dynamic -> complete() }, { _: dynamic -> complete() })
    }
}
