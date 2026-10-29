// Adapted from Ghostty 1.4.0 terminal/kitty/clipboard_response.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64

internal enum class KittyClipboardStatus { DATA, DONE, EBUSY, EFBIG, EINVAL, EIO, ENOSYS, EPERM, OK }

/** The caller supplies a sanitized request ID and validated metadata. */
internal data class KittyClipboardResponse(
    val operation: KittyClipboardOperation,
    val status: KittyClipboardStatus,
    val primary: Boolean = false,
    val id: String = "",
    val mime: String? = null,
    val password: String? = null,
    val payload: ByteArray = byteArrayOf(),
    val terminator: Int = 0,
) {
    fun encode(): String = buildString {
        append("\u001b]5522;type="); append(operation.name.lowercase()); append(":status="); append(status.name)
        if (primary) append(":loc=primary")
        if (id.isNotEmpty()) { append(":id="); append(id) }
        if (mime != null) { append(":mime="); append(Base64.encode(mime.encodeToByteArray())) }
        if (password != null) { append(":pw="); append(Base64.encode(password.encodeToByteArray())) }
        if (payload.isNotEmpty()) { append(';'); append(Base64.encode(payload)) }
        append(if (terminator == 7) "\u0007" else "\u001b\\")
    }
}

/** Lazy bounded DATA packets; the owner must preserve their ordering and apply backpressure. */
internal data class KittyClipboardReadSuccess(
    val primary: Boolean = false,
    val id: String = "",
    val password: String? = null,
    val list: Boolean = false,
    val available: List<String> = emptyList(),
    val contents: List<KittyClipboardContent> = emptyList(),
    val terminator: Int = 0,
) {
    fun packets(): Sequence<String> = sequence {
        fun packet(status: KittyClipboardStatus, mime: String? = null, payload: ByteArray = byteArrayOf()) =
            KittyClipboardResponse(KittyClipboardOperation.READ, status,
                primary = primary && status == KittyClipboardStatus.OK, id = id, mime = mime,
                password = password, payload = payload, terminator = terminator).encode()
        yield(packet(KittyClipboardStatus.OK))
        if (list) {
            val listing = ByteArray(4096)
            var size = 0
            if (available.isNotEmpty()) {
                for ((index, mime) in available.withIndex()) {
                    // Reject without encoding a host-supplied oversized MIME string first.
                    val separator = if (index > 0) 1 else 0
                    if (mime.length > listing.size - size - separator - 1) break
                    val bytes = mime.encodeToByteArray()
                    if (bytes.size > listing.size - size - separator - 1) break
                    if (index > 0) listing[size++] = 32
                    bytes.copyInto(listing, size); size += bytes.size
                }
                listing[size++] = 10
            }
            // Even an empty targets listing has a DATA packet, without a payload section.
            yield(packet(KittyClipboardStatus.DATA, ".", listing.copyOf(size)))
        }
        for (content in contents) {
            var offset = 0
            while (offset < content.data.size) {
                val end = offset + minOf(4096, content.data.size - offset)
                yield(packet(KittyClipboardStatus.DATA, content.mime, content.data.copyOfRange(offset, end)))
                offset = end
            }
        }
        yield(packet(KittyClipboardStatus.DONE))
    }
}
