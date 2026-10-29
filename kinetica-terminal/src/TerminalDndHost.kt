package io.heapy.kinetica.terminal

/** A view owns its drag callbacks; the application continues to own the session. */
internal class TerminalDndHostBinding internal constructor(
    private val hosts: TerminalDndHosts,
    internal val changed: () -> Unit,
) : TerminalDisposable {
    internal var disposed = false
    private var generation = 0L
    internal fun invalidate() { generation++ }
    val registered: Boolean get() = !disposed && hosts.state != null

    /** A delayed OS data read may finish only in the registration and gesture that started it. */
    internal class Ticket(val owner: TerminalDndHostBinding, val state: TerminalKittyDnd.State, val generation: Long)
    fun ticket(): Ticket? = if (disposed) null else hosts.state?.let { Ticket(this, it, generation) }
    fun valid(ticket: Ticket): Boolean = !disposed && ticket.owner === this && ticket.state === hosts.state && ticket.generation == generation

    /** Null delegates to the host's ordinary path/text drop behavior. Zero rejects the drag. */
    fun enter(position: TerminalDndPosition, mimes: List<String>): Int? {
        generation++
        return move(position, mimes)
    }
    fun move(position: TerminalDndPosition, mimes: List<String>): Int? {
        if (!registered) return null
        val types = terminalDndHostMimes(mimes) ?: run { leave(); return 0 }
        if (!hosts.offer(this, position, types, null)) return 0
        return operation(position.operations)
    }
    fun operation(allowed: Int): Int {
        if (!registered) return 0
        val accepted = if (hosts.active === this && hosts.state?.hovered == true) hosts.state?.clientAccepted else null
        return when (accepted) {
            0 -> 0
            1, 2 -> if (allowed and accepted != 0) accepted else 0
            else -> when { allowed and 1 != 0 -> 1; allowed and 2 != 0 -> 2; else -> 0 }
        }
    }

    /** Capture representations only for a real user drop. Copies are admitted before ownership. */
    fun drop(ticket: Ticket, position: TerminalDndPosition, items: List<TerminalDndItem>): Boolean {
        if (!valid(ticket) || operation(position.operations) == 0) return false
        val selected = items.take(16)
        if (selected.isEmpty() || selected.any { !terminalDndHostMime(it.mime) }) return false
        if (!hosts.offer(this, position, selected.map { it.mime }, selected)) return false
        generation++ // A duplicate or delayed callback cannot submit the same user drop twice.
        return true
    }
    fun leave() {
        generation++
        if (!disposed) hosts.leave(this)
    }
    override fun dispose() {
        if (disposed) return
        disposed = true; generation++
        hosts.remove(this)
    }
}

