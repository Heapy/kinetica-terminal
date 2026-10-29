package io.heapy.kinetica.terminal

/** One host surface's contribution to a session's visibility. Confined to the session thread. */
public interface TerminalVisibilityBinding : TerminalDisposable {
    /** True means potentially visible; false means hidden, detached or fully occluded. */
    public fun setVisible(visible: Boolean)
}
