package io.heapy.kinetica.terminal

import kotlin.time.TimeSource
import kotlin.time.DurationUnit

/**
 * Opt-in renderer diagnostics. Callbacks run on the UI thread and must be short, read-only,
 * and must not throw. IDs are local to one mounted surface, including renderer fallback.
 * Capture any input/visible-content marker in [onFrameStarted], not in an asynchronous GPU
 * callback: the session may have changed by then. No samples or GPU queries are retained when
 * no observer is installed. Disposal cancels pending callbacks.
 */
public interface TerminalRenderObserver {
    /** Called before drawing. A failed/unavailable drawable may have no submitted sample. */
    public fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {}
    /** CPU work has been submitted. This is not GPU completion or display presentation. */
    public fun onFrameSubmitted(frame: TerminalFrameMetrics) {}
    /** GPU backends only: duration, or null when unavailable, disjoint, failed or timed out. */
    public fun onGpuTime(frameId: Long, milliseconds: Double?) {}
    /**
     * Metal only: actual drawable presentation host time (seconds, same clock as
     * CACurrentMediaTime). Zero means dropped/unpresented. WebGL and software renderers
     * cannot report this; rAF and GPU query completion must not be substituted for it.
     */
    public fun onFramePresented(frameId: Long, hostTimeSeconds: Double) {}
}

/**
 * Renderer-only wall-clock CPU durations in milliseconds, excluding start/submission callbacks,
 * host event handling, widget sizing and accessibility updates.
 * Atlas includes rasterization/uploads; submission includes command encoding/commit.
 * Software rendering interleaves layout and paint: all its work is in [submitMillis].
 * GPU timings are delivered separately and are not additive to these CPU durations.
 */
public data class TerminalFrameMetrics(
    val frameId: Long,
    val backend: String,
    val acquireMillis: Double,
    val layoutMillis: Double,
    val atlasMillis: Double,
    val batchMillis: Double,
    val submitMillis: Double,
    val drawCalls: Int,
) {
    public val cpuMillis: Double get() = acquireMillis + layoutMillis + atlasMillis + batchMillis + submitMillis
}

internal class TerminalRenderProbe(val observer: TerminalRenderObserver?) {
    private var sequence = 0L
    fun begin(viewport: TerminalViewport, backend: String): TerminalFrameMeasurement? {
        val observer = observer ?: return null
        val id = ++sequence
        observer.onFrameStarted(id, viewport)
        return TerminalFrameMeasurement(id, backend, observer)
    }
}

internal class TerminalFrameMeasurement(val id: Long, private val backend: String, private val observer: TerminalRenderObserver) {
    private var mark = TimeSource.Monotonic.markNow()
    var acquire = 0.0
    var layout = 0.0
    var atlas = 0.0
    var batch = 0.0
    fun checkpoint(): Double {
        val now = TimeSource.Monotonic.markNow()
        val elapsed = (now - mark).toDouble(DurationUnit.MILLISECONDS)
        mark = now
        return elapsed
    }
    fun submitted(drawCalls: Int) {
        val submit = checkpoint()
        observer.onFrameSubmitted(TerminalFrameMetrics(id, backend, acquire, layout, atlas, batch, submit, drawCalls))
    }
}
