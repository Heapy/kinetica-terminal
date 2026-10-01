package io.heapy.kinetica.terminal


/** Browser timers for the common bounded byte stream; the embedding owns WebSocket framing. */
public fun BrowserTerminalConnection(
    session: TerminalSession,
    transport: TerminalByteTransport,
    onInputRejected: () -> Unit,
    onFailure: (Throwable) -> Unit,
    limits: TerminalStreamLimits = TerminalStreamLimits(),
): TerminalStreamConnection {
    val window: dynamic = js("globalThis.window")
    return TerminalStreamConnection(session, transport, TerminalScheduler { delay, action ->
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    }, onInputRejected, onFailure, limits)
}
