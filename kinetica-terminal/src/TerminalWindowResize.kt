package io.heapy.kinetica.terminal

/** XTWINOPS text-area size request. Zero keeps that dimension; values are raw protocol units.
 * The host chooses whether to allow it and constrains the resulting window to its layout limits.
 * Receiving a request alone never changes the terminal grid. */
public data class TerminalWindowResizeRequest(public val rows: Int, public val columns: Int) {
    init { require(rows in 0..65535 && columns in 0..65535) }
}
