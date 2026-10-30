package io.heapy.kinetica.terminal

/** A modal text preview. Clipboard content is assigned as text, never interpreted as markup. */
internal fun presentBrowserPaste(owner: dynamic, text: String, complete: (Boolean) -> Unit): TerminalDisposable {
    if (owner.isConnected != true) { complete(false); return TerminalDisposable {} }
    val document = owner.ownerDocument
    val previousFocus = document.activeElement
    val selectionStart = previousFocus?.selectionStart
    val selectionEnd = previousFocus?.selectionEnd
    val selectionDirection = previousFocus?.selectionDirection
    val dialog = document.createElement("dialog")
    dialog.setAttribute("aria-label", "Paste into Terminal?")
    dialog.style.cssText = "padding:20px;border:1px solid #888;border-radius:8px;max-width:calc(100vw - 48px);color-scheme:light dark;font:14px system-ui"
    val title = document.createElement("h2")
    title.textContent = "Paste into Terminal?"
    title.style.cssText = "font-size:18px;margin:0 0 12px"
    val explanation = document.createElement("p")
    explanation.textContent = "This text may execute commands when pasted into the terminal."
    val preview = document.createElement("textarea")
    preview.setAttribute("aria-label", "Clipboard text")
    preview.readOnly = true; preview.value = text
    preview.style.cssText = "display:block;box-sizing:border-box;width:480px;max-width:100%;height:160px;margin:12px 0;font:13px monospace"
    val cancel = document.createElement("button")
    cancel.textContent = "Cancel"; cancel.type = "button"
    val paste = document.createElement("button")
    paste.textContent = "Paste"; paste.type = "button"; paste.style.marginLeft = "8px"
    dialog.append(title, explanation, preview, cancel, paste)
    var active = true
    fun dismiss() {
        if (!active) return
        active = false
        val restoreFocus = dialog.contains(document.activeElement) == true
        dialog.oncancel = null; dialog.onclose = null; cancel.onclick = null; paste.onclick = null
        dialog.close(); dialog.remove()
        if (restoreFocus && owner.isConnected == true && previousFocus?.isConnected == true) {
            previousFocus.focus(js("({preventScroll:true})"))
            // Chromium restores activeElement on close without restoring the editable caret.
            // Without this, key events arrive but the next printable character produces no input.
            if (selectionStart != null && selectionEnd != null && previousFocus.setSelectionRange != null) {
                previousFocus.setSelectionRange(selectionStart, selectionEnd, selectionDirection ?: "none")
            }
        }
    }
    fun finish(confirmed: Boolean) {
        if (!active) return
        dismiss(); complete(confirmed)
    }
    cancel.onclick = { _: dynamic -> finish(false) }
    paste.onclick = { _: dynamic -> finish(true) }
    dialog.oncancel = { event: dynamic -> event.preventDefault(); finish(false) }
    dialog.onclose = { _: dynamic -> finish(false) }
    document.body.appendChild(dialog)
    dialog.showModal(); cancel.focus()
    return TerminalDisposable { dismiss() }
}
