// Animation semantics adapted from Ghostty kitty/graphics_{animation,exec,storage}.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal class TerminalGraphicsAnimationException(message: String) : Exception(message)

/** Per-image animation core. Storage owns admission/eviction; hosts supply the monotonic clock.
 * Every published pixel buffer is immutable, including buffers held by completed view frames. */
internal class TerminalGraphicsAnimation(root: TerminalGraphicsPixels,
    private val allocatePixels: (Int) -> ByteArray = { ByteArray(it) },
) {
    private class Frame(var pixels: TerminalGraphicsPixels, var gap: Long = 0)
    private val frames = mutableListOf(Frame(root))
    enum class State { STOPPED, LOADING, RUNNING }
    var initialized: Boolean = false
        private set
    var currentIndex: Int = 0
        private set
    var state: State = State.STOPPED
        private set
    var maxLoops: Long = 0
        private set
    var currentLoop: Long = 0
        private set
    var shownAt: Long? = null
        private set
    var mutation: Any = Any()
        private set
    // Mirrors Ghostty's image-generation invalidation for pending-transfer identity checks.
    // Renderers must retain currentPixels itself, not look it up again through mutable state.
    var contentGeneration: Any = Any()
        private set
    val rootPixels: TerminalGraphicsPixels get() = frames[0].pixels
    val currentPixels: TerminalGraphicsPixels get() = frames[currentIndex].pixels
    val frameCount: Int get() = frames.size
    val byteCount: Long get() = frames.sumOf { it.pixels.data.size.toLong() }
    val durationMillis: Long get() = frames.sumOf { it.gap }
    fun frame(number: Long): TerminalGraphicsPixels? = if (number in 1..frames.size.toLong()) frames[number.toInt() - 1].pixels else null
    fun gap(index: Int): Long = frames[index].gap

    private fun changed(content: Boolean = false) {
        mutation = Any()
        if (content) contentGeneration = Any()
    }
    private fun rgbaRoot() {
        if (rootPixels.format == 32) return
        frames[0].pixels = rootPixels.animationRgba(allocatePixels)
        changed(content = true)
    }
    private fun failure(message: String): Nothing = throw TerminalGraphicsAnimationException(message)
    private fun copyPixels(bytes: ByteArray): ByteArray = allocatePixels(bytes.size).also { bytes.copyInto(it) }

    /** Decoding/chunk identity checks happen before this call. Reserve only a new full frame.
     * A storage owner observes root conversion before deciding whether it can admit the frame. */
    fun load(command: TerminalGraphicsCommand, source: TerminalGraphicsPixels, reserveFrame: (Int) -> Boolean): Int {
        require(command.action == 'f')
        if (source.width > rootPixels.width || source.height > rootPixels.height) failure("EINVAL: frame dimensions exceed image")
        require(source.format == 24 || source.format == 32)
        rgbaRoot()
        initialized = true
        val requested = command.unsigned('r')
        val number = if (requested == 0L || requested > frames.size + 1L) frames.size + 1 else requested.toInt()
        val signedGap = command.signed('z')
        if (number == frames.size + 1) {
            val baseNumber = command.unsigned('c')
            val base = if (baseNumber == 0L) null else frame(baseNumber) ?: failure("EINVAL: base frame not found")
            val length = rootPixels.data.size
            if (!reserveFrame(length)) failure("ENOSPC: animation frame storage full")
            val canvas = base?.data?.let(::copyPixels) ?: animationBackground(length, command.unsigned('Y'), allocatePixels)
            animationCompose(canvas, rootPixels.width, rootPixels.height, source.data, source.width, source.height,
                command.unsigned('x'), command.unsigned('y'), sourceFormat = source.format, overwrite = command.unsigned('X') == 1L)
            frames += Frame(TerminalGraphicsPixels(rootPixels.width,rootPixels.height,32,canvas),
                when { signedGap > 0 -> signedGap.toLong(); signedGap < 0 -> 0; else -> 40 })
            changed()
        } else {
            val target = frames[number - 1]
            if (signedGap != 0) target.gap = signedGap.toLong().coerceAtLeast(0)
            val canvas = copyPixels(target.pixels.data)
            animationCompose(canvas, rootPixels.width, rootPixels.height, source.data, source.width, source.height,
                command.unsigned('x'), command.unsigned('y'), sourceFormat = source.format, overwrite = command.unsigned('X') == 1L)
            target.pixels = TerminalGraphicsPixels(rootPixels.width,rootPixels.height,32,canvas)
            val current = number - 1 == currentIndex
            if (current) shownAt = null
            changed(content = current)
        }
        return number
    }

    fun control(command: TerminalGraphicsCommand) {
        require(command.action == 'a')
        initialized = true
        val frame = command.unsigned('r'); val gap = command.signed('z')
        if (frame in 1..frames.size.toLong() && gap != 0) {
            frames[frame.toInt() - 1].gap = gap.toLong().coerceAtLeast(0); changed()
        }
        val current = command.unsigned('c')
        if (current in 1..frames.size.toLong() && current - 1 != currentIndex.toLong()) {
            currentIndex = current.toInt() - 1; shownAt = null; changed(content = true)
        }
        val action = command.unsigned('s')
        if (action in 1..3L) {
            val old = state
            state = State.entries[action.toInt() - 1]
            if (old == State.STOPPED && state != State.STOPPED) shownAt = null
            currentLoop = 0; changed()
        }
        val loops = command.unsigned('v')
        if (loops != 0L) { maxLoops = loops - 1; changed() }
    }

    fun compose(command: TerminalGraphicsCommand) {
        require(command.action == 'c')
        val sourceNumber = command.unsigned('r'); val destinationNumber = command.unsigned('c')
        if (frame(sourceNumber) == null) failure("ENOENT: source frame not found")
        if (frame(destinationNumber) == null) failure("ENOENT: destination frame not found")
        val width = command.unsigned('w').takeIf { it > 0 } ?: rootPixels.width.toLong()
        val height = command.unsigned('h').takeIf { it > 0 } ?: rootPixels.height.toLong()
        val dx = command.unsigned('x'); val dy = command.unsigned('y')
        val sx = command.unsigned('X'); val sy = command.unsigned('Y')
        if (dx + width > rootPixels.width || dy + height > rootPixels.height) failure("EINVAL: destination rectangle out of bounds")
        if (sx + width > rootPixels.width || sy + height > rootPixels.height) failure("EINVAL: source rectangle out of bounds")
        if (sourceNumber == destinationNumber && maxOf(sx,dx) < minOf(sx,dx) + width && maxOf(sy,dy) < minOf(sy,dy) + height)
            failure("EINVAL: source and destination rectangles overlap")
        rgbaRoot()
        val source = frame(sourceNumber)!!
        val destination = frames[destinationNumber.toInt() - 1]
        val canvas = copyPixels(destination.pixels.data)
        animationCompose(canvas,rootPixels.width,rootPixels.height,source.data,rootPixels.width,rootPixels.height,dx,dy,
            sourceX = sx.toInt(),sourceY = sy.toInt(),width = width.toInt(),height = height.toInt(),overwrite = command.unsigned('C') == 1L)
        destination.pixels = TerminalGraphicsPixels(rootPixels.width,rootPixels.height,32,canvas)
        changed(content = destinationNumber - 1 == currentIndex.toLong())
    }

    /** True asks the storage owner to delete the image and all placements. */
    fun delete(number: Long, uppercase: Boolean): Boolean {
        require(number in 0..0xffffffffL)
        if (frames.size == 1) return uppercase
        val selected = number.coerceIn(1,frames.size.toLong()).toInt()
        frames.removeAt(selected - 1)
        // Preserve the pinned implementation's index repair, including root promotion.
        val removedIndex = if (selected == 1) 0 else selected - 2
        if (currentIndex > frames.lastIndex) {
            currentIndex = frames.lastIndex; shownAt = null; changed(content = true)
        } else if (removedIndex == currentIndex) {
            shownAt = null; changed(content = true)
        } else {
            if (removedIndex < currentIndex) currentIndex--
            changed()
        }
        return false
    }

    /** One displayed-frame advance per tick; zero-gap frames are skipped without catch-up. */
    fun tick(now: Long, placed: Boolean): Long? {
        require(now >= 0)
        if (state == State.STOPPED || frames.size == 1 || !placed || durationMillis == 0L || maxLoops > 0 && currentLoop >= maxLoops) return null
        val at = shownAt?.takeIf { it <= now } ?: now
        shownAt = at
        fun deadline(time: Long, gap: Long) = if (gap > Long.MAX_VALUE - time) Long.MAX_VALUE else time + gap
        var nextAt = deadline(at,frames[currentIndex].gap)
        if (now >= nextAt) {
            var index = currentIndex
            while (true) {
                val next = (index + 1) % frames.size
                if (next == 0) {
                    if (state == State.LOADING) return null
                    currentLoop++
                    if (maxLoops > 0 && currentLoop >= maxLoops) return null
                }
                index = next
                if (frames[index].gap != 0L) break
            }
            currentIndex = index; shownAt = now; changed(content = true)
            nextAt = deadline(now,frames[index].gap)
        }
        return (nextAt - now).takeIf { it > 0 }
    }
}