/** Shared by all views of one session. Queues at most four host actions and 16 MiB of drop data. */
internal class TerminalDndHosts(
    private val session: TerminalSession,
    private val scheduler: TerminalScheduler,
) {
    private class Action(
        val owner: TerminalDndHostBinding,
        val state: TerminalKittyDnd.State,
        val position: TerminalDndPosition?,
        val mimes: List<ByteArray>,
        val items: List<TerminalDndItem>?,
    ) {
        val bytes: Int = items?.sumOf { it.data.size } ?: 0
    }
    private val bindings = mutableListOf<TerminalDndHostBinding>()
    private val queue = ArrayDeque<Action>()
    private var queuedBytes = 0
    private var job: TerminalDisposable? = null
    private var draining = false
    private var stopped = false
    private var cleanup: Pair<TerminalKittyDnd.State, TerminalDndResult>? = null
    internal var active: TerminalDndHostBinding? = null
        private set
    internal val state: TerminalKittyDnd.State? get() = session.kittyDnd.state
    private val ready = session.onOutputReady { schedule() }
    private val events = session.onDnd { event ->
        if (event == TerminalDndEvent.REGISTRATION) {
            discardInvalid()
            if (state == null) active = null
        }
        bindings.toList().forEach { if (!it.disposed) it.changed() }
        schedule()
    }

    fun attach(changed: () -> Unit): TerminalDndHostBinding {
        check(!stopped)
        return TerminalDndHostBinding(this, changed).also { bindings.add(it) }
    }

    internal fun offer(owner: TerminalDndHostBinding, position: TerminalDndPosition, mimes: List<ByteArray>, items: List<TerminalDndItem>?): Boolean {
        val current = state ?: return false
        if (owner.disposed || stopped) return false
        discardInvalid()
        val mimeBytes = mimes.sumOf { it.size.toLong() + 1 }
        val dataBytes = items?.sumOf { it.data.size.toLong() } ?: 0L
        if (mimeBytes > TerminalKittyDnd.MIME_LIMIT || dataBytes > MAX_DROP_BYTES - queuedBytes) return false
        // Keep a completed drop in order. Only an unsent pointer move may be replaced.
        val tail = queue.lastOrNull()
        val replace = tail?.owner === owner && tail.state === current && tail.items == null && tail.position != null
        if (!replace && queue.size == MAX_ACTIONS) return false
        val copies = items?.map { TerminalDndItem(it.mime.copyOf(), it.data.copyOf()) }
        val types = copies?.map { it.mime } ?: mimes.map { it.copyOf() }
        if (replace) queue.removeLast()
        val action = Action(owner, current, position, types, copies)
        queue.addLast(action); queuedBytes += action.bytes
        drain()
        return true
    }

    internal fun leave(owner: TerminalDndHostBinding) {
        val current = state ?: return
        discardInvalid()
        val tail = queue.lastOrNull()
        if (tail?.owner === owner && tail.items != null) return // The OS session ended at drop time.
        // Leaving discards only this view's unsent moves; accepted drops keep their order.
        removeWhere { it.owner === owner && it.items == null }
        if (active !== owner) return
        if (queue.size < MAX_ACTIONS) queue.addLast(Action(owner, current, null, emptyList(), null))
        else {
            // A leave cannot be lost behind other views' full queues.
            cleanup = current to session.kittyDnd.leave()
            if (!current.dropped) active = null
        }
        drain()
    }

    internal fun remove(owner: TerminalDndHostBinding) {
        bindings.remove(owner)
        removeWhere { it.owner === owner }
        if (active === owner) {
            state?.let { current ->
                cleanup = current to session.kittyDnd.leave()
                current.resetDrop()
            }
            active = null
        }
        drain()
    }

    /** Transport closure/failure cancels gestures and their delayed data readers, not registration. */
    internal fun cancelPending() {
        job?.dispose(); job = null
        queue.clear(); queuedBytes = 0; cleanup = null
        state?.resetDrop(); active = null
        bindings.toList().forEach { it.invalidate(); if (!it.disposed) it.changed() }
        stopIfUnused()
    }

    private fun removeWhere(predicate: (Action) -> Boolean) {
        val kept = queue.filterNot(predicate)
        queue.clear(); queue.addAll(kept)
        queuedBytes = queue.sumOf { it.bytes }
    }
    private fun discardInvalid() {
        removeWhere { it.owner.disposed || it.state !== state }
        if (cleanup?.first !== state) cleanup = null
    }
    private fun schedule() {
        if (stopped || draining || job != null || session.outputPaused) return
        if (queue.isEmpty() && cleanup == null) { stopIfUnused(); return }
        job = scheduler.schedule(0) { job = null; drain() }
    }
    private fun drain() {
        if (stopped || draining) return
        draining = true
        try {
            discardInvalid()
            while (!session.outputPaused) {
                val leaving = cleanup
                if (leaving != null) {
                    cleanup = null
                    session.acceptDndResult(leaving.second)
                    continue
                }
                val next = queue.removeFirstOrNull() ?: break
                queuedBytes -= next.bytes
                if (next.owner.disposed || next.state !== state) continue
                val position = next.position
                if (position == null) {
                    if (active === next.owner) {
                        val result = session.kittyDnd.leave()
                        if (state?.dropped != true) active = null
                        session.acceptDndResult(result)
                    }
                } else {
                    val before = if (active !== next.owner) session.kittyDnd.leave().packets else emptySequence()
                    active = next.owner
                    val result = if (next.items == null) session.kittyDnd.move(position, next.mimes)
                        else session.kittyDnd.drop(position, next.items) ?: continue
                    session.acceptDndResult(result.copy(packets = before + result.packets))
                }
            }
        } finally { draining = false }
        stopIfUnused()
    }
    private fun stopIfUnused() {
        if (bindings.isNotEmpty() || queue.isNotEmpty() || cleanup != null || stopped) return
        stopped = true; job?.dispose(); job = null
        ready.dispose(); events.dispose()
        if (session.dndHosts === this) session.dndHosts = null
    }
    companion object {
        const val MAX_DROP_BYTES = 16 * 1024 * 1024
        const val MAX_ACTIONS = 4
    }
}

/** Hosts curate at most sixteen representations. MIME tokens must be safe in plain OSC data. */
private fun terminalDndHostMime(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.all { (it.toInt() and 255) > 32 && it != 127.toByte() }
internal fun terminalDndHostMimes(values: List<String>): List<ByteArray>? {
    val result = values.take(16).map { it.encodeToByteArray() }
    return result.takeIf { it.isNotEmpty() && it.all(::terminalDndHostMime) && it.sumOf { mime -> mime.size.toLong() + 1 } <= TerminalKittyDnd.MIME_LIMIT }
}
