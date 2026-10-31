// Focus-loss key release ordering adapted from Ghostty Surface.zig (1.4.0).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Ghostty retains the last unconsumed key event, not a stack of held keys. */
internal class TerminalKeyFocus {
    private var last: TerminalKey? = null
    private var identity: String? = null

    fun record(event: TerminalKey) {
        val current = event.physicalCode ?: event.keypad?.name ?: event.key
        val released = event.action == TerminalKeyAction.RELEASE
        if (released && last == null) return
        val nextIdentity = if (released && current == identity) null else current
        identity = nextIdentity
        // Upstream clears the borrowed UTF-8 buffer before retaining an event. A release
        // therefore has no associated text or shifted alternate derived from that text.
        last = event.copy(text = "").takeUnless {
            nextIdentity == null && !it.shift && !it.control && !it.alt && !it.meta && !it.capsLock && !it.numLock
        }
    }

    fun clear() { last = null; identity = null }

    fun release(send: (TerminalKey) -> Unit) {
        var event = last?.copy(action = TerminalKeyAction.RELEASE) ?: return
        val original = identity
        clear()
        if (original != null) send(event)
        for (modifier in listOf("Shift", "Control", "Alt", "Meta")) {
            val held = when (modifier) {
                "Shift" -> event.shift; "Control" -> event.control; "Alt" -> event.alt; else -> event.meta
            }
            if (!held) continue
            event = when (modifier) {
                "Shift" -> event.copy(shift = false); "Control" -> event.copy(control = false)
                "Alt" -> event.copy(alt = false); else -> event.copy(meta = false)
            }
            for (side in listOf("Right", "Left")) {
                val key = modifier + side
                // Layout/base identities are derived from the new physical key in Ghostty.
                // They must not remain attached to the character/keypad key being released.
                if (key != original) send(event.copy(key = key, physicalCode = key, keypad = null,
                    unshiftedCodepoint = null, baseCodepoint = null))
            }
        }
    }
}
