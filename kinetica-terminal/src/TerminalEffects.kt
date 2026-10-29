// OSC 9/777 semantics adapted from Ghostty's osc9.zig and rxvt_extension.zig (MIT),
// commit 4da7523faba68ccb4042ea20585817098a51c015. See third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

public data class TerminalNotification(public val title: String, public val body: String)

/** Surface-owned desktop delivery. Ask permission from a host user action, never from terminal output. */
public interface TerminalNotificationDelivery : TerminalDisposable {
    public fun requestPermission(complete: (Boolean) -> Unit)
    public fun show(notification: TerminalNotification)
    public fun clear()
}

public enum class TerminalProgressState { REMOVE, SET, ERROR, INDETERMINATE, PAUSE }

/** Literal protocol report. An omitted percentage is distinct from zero. */
public data class TerminalProgressReport(public val state: TerminalProgressState, public val percentage: Int? = null) {
    init { require(percentage == null || percentage in 0..100) }
}

internal fun parseTerminalEffect(command: Int, payload: String, notify: (TerminalNotification) -> Unit, progress: (TerminalProgressReport) -> Unit) {
    val bytes = payload.encodeToByteArray().size
    if (bytes > 2048) return
    if (command == 777) {
        if (bytes >= 2048 || !payload.startsWith("notify;")) return
        val separator = payload.indexOf(';', 7)
        if (separator < 0) return
        notify(TerminalNotification(payload.substring(7, separator), payload.substring(separator + 1)))
        return
    }
    if (payload.startsWith("4;") && payload.length >= 3 && payload[2] in '0'..'4') {
        val state = TerminalProgressState.entries[payload[2] - '0']
        var percentage: Int? = if (state == TerminalProgressState.SET) 0 else null
        if (state in listOf(TerminalProgressState.SET, TerminalProgressState.ERROR, TerminalProgressState.PAUSE) && payload.getOrNull(3) == ';') {
            val number = payload.substring(4)
            percentage = if (number.isNotEmpty() && number.all { it in '0'..'9' })
                number.toULongOrNull()?.coerceAtMost(100u)?.toInt() else null
        }
        progress(TerminalProgressReport(state, percentage))
        return
    }
    // Recognized ConEmu commands must not accidentally become desktop notifications.
    // They are separate protocols (including command execution, which is not a host action here).
    val conemu = payload.startsWith("1;") || payload.startsWith("2;") || payload.startsWith("3;") ||
        payload.startsWith('5') || payload.startsWith("6;") || payload.startsWith("7;") ||
        payload.startsWith("8;") || payload.startsWith("9;") || payload.startsWith("11;") || payload.startsWith("12") ||
        payload == "10" || payload.startsWith("10;") && payload.getOrNull(3) in '0'..'3'
    if (!conemu && bytes < 2048) notify(TerminalNotification("", payload))
}

/** Surface progress state and the same 15-second stale-report timeout used by Ghostty. */
internal class TerminalProgressPresentation(session: TerminalSession, private val scheduler: TerminalScheduler, private val changed: () -> Unit) : TerminalDisposable {
    var report: TerminalProgressReport? = null
        private set
    val percentage: Int? get() = report?.percentage ?: if (report?.state == TerminalProgressState.PAUSE) 100 else null
    val label: String get() = when (report?.state) {
        TerminalProgressState.ERROR -> "Operation failed"
        TerminalProgressState.PAUSE -> "Operation paused"
        else -> "Operation in progress"
    }
    private var timer: TerminalDisposable? = null
    private var disposed = false
    private var revision = 0L
    private val observation = session.onProgress { value ->
        if (disposed) return@onProgress
        timer?.dispose(); timer = null
        val expected = ++revision
        report = value.takeIf { it.state != TerminalProgressState.REMOVE }
        if (report != null) timer = scheduler.schedule(15_000) {
            if (!disposed && revision == expected) { report = null; timer = null; changed() }
        }
        changed()
    }
    override fun dispose() { disposed = true; revision++; observation.dispose(); timer?.dispose(); timer = null; report = null }
}
