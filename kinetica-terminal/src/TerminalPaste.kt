// Paste encoding/safety adapted from Ghostty input/paste.zig and Surface.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Host clipboard-paste settings, matching Ghostty's defaults. Does not affect explicit
 * [TerminalSession.paste], typing, drag-and-drop, or mode-5522 clipboard events. */
public data class TerminalPastePolicy(
    public val protection: Boolean = true,
    public val bracketedSafe: Boolean = true,
) {
    public fun requiresConfirmation(text: String, bracketed: Boolean): Boolean = protection &&
        ("\u001b[201~" in text || (!(bracketed && bracketedSafe) && text.any { it == '\r' || it == '\n' }))
}

/** Show the original clipboard text and complete on the view's thread. The returned handle
 * must dismiss the prompt when replaced or disposed; late/duplicate replies are ignored. */
public fun interface TerminalPasteConfirmation {
    public fun confirm(text: String, complete: (Boolean) -> Unit): TerminalDisposable
}

/** One pending user paste per view. Encoding uses the current terminal mode on acceptance. */
internal class TerminalClipboardPaste(
    private val session: TerminalSession,
    private val available: () -> Boolean,
    private val present: (String, (Boolean) -> Unit) -> TerminalDisposable,
    private val accepted: () -> Unit,
) : TerminalDisposable {
    private class Pending(var text: String, var dialog: TerminalDisposable? = null)
    private var pending: Pending? = null
    private var disposed = false

    fun cancel() {
        val previous = pending ?: return
        pending = null
        previous.text = ""
        val dialog = previous.dialog; previous.dialog = null
        dialog?.dispose()
    }

    /** Acquire text under the same cancellation identity as confirmation. */
    fun read(policy: TerminalPastePolicy, onAccepted: () -> Unit = accepted, acquire: ((String?) -> Unit) -> Unit) {
        cancel()
        if (disposed || !available()) return
        val request = Pending("")
        pending = request
        try {
            acquire { text ->
                if (pending === request) {
                    pending = null
                    if (text != null) paste(text, policy, onAccepted)
                }
            }
        } catch (error: Exception) {
            if (pending === request) cancel()
            throw error
        }
    }

    fun paste(text: String, policy: TerminalPastePolicy, onAccepted: () -> Unit = accepted) {
        cancel()
        if (disposed || !available() || text.isEmpty()) return
        if (!policy.requiresConfirmation(text, session.bracketedPaste)) {
            onAccepted(); session.paste(text)
            return
        }
        val request = Pending(text)
        pending = request
        val dialog = try {
            present(text) { confirmed ->
                if (pending === request) {
                    val contents = request.text
                    cancel()
                    if (confirmed && !disposed && available()) {
                        onAccepted(); session.paste(contents)
                    }
                }
            }
        } catch (error: Exception) {
            if (pending === request) cancel()
            throw error
        }
        if (pending === request) request.dialog = dialog else dialog.dispose()
    }

    override fun dispose() { disposed = true; cancel() }
}

internal fun terminalEncodePaste(text: String, bracketed: Boolean): String = buildString(text.length) {
    if (bracketed) append("\u001b[200~")
    for (char in text) append(when (char.code) {
        // xterm's fixed control-byte replacement set, also used by Ghostty for DnD.
        0x00, 0x08, 0x05, 0x04, 0x1b, 0x7f,
        0x03, 0x1c, 0x15, 0x1a, 0x11, 0x13, 0x17, 0x16, 0x12, 0x0f -> ' '
        0x0a -> if (bracketed) '\n' else '\r'
        else -> char
    })
    if (bracketed) append("\u001b[201~")
}
