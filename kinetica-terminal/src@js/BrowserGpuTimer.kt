package io.heapy.kinetica.terminal


/** Nonblocking, bounded diagnostic queries. Never gl.finish() or a synchronous result read. */
internal class BrowserGpuTimer(private val gl: dynamic, private val observer: TerminalRenderObserver,
    private val scheduler: TerminalScheduler = TerminalScheduler { delay, action ->
        val window: dynamic = js("globalThis.window")
        val timer = window.setTimeout({ action() }, delay)
        TerminalDisposable { window.clearTimeout(timer) }
    }, private val now: () -> Double = { js("globalThis.performance.now()") as Double }) {
    private val extension: dynamic = gl.getExtension("EXT_disjoint_timer_query_webgl2")
    private val pending = mutableListOf<Query>()
    private var active: Query? = null
    private var timer: TerminalDisposable? = null
    private var stopped = false

    internal class Query(val id: Long, val handle: dynamic, val start: Double)

    fun begin(id: Long): Query? {
        check(active == null) { "A GPU timing interval is already active" }
        if (stopped || extension == null || pending.size >= 8) {
            observer.onGpuTime(id, null)
            return null
        }
        // Read/clear disjoint before starting a new interval; it invalidates outstanding ones.
        if (gl.getParameter(extension.GPU_DISJOINT_EXT) == true) clear(report = true, delete = true)
        val handle = gl.createQuery()
        if (handle == null) { observer.onGpuTime(id, null); return null }
        val query = Query(id, handle, now())
        gl.beginQuery(extension.TIME_ELAPSED_EXT, handle)
        active = query
        return query
    }

    fun end(query: Query?) {
        if (query == null) return
        check(active === query)
        gl.endQuery(extension.TIME_ELAPSED_EXT)
        active = null
        pending += query
        schedule()
    }

    private fun schedule() {
        if (timer != null || stopped || pending.isEmpty()) return
        timer = scheduler.schedule(16) { timer = null; poll() }
    }

    private fun poll() {
        if (stopped) return
        if (gl.isContextLost() == true) { contextLost(); return }
        if (gl.getParameter(extension.GPU_DISJOINT_EXT) == true) clear(report = true, delete = true)
        else {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val query = iterator.next()
                val available = gl.getQueryParameter(query.handle, gl.QUERY_RESULT_AVAILABLE) == true
                if (available || now() - query.start >= 5000) {
                    val duration = if (available) (gl.getQueryParameter(query.handle, gl.QUERY_RESULT) as Number).toDouble() / 1e6 else null
                    gl.deleteQuery(query.handle); iterator.remove()
                    observer.onGpuTime(query.id, duration?.takeIf { it.isFinite() && it >= 0 })
                }
            }
        }
        schedule()
    }

    private fun clear(report: Boolean, delete: Boolean) {
        timer?.dispose(); timer = null
        val abandoned = pending.toList(); pending.clear()
        // A renderer exception may dispose us between begin/end. End the active interval
        // before deleting its handle, even though it never entered the pending queue.
        active?.let { query ->
            active = null
            if (delete) { gl.endQuery(extension.TIME_ELAPSED_EXT); gl.deleteQuery(query.handle) }
            if (report) observer.onGpuTime(query.id, null)
        }
        for (query in abandoned) {
            if (delete) gl.deleteQuery(query.handle)
            if (report) observer.onGpuTime(query.id, null)
        }
    }

    fun contextLost() { stopped = true; clear(report = true, delete = false) }
    fun dispose() { stopped = true; clear(report = false, delete = gl.isContextLost() != true) }
}
