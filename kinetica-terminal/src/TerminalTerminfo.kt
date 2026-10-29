package io.heapy.kinetica.terminal

/** Ghostty's XTGETTCAP map uses uppercase hex keys and complete wire replies. */
internal fun terminalTerminfoReplies(request: String, name: String?): Sequence<String> = sequence {
    val nameBytes = name?.takeIf { it.length in 1..128 }?.encodeToByteArray()?.takeIf { it.size <= 128 }
    var start = 0
    while (start < request.length) {
        val end = request.indexOf(';', start).let { if (it < 0) request.length else it }
        // No current key is longer than this. Avoid copying giant malformed query fields.
        if (end - start <= 128) {
            val key = buildString(end - start) {
                for (i in start until end) append(request[i].let { if (it in 'a'..'z') it - 32 else it })
            }
            if (key == "544E") {
                if (nameBytes != null) yield("\u001bP1+r544E=${nameBytes.toHexString(HexFormat.UpperCase)}\u001b\\")
            } else terminalTerminfoCapabilities[key]?.let { yield(it) }
        }
        start = end + 1
    }
}
