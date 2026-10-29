package io.heapy.kinetica.terminal

/** XTWINOPS text-area size request. Zero keeps that dimension; values are raw protocol units.
 * The host chooses whether to allow it and constrains the resulting window to its layout limits.
 * Receiving a request alone never changes the terminal grid. */
public data class TerminalWindowResizeRequest(public val rows: Int, public val columns: Int) {
    init { require(rows in 0..65535 && columns in 0..65535) }
}

/** Requested text-area extent in logical pixels (CSS pixels or AppKit points).
 * A null dimension leaves its actual host extent unchanged, including fractional cell space. */
public data class TerminalWindowSize(public val width: Double?, public val height: Double?)

internal fun TerminalWindowResizeRequest.windowSize(
    currentColumns: Int, currentRows: Int, cellWidth: Double, cellHeight: Double,
): TerminalWindowSize {
    // Ghostty's window minimum is 40 x 10 cells. Keep the engine's independent grid limits.
    val width = if (columns == 0) currentColumns else columns.coerceIn(40, 4096)
        .coerceAtMost(1_000_000 / if (rows == 0) currentRows else 10)
    val height = if (rows == 0) currentRows else rows.coerceIn(10, 4096).coerceAtMost(1_000_000 / width)
    return TerminalWindowSize(if (columns == 0) null else kotlin.math.ceil(width * cellWidth),
        if (rows == 0) null else kotlin.math.ceil(height * cellHeight))
}

/** One deferred layout per turn, merging nonzero axes so width-only and height-only requests
 * compose. No callback may outlive its host or move a replacement window/container. */
internal class TerminalWindowResizeBinding(
    session: TerminalSession,
    private val scheduler: TerminalScheduler,
    private val context: () -> Any?,
    private val allowed: (TerminalWindowResizeRequest) -> Boolean,
    private val apply: (TerminalWindowResizeRequest) -> Unit,
) : TerminalDisposable {
    private var disposed = false
    private var generation = 0L
    private var pending: TerminalWindowResizeRequest? = null
    private var owner: Any? = null
    private var timer: TerminalDisposable? = null
    private val observation = session.onWindowResizeRequested { request ->
        val target = context()
        if (!disposed && target != null && allowed(request) && !disposed &&
            (request.rows != 0 || request.columns != 0)) {
            if (owner != target) cancelPending()
            owner = target
            pending = TerminalWindowResizeRequest(request.rows.takeIf { it != 0 } ?: pending?.rows ?: 0,
                request.columns.takeIf { it != 0 } ?: pending?.columns ?: 0)
            if (timer == null) {
                val expected = ++generation
                timer = scheduler.schedule(16) {
                    if (!disposed && generation == expected) {
                        val size = pending
                        val host = owner
                        timer = null; pending = null; owner = null
                        if (size != null && context() == host && allowed(size) && !disposed &&
                            generation == expected && context() == host) apply(size)
                    }
                }
            }
        }
    }
    fun cancelPending() {
        generation++; timer?.dispose(); timer = null; pending = null; owner = null
    }
    override fun dispose() {
        disposed = true; observation.dispose(); cancelPending()
    }
}
