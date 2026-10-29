package io.heapy.kinetica.terminal

/** One replaceable desktop notification per surface; browser permissions remain authoritative. */
public class BrowserTerminalNotifications : TerminalNotificationDelivery {
    private val api: dynamic = js("globalThis.Notification")
    private val tag: String = "kinetica-terminal-${nextId++}"
    private var current: dynamic = null
    private var disposed = false
    override fun requestPermission(complete: (Boolean) -> Unit) {
        if (disposed || api == null) { complete(false); return }
        try {
            api.requestPermission().then({ value: String -> complete(!disposed && value == "granted") }, { _: dynamic -> complete(false) })
        } catch (_: Throwable) { complete(false) }
    }
    override fun show(notification: TerminalNotification) {
        if (disposed || api == null || api.permission != "granted") return
        clear()
        try { current = create(notification.title.ifEmpty { "Terminal" }, notification.body, tag) } catch (_: Throwable) { }
    }
    override fun clear() { current?.close(); current = null }
    override fun dispose() { disposed = true; clear() }
    private fun create(title: String, body: String, tag: String): dynamic = js("new globalThis.Notification(title, { body: body, tag: tag })")
    private companion object { var nextId = 0L }
}
