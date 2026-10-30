package io.heapy.kinetica.terminal

/** Browser URLs never inherit the embedding page's base URL. Terminal paths need a host opener. */
internal fun openBrowserTerminalLink(value: String, permitted: () -> Boolean = { true }): Boolean {
    if (!permitted()) return true
    val window: dynamic = js("globalThis.window")
    fun denied(message: String): Boolean { window.alert(message); return true }
    if (value.isEmpty() || value != value.trim() || value.any {
        it.code in 0..31 || it.code in 127..159 || it == '\u061c' || it in '\u200b'..'\u200f' ||
            it in '\u2028'..'\u202e' || it in '\u2066'..'\u2069' || it == '\u2060' || it == '\ufeff'
    }) return denied("This terminal link contains invalid or invisible characters.")
    val parse: dynamic = js("(function(value) { try { return new URL(value); } catch (_) { return null; } })")
    val url = parse(value) ?: return denied("This terminal link is not an absolute URL. Local paths require a host application opener.")
    when (url.protocol as String) {
        "http:", "https:" -> if ((url.hostname as String).isEmpty()) return denied("This terminal link has no valid host.")
        "mailto:" -> if ((url.pathname as String).isEmpty()) return denied("This mail link has no address.")
        "javascript:", "vbscript:", "data:", "blob:", "file:", "about:" ->
            return denied("This kind of terminal link cannot be opened by the browser terminal.")
        else -> if (window.confirm("Open this link in another application?\n\n$value") != true) return true
    }
    if (!permitted()) return true
    window.open(url.href, "_blank", "noopener,noreferrer")
    return true
}
