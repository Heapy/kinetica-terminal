package io.heapy.kinetica.terminal


/** One-shot callbacks on the session's owning thread, never inline. Disposal cancels the callback. */
public fun interface TerminalScheduler {
    public fun schedule(delayMillis: Int, action: () -> Unit): TerminalDisposable
}

internal const val TERMINAL_SYNC_TIMEOUT_MILLIS = 1000
internal const val TERMINAL_CURSOR_BLINK_MILLIS = 500
