package io.heapy.kinetica.terminal

/** Releases a terminal subscription or scheduled callback on its owning thread. */
public fun interface TerminalDisposable {
    public fun dispose()
}
