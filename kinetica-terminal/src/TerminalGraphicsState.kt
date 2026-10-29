// Command execution and placement lifecycle follow pinned Ghostty kitty/graphics_{exec,storage}.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal data class TerminalGraphicsPlacementKey(val image: Long, val id: Long, val external: Boolean)

internal class TerminalGraphicsPlacement(
    val key: TerminalGraphicsPlacementKey,
    val image: TerminalGraphicsStoredImage,
    var geometry: TerminalGraphicsPlacementGeometry,
    val anchor: TerminalGraphicsAnchor?,
    val parent: TerminalGraphicsPlacementKey? = null,
    val horizontalOffset: Int = 0,
    val verticalOffset: Int = 0,
) {
    val virtual: Boolean get() = anchor == null && parent == null
}

/** One screen's command executor. Hosts enable it only when their view can present images. */
internal class TerminalGraphicsState(
    private val terminal: TerminalSession,
    private val changed: () -> Unit,
    private val placementLimit: Int = 16_384,
) {
    private val entries = mutableMapOf<TerminalGraphicsPlacementKey, TerminalGraphicsPlacement>()
    // Reverse parent edges make subtree deletion linear, even after ancestor replacement deepens a chain.
    private val children = mutableMapOf<TerminalGraphicsPlacementKey, MutableSet<TerminalGraphicsPlacementKey>>()
    private var nextInternalId = 0L
    val storage = TerminalGraphicsStorage(limit = 0, removed = { image ->
        entries.keys.filter { it.image == image.id }.forEach { remove(it) }
    })
    val placements: Collection<TerminalGraphicsPlacement> get() = entries.values
    var generation: Any = Any()
        private set

    init { require(placementLimit >= 0) }

    private fun mutated() { generation = Any(); changed() }

    fun setLimit(value: Int) {
        val before = storage.generation
        storage.setLimit(value)
        if (value == 0) nextInternalId = 0
        if (before !== storage.generation) mutated()
    }

    fun reset() {
        storage.clear()
        nextInternalId = 0
        mutated()
    }

    fun execute(command: TerminalGraphicsCommand) {
        if (!storage.enabled) return
        storage.conflictingIdentifiers(command)?.let {
            it.encode(command.quiet)?.let(terminal::sendInput)
            return
        }
        val before = storage.generation
        when (command.action) {
            'q' -> storage.query(command).reply()?.let(terminal::sendInput)
            't', 'T', 'f' -> {
                if (command.action == 'f' && !storage.hasPendingTransfer) {
                    unsupported(command)
                } else {
                    // Beginning replacement has uppercase ID-delete semantics, including orphan data.
                    if (!storage.hasPendingTransfer && command.imageId != 0L) deleteImage(command.imageId)
                    val result = storage.transmit(command)
                    val display = result.display
                    val image = result.image
                    val response = if (display != null && image != null) display(display, image) else result.response
                    if (image?.implicitId != true) response?.encode(result.quiet)?.let(terminal::sendInput)
                }
            }
            'p' -> display(command).encode(command.quiet)?.let(terminal::sendInput)
            'd' -> { storage.abortTransfer(); delete(command) }
            else -> unsupported(command)
        }
        if (before !== storage.generation) mutated()
    }

    private fun unsupported(command: TerminalGraphicsCommand) {
        TerminalGraphicsResponse(command.imageId, command.imageNumber, command.placementId,
            "ENOSYS: animation is not supported").encode(command.quiet)?.let(terminal::sendInput)
    }

    private fun display(command: TerminalGraphicsCommand, transmitted: TerminalGraphicsStoredImage? = null): TerminalGraphicsResponse {
        var response = TerminalGraphicsResponse(transmitted?.id ?: command.imageId, command.imageNumber, command.placementId)
        if (response.id == 0L && response.number == 0L) return response.copy(message = "EINVAL: image ID or number required")
        val parentId = command.unsigned('P')
        if (command.virtual && parentId != 0L) return response.copy(message = "EINVAL: virtual placement cannot refer to a parent")
        val image = transmitted ?: (if (command.imageId != 0L) storage.image(command.imageId) else storage.imageByNumber(command.imageNumber))
            ?: return response.copy(message = "ENOENT: image not found")
        response = response.copy(id = image.id)
        reapRetired()
        val externalKey = if (command.placementId > 0) TerminalGraphicsPlacementKey(image.id, command.placementId, true) else null
        var parent: TerminalGraphicsPlacementKey? = null
        if (parentId != 0L) {
            if (storage.image(parentId) == null) return response.copy(message = "ENOPARENT: parent image not found")
            val requested = command.unsigned('Q')
            parent = if (requested > 0L) TerminalGraphicsPlacementKey(parentId, requested, true).takeIf(entries::containsKey)
                else entries.keys.filter { it.image == parentId }.minWithOrNull(compareBy({ !it.external }, { it.id }))
            if (parent == null) return response.copy(message = "ENOPARENT: parent placement not found")
            if (parent == externalKey) return response.copy(message = "EINVAL: placement cannot be its own parent")
            var current = parent
            var depth = 1
            while (current != null) {
                if (current == externalKey) return response.copy(message = "ECYCLE: parent chain creates a cycle")
                val placement = entries[current] ?: return response.copy(message = "ENOENT: parent chain ancestor not found")
                current = placement.parent
                if (current != null && depth++ >= 8) return response.copy(message = "ETOODEEP: parent chain too deep")
            }
        }
        if (entries.size >= placementLimit && externalKey !in entries) return response.copy(message = "ENOMEM: out of memory")
        val key = externalKey ?: TerminalGraphicsPlacementKey(image.id, nextInternalId, false).also {
            nextInternalId = (nextInternalId + 1) and 0xffffffffL
        }
        val anchor = if (command.virtual || parent != null) null else terminal.graphicsAnchor()
        val geometry = TerminalGraphicsPlacementGeometry.from(command, terminal.cellSize)
        // Replacement retains descendants, which follow the same key's new origin.
        entries.remove(key)?.let { release(it) }
        entries[key] = TerminalGraphicsPlacement(key, image, geometry, anchor, parent, command.signed('H'), command.signed('V'))
        image.placementCount++
        parent?.let { children.getOrPut(it) { mutableSetOf() }.add(key) }
        mutated()
        if (anchor != null && command.moveCursor) terminal.advanceGraphicsCursor(geometry.resolve(image.pixels.width, image.pixels.height, terminal.cellSize).grid)
        return response
    }

    /** Resolve dynamically so descendants follow parent replacement, scrolling and reflow. */
    fun position(placement: TerminalGraphicsPlacement): TerminalPosition? {
        var current = placement
        var x = 0; var y = 0; var depth = 0
        while (current.parent != null) {
            if (++depth > 8) return null
            x = saturatedAdd(x, current.horizontalOffset)
            y = saturatedAdd(y, current.verticalOffset)
            current = entries[current.parent] ?: return null
        }
        val anchor = current.anchor ?: return null
        val origin = terminal.graphicsAnchorPosition(anchor) ?: return null
        return TerminalPosition(saturatedAdd(origin.x, x), saturatedAdd(origin.y, y), false)
    }

    private fun release(placement: TerminalGraphicsPlacement) {
        placement.anchor?.dispose()
        placement.image.placementCount--
        check(placement.image.placementCount >= 0)
        placement.parent?.let { key -> children[key]?.let { it.remove(placement.key); if (it.isEmpty()) children.remove(key) } }
    }

    private fun remove(key: TerminalGraphicsPlacementKey, deleteUnused: Boolean = false) {
        val pending = ArrayDeque<TerminalGraphicsPlacementKey>()
        pending.addLast(key)
        var removed = false
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            val placement = entries.remove(next) ?: continue
            children.remove(next)?.let { pending.addAll(it) }
            release(placement)
            if (deleteUnused && placement.image.placementCount == 0) storage.remove(placement.image.id)
            removed = true
        }
        if (removed) mutated()
    }

    fun reapRetired() {
        entries.values.filter { it.anchor != null && it.anchor.line == null }.map { it.key }.forEach { remove(it) }
    }

    private data class Rect(val left: Long, val top: Long, val right: Long, val bottom: Long) {
        fun contains(x: Long, y: Long): Boolean = x in left..right && y in top..bottom
    }

    private fun rect(placement: TerminalGraphicsPlacement): Rect? {
        val anchor = placement.anchor ?: return null
        val position = terminal.graphicsAnchorPosition(anchor) ?: return null
        val image = placement.image.pixels
        val grid = placement.geometry.resolve(image.width, image.height, terminal.cellSize).grid
        if (grid.width == 0L || grid.height == 0L) return null
        return Rect(position.x.toLong(), position.y.toLong(),
            minOf(terminal.columns.toLong() - 1, position.x + grid.width - 1),
            minOf(terminal.rows.toLong() - 1, position.y + grid.height - 1))
    }

    private fun visible(placement: TerminalGraphicsPlacement): Boolean {
        val position = placement.anchor?.let(terminal::graphicsAnchorPosition) ?: return false
        return position.y >= 0 || (rect(placement)?.bottom ?: -1) >= 0
    }

    fun clearScreen() {
        val before = storage.generation
        entries.values.filter(::visible).map { it.key }.forEach { remove(it, deleteUnused = true) }
        storage.removeUnused()
        if (before !== storage.generation) mutated()
    }

    private fun delete(command: TerminalGraphicsCommand) {
        val action = command.deleteAction.lowercaseChar()
        val deleteUnused = command.deleteAction.isUpperCase()
        if (action == 'i' || action == 'n' || action == 'f') {
            val id = if (action == 'n' || action == 'f' && command.imageId == 0L)
                storage.imageByNumber(command.imageNumber)?.id ?: return else command.imageId
            if (action == 'f') {
                // Base-image case; animated image frame deletion is added with animation execution.
                if (deleteUnused) deleteImage(id)
                return
            }
            val key = TerminalGraphicsPlacementKey(id, command.placementId, true)
            val matched = command.placementId == 0L || key in entries
            val selected = if (command.placementId == 0L) entries.keys.filter { it.image == id } else listOf(key)
            selected.forEach { remove(it, deleteUnused) }
            if (deleteUnused && matched && storage.image(id)?.placementCount == 0) storage.remove(id)
            return
        }
        val x = command.unsigned('x') - 1; val y = command.unsigned('y') - 1
        val selected = entries.values.filter { placement ->
            when (action) {
                'a' -> visible(placement)
                'r' -> command.unsigned('y') > 0 && placement.image.id in command.unsigned('x')..command.unsigned('y')
                'z' -> !placement.virtual && placement.geometry.z == command.signed('z')
                'x' -> x >= 0 && rect(placement)?.let { x in it.left..it.right } == true
                'y' -> y in 0 until terminal.rows.toLong() && rect(placement)?.let { y in it.top..it.bottom } == true
                'c' -> rect(placement)?.contains(terminal.cursorColumn.toLong(), terminal.cursorRow.toLong()) == true
                'p', 'q' -> x in 0 until terminal.columns.toLong() && y in 0 until terminal.rows.toLong() &&
                    (action != 'q' || placement.geometry.z == command.signed('z')) && rect(placement)?.contains(x, y) == true
                else -> false
            }
        }.map { it.key }
        selected.forEach { remove(it, deleteUnused) }
        if (action == 'r' && deleteUnused) storage.removeRange(command.unsigned('x'), command.unsigned('y'))
    }

    private fun deleteImage(id: Long) {
        entries.keys.filter { it.image == id }.forEach { remove(it, deleteUnused = true) }
        storage.remove(id)
    }

    /** IL/DL edit cells at stable grid coordinates in Ghostty; their image pins do not scroll. */
    fun preservePositions(): () -> Unit {
        val restores = entries.values.mapNotNull { placement ->
            val anchor = placement.anchor ?: return@mapNotNull null
            val position = terminal.graphicsAnchorPosition(anchor) ?: return@mapNotNull null
            if (position.y < 0) null else Triple(anchor, position.x, position.y)
        }
        return { for ((anchor, column, row) in restores) anchor.moveTo(terminal.screenLine(row), column) }
    }

    /** Ghostty's full-screen no-history erasure clamps removed pins to row zero, preserving x. */
    fun scrollWithoutHistory(count: Int): () -> Unit {
        val restores = entries.values.mapNotNull { placement ->
            val anchor = placement.anchor ?: return@mapNotNull null
            val position = terminal.graphicsAnchorPosition(anchor) ?: return@mapNotNull null
            if (position.y < 0) return@mapNotNull null
            Triple(anchor, position.x, (position.y - count).coerceAtLeast(0))
        }
        return { for ((anchor, column, row) in restores) anchor.moveTo(terminal.screenLine(row), column) }
    }

    /** Snapshot before row rotation; restore after it, including pins whose old rows were recycled. */
    fun scrollMargins(top: Int, bottom: Int, left: Int, right: Int, delta: Int): () -> Unit {
        if (entries.isEmpty() || top == 0 && bottom == terminal.rows - 1 && left == 0 && right == terminal.columns - 1) return {}
        val restores = mutableListOf<Triple<TerminalGraphicsAnchor, Int, Int>>()
        for (placement in entries.values.toList()) {
            val anchor = placement.anchor ?: continue
            val position = terminal.graphicsAnchorPosition(anchor) ?: continue
            if (position.y < 0) continue
            val image = placement.image.pixels
            val next = graphicsScrollWithinMargins(placement.geometry, image.width, image.height, terminal.cellSize,
                position.x, position.y, terminal.columns, top, bottom, left, right, delta)
            if (next == null) { remove(placement.key); continue }
            if (next.geometry != placement.geometry) { placement.geometry = next.geometry; mutated() }
            restores += Triple(anchor, position.x, next.row)
        }
        return {
            for ((anchor, column, row) in restores) anchor.moveTo(terminal.screenLine(row), column)
        }
    }

    private fun saturatedAdd(a: Int, b: Int): Int = (a.toLong() + b).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
