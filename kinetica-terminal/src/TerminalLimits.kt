package io.heapy.kinetica.terminal

/**
 * Retained-storage policy, measured in accounting bytes, not VM heap usage or process RSS.
 * Rows charge packed cells, optional reference arrays, metadata and UTF-16 string storage.
 * Shared links are charged once per row; snapshots and other rows charge them independently.
 * The active screen is never discarded to meet the history budget.
 */
public data class TerminalLimits(
    public val historyBytes: Long = 32L * 1024 * 1024,
    public val hyperlinkBytesPerLine: Int = 64 * 1024,
) {
    init {
        require(historyBytes >= 0) { "History storage budget must be nonnegative" }
        require(hyperlinkBytesPerLine >= 0) { "Hyperlink storage budget must be nonnegative" }
    }
}
