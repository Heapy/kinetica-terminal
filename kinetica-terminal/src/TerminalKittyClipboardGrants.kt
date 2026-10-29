// Adapted from Ghostty 1.4.0 terminal/kitty/clipboard_grants.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal enum class KittyClipboardDirection { READ, WRITE }

/** Session-scoped grants. Paste passwords must come from a cryptographically secure host source. */
internal class KittyClipboardGrants {
    private class Entry(val password: String, var oneTime: Boolean) {
        var read = false
        var write = false
    }
    private val entries = mutableListOf<Entry>()
    val size: Int get() = entries.size

    fun grant(password: String, direction: KittyClipboardDirection, oneTime: Boolean) {
        if (password.isEmpty() || password.length > 128 || password.encodeToByteArray().size > 128) return
        val entry = entries.firstOrNull { it.password == password }?.also { it.oneTime = it.oneTime && oneTime }
            ?: Entry(password, oneTime).also {
                if (entries.size == 32) entries.removeAt(0)
                entries.add(it)
            }
        when (direction) {
            KittyClipboardDirection.READ -> entry.read = true
            KittyClipboardDirection.WRITE -> entry.write = true
        }
    }

    /** Checking the wrong direction also consumes a one-time grant, matching Kitty/Ghostty. */
    fun use(password: String, direction: KittyClipboardDirection): Boolean {
        if (password.isEmpty()) return false
        val index = entries.indexOfFirst { it.password == password }
        if (index < 0) return false
        val entry = entries[index]
        val allowed = if (direction == KittyClipboardDirection.READ) entry.read else entry.write
        if (entry.oneTime) {
            // Original uses swapRemove here. Preserve its effect on later capacity eviction.
            val last = entries.removeAt(entries.lastIndex)
            if (index < entries.size) entries[index] = last
        }
        return allowed
    }

    fun clear() { entries.clear() }
}
