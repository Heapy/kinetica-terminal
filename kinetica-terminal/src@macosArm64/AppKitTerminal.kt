@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.AppKit.NSCompositingOperation.NSCompositingOperationSourceOver
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.QuartzCore.*
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.posix.gethostname
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

// Option translation and side mapping follow Ghostty's AppKit runtime (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors; see third-party/GHOSTTY-LICENSE.
/** Which macOS Option keys act as Alt instead of participating in text layout. */
public enum class AppKitTerminalOptionAsAlt { NONE, BOTH, LEFT, RIGHT }

private fun AppKitTerminalOptionAsAlt.applies(flags: ULong): Boolean {
    // IOLLEvent.h NX_DEVICERALTKEYMASK. Ghostty.Input.swift uses the same mapping:
    // both keys collapse to right; aggregate Option without a side bit defaults to left.
    val right = flags and 0x40uL != 0uL
    return when (this) {
        AppKitTerminalOptionAsAlt.NONE -> false
        AppKitTerminalOptionAsAlt.BOTH -> true
        AppKitTerminalOptionAsAlt.LEFT -> !right
        AppKitTerminalOptionAsAlt.RIGHT -> right
    }
}

/** A single custom-drawn native view; no NSTextField or NSView is allocated for terminal cells. */
public class AppKitTerminalView(
    public val session: TerminalSession,
    private val sessionId: String = "terminal",
    optionAsMeta: Boolean = false,
    private val rendering: TerminalRendering = TerminalRendering.AUTO,
    renderObserver: TerminalRenderObserver? = null,
    clipboardPolicy: TerminalClipboardPolicy = TerminalClipboardPolicy.DENY,
    clipboard: TerminalClipboard? = null,
    notificationPolicy: (TerminalNotification) -> Boolean = { false },
    notifications: TerminalNotificationDelivery? = null,
    graphicsStorageBytes: Int = 64 * 1024 * 1024,
    mimeClipboardPolicy: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy.DENY,
    mimeClipboard: TerminalMimeClipboard? = null,
    windowResizePolicy: (TerminalWindowResizeRequest) -> Boolean = { false },
    private val optionAsAlt: AppKitTerminalOptionAsAlt =
        if (optionAsMeta) AppKitTerminalOptionAsAlt.BOTH else AppKitTerminalOptionAsAlt.NONE,
) : NSView(NSMakeRect(0.0, 0.0, 800.0, 480.0)), NSTextInputClientProtocol {
    private var fontSize = 14.0
    private var fontFamily: String? = null
    private var font = terminalNativeFont(fontFamily, fontSize)
    public val currentFontSize: Double get() = fontSize
    public val currentFontFamily: String? get() = fontFamily
    private var cellWidth = 8.0
    private var cellHeight = 19.0
    private var disposed = false
    public var pastePolicy: TerminalPastePolicy = TerminalPastePolicy()
    public var copyTrimTrailingSpaces: Boolean = true
    public var copyFormat: TerminalCopyFormat = TerminalCopyFormat.MIXED
    public var selectionClearOnCopy: Boolean = false
    public var selectionClearOnTyping: Boolean = true
    /** Zero uses the macOS double-click interval; positive values are milliseconds. */
    public var clickRepeatIntervalMillis: Long = 0
        set(value) { require(value in 0..0xffff_ffffL); field = value }
    public var rightClickAction: TerminalRightClickAction = TerminalRightClickAction.CONTEXT_MENU
    public var middleClickAction: TerminalMiddleClickAction = TerminalMiddleClickAction.PRIMARY_PASTE
    /** Enable only when the custom MIME backend serves the distinct SELECTION clipboard. */
    public var selectionClipboardSupported: Boolean = false
    public var copyOnSelect: TerminalCopyOnSelect = TerminalCopyOnSelect.NONE
    public var copyCodepointMap: List<TerminalClipboardCodepointMap> = emptyList()
        set(value) { field = value.toList() }
    public var pasteConfirmation: TerminalPasteConfirmation = TerminalPasteConfirmation { text, complete ->
        presentNativePaste(window, text, complete)
    }
    internal var readPasteText: () -> String? = { NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString) }
    private val clipboardPaste = TerminalClipboardPaste(session, { !disposed && window != null },
        { text, complete -> pasteConfirmation.confirm(text, complete) },
        { viewport.followOutput(); viewport.clearSelection() })
    private var notifications = notifications
    private fun notificationDelivery(): TerminalNotificationDelivery = notifications ?: AppKitTerminalNotifications().also { notifications = it }
    public fun requestNotificationPermission(complete: (Boolean) -> Unit) {
        if (disposed) complete(false) else notificationDelivery().requestPermission(complete)
    }
    private val notificationObservation = session.onNotification { if (!disposed && notificationPolicy(it)) notificationDelivery().show(it) }
    private val clipboardBinding = session.attachClipboard(clipboard ?: TerminalNativeClipboard(), clipboardPolicy)
    private val copyClipboard = mimeClipboard ?: TerminalNativeMimeClipboard()
    private val mimeClipboardBinding = session.attachMimeClipboard(copyClipboard, mimeClipboardPolicy)
    private var protocolPointerCursor: NSCursor = nativePointerCursor(session.pointerShape) ?: NSCursor.IBeamCursor
    internal var pointerCursor: NSCursor = nativePointerCursor(session.pointerShape) ?: NSCursor.IBeamCursor
        private set
    private val pointerObservation = session.onPointerShape { shape ->
        nativePointerCursor(shape)?.let { protocolPointerCursor = it; refreshLinkHover() }
    }
    private val linkPolicyObservation = session.onLinkPolicyChanged { refreshLinkHover() }
    private val pressedKeys = mutableMapOf<UShort, TerminalKey>()
    private val keyFocus = TerminalKeyFocus()
    private var terminalFocused = false
    private var focusWindowObservation: NSObjectProtocol? = null
    private class KeyInterpretation {
        val text = mutableListOf<String>()
        var command = false
    }
    private var keyInterpretation: KeyInterpretation? = null
    public var onViewportChanged: (() -> Unit)? = null
    private var accessibilitySelectionKey: Pair<Int, TerminalSelectionRange>? = null
    private var accessibilitySelectionTimer: NSTimer? = null
    internal var notifyAccessibilitySelectionChanged: () -> Unit = {
        NSAccessibilityPostNotification(this, NSAccessibilitySelectedTextChangedNotification)
    }
    internal var onScrollActivity: (() -> Unit)? = null
    internal var launchLink: (NSURL) -> Unit = { NSWorkspace.sharedWorkspace.openURL(it) }
    internal var presentLink: (NativeLinkDecision, String, (Boolean) -> Unit) -> TerminalDisposable =
        { decision, display, complete -> presentNativeLinkDecision(window, decision, display, complete) }
    private val links = NativeTerminalLinkOpener(TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    }, { decision, display, complete -> presentLink(decision, display, complete) },
        { launchLink(it) }, { !disposed && window != null })
    private val defaultOpenLink: (String) -> Boolean = { value -> links.open(value) { session.osc8LinksEnabled } }
    /** Override only with a host policy suitable for untrusted terminal-output targets. */
    public var openLink: (String) -> Boolean = defaultOpenLink
    public var linkPreviewMode: TerminalLinkPreviewMode = TerminalLinkPreviewMode.ALL
        set(value) { field = value; refreshLinkHover() }
    internal val linkPreviewLabel = TerminalNativeLinkPreview()
    private var hoverPoint: Pair<Double, Double>? = null
    private var hoverModifiers = TerminalLinkModifiers()
    private var refreshingLinkHover = false
    private var hoverWindowObservation: NSObjectProtocol? = null
    public val scrollOffset: Int get() = viewport.scrollOffset
    public val scrollbackLines: Int get() = session.historySize
    private var ready = false
    private var liveResizing = false
    private var pendingGridSize: Pair<Int, Int>? = null
    private var marked = ""
    private var markedSelection = NSMakeRange(0u, 0u)
    private var selecting = false
    private var forceClickLookupFired = false
    private val lookupLayout = NSLayoutManager()
    internal var forceClickLookupEnabled: () -> Boolean = {
        NSUserDefaults.standardUserDefaults.boolForKey("com.apple.trackpad.forceClick")
    }
    internal var showLookup: (NSAttributedString, CValue<NSPoint>) -> Unit = { text, point ->
        showDefinitionForAttributedString(text, atPoint = point)
    }
    private val mouseReporter = TerminalMouseReporter(session)
    private val localMouseButtons = mutableSetOf<Int>()
    private val verticalScroll = TerminalScrollAccumulator()
    private val horizontalScroll = TerminalScrollAccumulator()
    private var mouseTrackingArea: NSTrackingArea? = null
    private val colors = mutableMapOf<Int, NSColor>()
    private val attributes = mutableMapOf<Pair<Int, Int>, Map<Any?, *>>()
    private val viewport = TerminalViewport(session, ::invalidateRows, TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    })
    private var visibilityObservation: NSObjectProtocol? = null
    private val visibilityBinding = session.attachVisibility(false)
    private var visibilityRefreshQueued = false
    private var graphicsVisible = false
    private val resizeHoldObservation = session.onRenderHold { held ->
        if (!held && ready && !disposed) applyPendingGridSize()
    }
    private val sizeRefreshObservation = session.onSizeRefreshRequested {
        if (ready && !disposed) measure(force = true)
    }
    private val dragAndDrop = AppKitTerminalDnd(session, this, { sender, operations ->
        if (!ready || disposed || window == null || sender.draggingDestinationWindow != window || hiddenOrHasHiddenAncestor) null else
            convertPoint(sender.draggingLocation, fromView = null).useContents {
                val scale = window?.backingScaleFactor ?: 1.0
                TerminalDndPosition(floor(x / cellWidth).toInt().coerceIn(0, session.columns - 1).toLong(),
                    floor(y / cellHeight).toInt().coerceIn(0, session.rows - 1).toLong(),
                    floor(x * scale).toInt(), floor(y * scale).toInt(), operations)
            }
    }) { viewport.followOutput(); viewport.clearSelection(); window?.makeFirstResponder(this) }
    private val windowResizeBinding = TerminalWindowResizeBinding(session, TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    }, { if (ready && !disposed && !hiddenOrHasHiddenAncestor) window?.takeIf {
        it.visible && !it.miniaturized && it.styleMask and NSWindowStyleMaskFullScreen == 0uL
    } else null }, windowResizePolicy, ::resizeWindow)
    private var gpu: MetalTerminalDrawing? = null
    internal var acquireMetalDrawable: (CAMetalLayer) -> CAMetalDrawableProtocol? = { it.nextDrawable() }
    // Keep the custom Metal display delegate off NSView itself. Overriding displayLayer:
    // on the view also intercepts AppKit's software backing-layer display and skips drawRect.
    private val metalDisplayDelegate = object : NSObject(), CALayerDelegateProtocol {
        override fun displayLayer(layer: CALayer) { if (!disposed && gpu != null) updateLayer() }
    }
    private val softwareImages = AppKitTerminalImages()
    private var softwareGlyphs: AppKitTerminalGlyphs? = null
    private fun clearSoftwareGlyphs() { softwareGlyphs?.dispose(); softwareGlyphs = null }
    internal val imageCacheBytes: Long get() = gpu?.imageCacheBytes ?: softwareImages.bytes
    internal val imageTileCount: Int get() = gpu?.imageTileCount ?: softwareImages.tileCount
    internal val imageUploads: Int get() = gpu?.imageUploads ?: softwareImages.uploads
    private val renderProbe = TerminalRenderProbe(renderObserver)
    internal val progressIndicator = TerminalNativeProgress(session)
    public val renderingBackend: String get() = if (gpu != null) "metal" else "appkit"
    internal val submittedGpuFrames: Int get() = gpu?.submittedFrames ?: 0
    public var renderingFailure: String? = null
        private set

    init {
        viewport.setVisible(false)
        session.setGraphicsStorageLimit(graphicsStorageBytes)
        setAccessibilityElement(true)
        setAccessibilityRole("AXTextArea")
        setAccessibilityLabel("Terminal")
        dragAndDrop.refreshRegistration()
        setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationHorizontal)
        setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationVertical)
        setContentCompressionResistancePriority(1f, NSUserInterfaceLayoutOrientationVertical)
        if (rendering != TerminalRendering.SOFTWARE) {
            try {
                val renderer = MetalTerminalDrawing(renderProbe, acquireDrawable = { acquireMetalDrawable(it) }) {
                    if (!disposed) setNeedsDisplay(true)
                }
                gpu = renderer
                renderer.setVisible(false)
                layerContentsPlacement = NSViewLayerContentsPlacementTopLeft
                wantsLayer = true
                layerContentsRedrawPolicy = NSViewLayerContentsRedrawDuringViewResize
                layer!!.delegate = metalDisplayDelegate
            } catch (error: Exception) {
                renderingFailure = error.message
                releaseGpu()
                if (rendering == TerminalRendering.GPU) throw error
            }
        }
        ready = true
        addSubview(progressIndicator)
        addSubview(linkPreviewLabel)
        bounds.useContents { progressIndicator.setFrameSize(NSMakeSize(size.width, 4.0)) }
        viewport.setFocused(false)
        measure()
    }

    override fun isFlipped(): Boolean = true
    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if (!ready || disposed) return
        windowResizeBinding.cancelPending()
        clipboardPaste.cancel()
        clearLinkPointer()
        hoverWindowObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        hoverWindowObservation = window?.let { owner ->
            NSNotificationCenter.defaultCenter.addObserverForName(NSWindowDidResignKeyNotification,
                owner, NSOperationQueue.mainQueue) { clearLinkPointer(); focusChanged(false) }
        }
        focusWindowObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        focusWindowObservation = window?.let { owner ->
            NSNotificationCenter.defaultCenter.addObserverForName(NSWindowDidBecomeKeyNotification,
                owner, NSOperationQueue.mainQueue) { if (owner.firstResponder == this) focusChanged(true) }
        }
        if (window == null) focusChanged(false)
        dragAndDrop.cancel()
        visibilityObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        visibilityObservation = window?.let { owner ->
            NSNotificationCenter.defaultCenter.addObserverForName(NSWindowDidChangeOcclusionStateNotification,
                owner, NSOperationQueue.mainQueue) {
                    if (owner.occlusionState and NSWindowOcclusionStateVisible == 0uL) clearLinkPointer()
                    refreshVisibilityAfterTransition()
                }
        }
        refreshVisibilityAfterTransition()
    }
    override fun viewDidHide() { super.viewDidHide(); if (ready) { clearLinkPointer(); refreshVisibilityAfterTransition() } }
    override fun viewDidUnhide() { super.viewDidUnhide(); if (ready) refreshVisibilityAfterTransition() }
    private fun refreshVisibilityAfterTransition() {
        if (!ready || disposed) return
        if (!updateVisibility()) releaseHiddenResources()
        if (visibilityRefreshQueued) return
        visibilityRefreshQueued = true
        // AppKit can reuse a cached backing surface while completing unhide/attachment.
        // Recheck after that transition and request a fresh frame even if the cached view
        // consumed the synchronous dirty mark before it became drawable.
        NSOperationQueue.mainQueue.addOperationWithBlock {
            visibilityRefreshQueued = false
            if (updateVisibility()) viewport.markAll()
        }
    }
    private fun updateVisibility(): Boolean {
        if (!ready || disposed) return false
        val owner = window
        val visible = owner != null && owner.visible && !owner.miniaturized &&
            owner.occlusionState and NSWindowOcclusionStateVisible != 0uL && !hiddenOrHasHiddenAncestor
        if (visible != graphicsVisible) {
            graphicsVisible = visible
            gpu?.setVisible(visible)
            if (!visible) releaseHiddenResources()
        }
        viewport.setVisible(visible)
        visibilityBinding.setVisible(visible)
        return visible
    }
    private fun releaseHiddenResources() {
        gpu?.setVisible(false)
        softwareImages.clear(); clearSoftwareGlyphs()
        attributes.clear(); colors.clear()
    }
    override fun makeBackingLayer(): CALayer = gpu?.layer ?: super.makeBackingLayer()
    override fun isOpaque(): Boolean = true
    override fun acceptsFirstResponder(): Boolean = !disposed
    override fun resetCursorRects() {
        super.resetCursorRects()
        if (!disposed) addCursorRect(bounds, pointerCursor)
    }
    override fun becomeFirstResponder(): Boolean {
        if (disposed) return false
        focusChanged(true); return true
    }
    override fun resignFirstResponder(): Boolean {
        focusChanged(false)
        return true
    }
    private fun focusChanged(value: Boolean) {
        if (disposed) return
        if (!value) {
            mouseReporter.cancel(); selecting = false; clearLinkPointer(); localMouseButtons.clear()
        }
        if (terminalFocused == value) return
        if (!value) { keyFocus.release { session.sendKey(it) }; pressedKeys.clear() }
        terminalFocused = value; viewport.setFocused(value); session.sendFocus(value)
        if (value) notifications?.clear()
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        mouseTrackingArea?.let { removeTrackingArea(it) }; mouseTrackingArea = null
        if (ready && !disposed) {
            mouseTrackingArea = NSTrackingArea(NSMakeRect(0.0, 0.0, 0.0, 0.0),
                NSTrackingMouseMoved or NSTrackingMouseEnteredAndExited or NSTrackingActiveInKeyWindow or NSTrackingInVisibleRect, this, null)
                .also { addTrackingArea(it) }
        }
    }

    public fun configureFont(family: String?, size: Double) {
        require(family == null || family.isNotBlank() && family.length <= 256 && family.none { it.code < 32 })
        require(size.isFinite() && size in 6.0..96.0)
        if (fontSize == size && fontFamily == family) return
        clearSoftwareGlyphs()
        fontSize = size; fontFamily = family; font = terminalNativeFont(family, size)
        attributes.clear(); measure()
    }

    override fun setFrameSize(newSize: CValue<NSSize>) {
        super.setFrameSize(newSize)
        if (ready && !disposed) measure()
    }

    override fun viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        if (ready && !disposed) measure()
    }

    override fun viewWillStartLiveResize() {
        liveResizing = true
        super.viewWillStartLiveResize()
    }

    override fun viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        liveResizing = false
        if (ready && !disposed) { viewport.markAll(); setNeedsDisplay(true) }
    }

    private fun measure(force: Boolean = false) {
        cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        cellHeight = ceil(font.ascender - font.descender + font.leading + 2.0)
        bounds.useContents {
            if (size.width > 0 && size.height > 0) {
                pendingGridSize = floor(size.width / cellWidth).toInt().coerceIn(1, 4096) to
                    floor(size.height / cellHeight).toInt().coerceIn(1, 4096)
                if (force || !session.synchronizedOutput) applyPendingGridSize(force)
            }
        }
        viewport.markAll()
        setNeedsDisplay(true)
    }

    private fun applyPendingGridSize(force: Boolean = false) {
        val (columns, rows) = pendingGridSize ?: return
        pendingGridSize = null
        // Pixel-only changes must not end an application's synchronized-output hold.
        // For a real grid change, let the current atomic output finish before sending
        // SIGWINCH; the existing hold deadline still bounds an abandoned update.
        val scale = window?.backingScaleFactor ?: 1.0
        val cellSize = TerminalCellSize((cellWidth * scale).toInt().coerceAtLeast(1), (cellHeight * scale).toInt().coerceAtLeast(1))
        val gridChanged = columns != session.columns || rows != session.rows
        if (force || gridChanged || cellSize != session.cellSize) {
            if (!force && liveResizing && gridChanged) viewport.resizeSession(columns, rows, cellSize)
            else session.resize(columns, rows, cellSize)
        }
    }

    private fun resizeWindow(request: TerminalWindowResizeRequest) {
        val owner = window ?: return
        val size = request.windowSize(session.columns, session.rows, cellWidth, cellHeight)
        val available = owner.screen?.visibleFrame ?: return
        val widthDelta = size.width?.let { it - bounds.useContents { this.size.width } } ?: 0.0
        val heightDelta = size.height?.let { it - bounds.useContents { this.size.height } } ?: 0.0
        val minWidth = owner.minSize.useContents { width }
        val minHeight = owner.minSize.useContents { height }
        val maxWidth = owner.maxSize.useContents { width }
        val maxHeight = owner.maxSize.useContents { height }
        owner.frame.useContents {
            val oldWidth = this.size.width; val oldHeight = this.size.height
            val oldX = origin.x; val oldTop = origin.y + oldHeight
            available.useContents {
                val width = if (size.width == null) oldWidth else (oldWidth + widthDelta)
                    .coerceIn(minWidth.coerceAtMost(this.size.width), maxWidth.coerceAtMost(this.size.width))
                val height = if (size.height == null) oldHeight else (oldHeight + heightDelta)
                    .coerceIn(minHeight.coerceAtMost(this.size.height), maxHeight.coerceAtMost(this.size.height))
                val x = if (size.width == null) oldX else oldX.coerceIn(origin.x, origin.x + (this.size.width - width).coerceAtLeast(0.0))
                val y = if (size.height == null) oldTop - oldHeight else (oldTop - height)
                    .coerceIn(origin.y, origin.y + (this.size.height - height).coerceAtLeast(0.0))
                owner.setFrame(NSMakeRect(x, y, width, height), display = true)
            }
        }
    }

    private fun invalidateRows() {
        if (!ready || disposed) return
        val selectionKey = viewport.accessibleSelectionKey()
        if (selectionKey != accessibilitySelectionKey) {
            accessibilitySelectionKey = selectionKey
            accessibilitySelectionTimer?.invalidate()
            accessibilitySelectionTimer = NSTimer.timerWithTimeInterval(0.1, repeats = false) {
                accessibilitySelectionTimer = null
                if (!disposed) notifyAccessibilitySelectionChanged()
            }.also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
        }
        refreshLinkHover()
        updateVisibility()
        onViewportChanged?.invoke()
        val first = viewport.firstDirtyRow
        val last = viewport.lastDirtyRow
        if (last >= first) setNeedsDisplayInRect(NSMakeRect(0.0, first * cellHeight, bounds.useContents { size.width }, (last - first + 1) * cellHeight))
    }

    override fun wantsUpdateLayer(): Boolean = gpu != null

    override fun updateLayer() {
        if (disposed) return
        if (!updateVisibility()) return
        gpu?.let { renderer ->
            try {
                // Metal is the view's backing layer, so there is no separate AppKit
                // bitmap to clear or swap while its geometry changes.
                val scale = window?.backingScaleFactor ?: 1.0
                bounds.useContents { renderer.resize(size.width, size.height, scale, fontSize, cellWidth, cellHeight,
                    managesLayerFrame = false, fontFamily = fontFamily) }
                renderer.draw(viewport, synchronizePresentation = liveResizing)
                return
            } catch (error: Exception) {
                if (rendering == TerminalRendering.GPU) throw error
                renderingFailure = error.message
                releaseGpu()
                viewport.markAll(); setNeedsDisplay(true)
            }
        }
    }

    private fun releaseGpu() {
        val renderer = gpu
        gpu = null
        renderer?.let {
            renderer.layer.delegate = null
            wantsLayer = false; layer = null
            renderer.dispose()
        }
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        if (disposed) return
        // Explicit offscreen snapshots have no window; queued hidden-window draws must
        // not repopulate the caches just released by the visibility notification.
        if (!updateVisibility() && (window != null || hiddenOrHasHiddenAncestor)) return
        if (gpu != null) { updateLayer(); return }
        val measurement = renderProbe.begin(viewport, "appkit")
        color(viewport.theme.background).setFill(); NSRectFill(dirtyRect)
        val first = dirtyRect.useContents { floor(origin.y / cellHeight).toInt().coerceAtLeast(0) }
        val last = dirtyRect.useContents { floor((origin.y + size.height) / cellHeight).toInt().coerceAtMost(viewport.rows - 1) }
        val scale = window?.backingScaleFactor ?: 1.0
        val painter = object : TerminalPainter {
            private var alpha = 1.0
            override fun withOpacity(opacity: Double, draw: () -> Unit) {
                val context = NSGraphicsContext.currentContext?.CGContext ?: return draw()
                val previous = alpha
                alpha *= opacity
                CGContextSaveGState(context)
                try { CGContextSetAlpha(context, alpha); draw() } finally { alpha = previous; CGContextRestoreGState(context) }
            }
            override fun fill(x: Double, y: Double, width: Double, height: Double, color: Int) {
                val left = round(x * scale) / scale
                val top = round(y * scale) / scale
                color(color).setFill()
                NSRectFillUsingOperation(NSMakeRect(left, top, terminalPixelSpan(x, width, scale),
                    terminalPixelSpan(y, height, scale)), NSCompositingOperationSourceOver)
            }
            override fun text(value: String, x: Double, y: Double, color: Int, style: Int) {
                // The shared painter draws decorations; native underlining would composite them twice.
                NSAttributedString.create(string = value, attributes = textAttributes(color, style and TerminalStyle.UNDERLINES.inv())).drawAtPoint(NSMakePoint(x, y))
            }
        }
        val images = viewport.graphicsFrame().mapNotNull { it.quad(cellWidth, cellHeight, scale, viewport.columns, viewport.rows) }
        if (softwareGlyphs != null && softwareGlyphs?.scale != scale) {
            clearSoftwareGlyphs(); viewport.markAll()
        }
        val glyphs = softwareGlyphs ?: AppKitTerminalGlyphs(fontFamily, fontSize, scale, cellWidth, cellHeight).also {
            softwareGlyphs = it; viewport.markAll()
        }
        glyphs.prepare(viewport)
        val frame = glyphs.frame
        val rows = if (last < first) emptyList() else frame.rows.subList(first, last + 1)
        softwareImages.beginFrame()
        softwareImages.draw(images, TerminalGraphicsLayer.BELOW_BACKGROUND)
        for (row in rows) for (rect in row.backgrounds) if (!rect.defaultBackground)
            painter.fill(rect.x, rect.y, rect.width, rect.height, rect.color)
        softwareImages.draw(images, TerminalGraphicsLayer.BELOW_TEXT)
        for (row in rows) for (rect in row.decorations) if (rect.behindText) painter.withOpacity(rect.opacity) {
            painter.fill(rect.x, rect.y, rect.width, rect.height, rect.color)
        }
        glyphs.draw(first, last)
        for (row in rows) for (rect in row.decorations) if (!rect.behindText) painter.withOpacity(rect.opacity) {
            painter.fill(rect.x, rect.y, rect.width, rect.height, rect.color)
        }
        softwareImages.draw(images, TerminalGraphicsLayer.ABOVE_TEXT)
        softwareImages.endFrame()
        measurement?.submitted(0)
    }

    private fun color(rgb: Int): NSColor {
        if (colors.size > 512) colors.clear()
        return colors.getOrPut(rgb) { NSColor.colorWithSRGBRed((rgb shr 16 and 255) / 255.0, (rgb shr 8 and 255) / 255.0, (rgb and 255) / 255.0, 1.0) }
    }
    private fun textAttributes(rgb: Int, style: Int): Map<Any?, *> {
        if (attributes.size > 512) attributes.clear()
        return attributes.getOrPut(rgb to style) {
            val styled = terminalNativeFont(fontFamily, fontSize, style)
            mapOf(NSFontAttributeName to styled, NSForegroundColorAttributeName to color(rgb),
                NSUnderlineStyleAttributeName to if (style and TerminalStyle.UNDERLINE != 0) 1 else 0)
        }
    }

    override fun keyDown(event: NSEvent) {
        if (disposed) return
        if (navigateViewport(event)) return
        val flags = event.modifierFlags
        val command = flags and NSEventModifierFlagCommand != 0uL
        val shift = flags and NSEventModifierFlagShift != 0uL
        val control = flags and NSEventModifierFlagControl != 0uL
        val raw = event.charactersIgnoringModifiers.orEmpty()
        val chars = if (command || control) terminalShortcutKey(raw, event.keyCode.toInt()) else raw
        if (command && !shift && !control && flags and NSEventModifierFlagOption == 0uL &&
            !hasMarkedText() && chars.lowercase() == "j" && scrollToSelection()) return
        if (command && !hasMarkedText()) {
            val localCommand = chars.lowercase().takeIf {
                !control && flags and NSEventModifierFlagOption == 0uL && (!shift || it == "k")
            }
            when (localCommand) {
                "c" -> copySelection(); "v" -> pasteClipboard(); "a" -> selectAll(null)
                "k" -> if (shift) clearHistory(null) else clearScreen(null)
                else -> if (session.kittyKeyboardFlags != 0 || session.modifyOtherKeysMode == 2) {
                    val terminalKey = terminalKey(event)
                    if (sendTypedKey(terminalKey)) pressedKeys[event.keyCode] = terminalKey
                } else super.keyDown(event)
            }
            return
        }
        val keypad = nativeKeypad(event.keyCode.toInt())
        val key = if (keypad == TerminalKeypadKey.ENTER) "Enter" else nativeKey(chars)
        if (session.keyboardInputBlocked) return
        val extendedKeyboard = session.kittyKeyboardFlags != 0 || session.modifyOtherKeysMode == 2
        val terminalKey = when {
            extendedKeyboard || command -> terminalKey(event)
            flags and NSEventModifierFlagOption != 0uL -> terminalKey(event).copy(key = key)
            else -> TerminalKey(key, control, false, shift, keypad = keypad)
        }
        // Printable text goes through NSTextInputClient first so dead keys and IME own composition.
        val optionPressed = flags and NSEventModifierFlagOption != 0uL
        val interpretText = (extendedKeyboard || optionPressed) && terminalKey.key.terminalCodepoints().size == 1 &&
            !control && !command && !(optionAsAlt.applies(flags) && optionPressed)
        if (!hasMarkedText() && !interpretText && sendTypedKey(terminalKey)) {
            pressedKeys[event.keyCode] = terminalKey
            return
        }
        interpretTerminalKey(event, terminalKey) { interpretKeyEvents(listOf(event)) }
    }

    /** AppKit may commit text, change preedit and issue a command during one interpretation.
     * Keep that boundary explicit so isolated tests can supply input-method callbacks. */
    internal fun interpretTerminalKey(event: NSEvent, key: TerminalKey = terminalKey(event), interpret: () -> Unit) {
        if (disposed || session.keyboardInputBlocked) return
        val markedBefore = hasMarkedText()
        val current = KeyInterpretation()
        val previous = keyInterpretation
        keyInterpretation = current
        try { interpret() } finally { keyInterpretation = previous }
        if (disposed || session.keyboardInputBlocked) return
        val composing = markedBefore || hasMarkedText()
        if (markedBefore && current.text.isNotEmpty()) {
            for (text in current.text) if (!suppressComposingControl(text, composing)) sendTypedText(text)
            // Plain Left already leaves the caret where Korean input methods put it.
            // The other arrows (and modified Left) still need to reach the application.
            val replay = when (event.keyCode.toInt()) {
                124, 125, 126 -> true
                123 -> event.modifierFlags and (NSEventModifierFlagShift or NSEventModifierFlagControl or
                    NSEventModifierFlagOption or NSEventModifierFlagCommand) != 0uL
                else -> false
            }
            if (replay && sendTypedKey(key)) pressedKeys[event.keyCode] = key
        } else if (current.text.isNotEmpty()) {
            for (text in current.text) {
                if (suppressComposingControl(text, composing)) continue
                if (session.kittyKeyboardFlags != 0 || session.modifyOtherKeysMode == 2) {
                    val committed = key.copy(text = text)
                    if (!sendTypedKey(committed)) sendTypedText(text)
                    pressedKeys[event.keyCode] = committed
                } else sendTypedText(text)
            }
        } else if (!composing && current.command && sendTypedKey(key)) {
            pressedKeys[event.keyCode] = key
        }
    }

    private fun suppressComposingControl(text: String, composing: Boolean): Boolean =
        composing && text.length == 1 && text[0].code < 0x20

    /** Ghostty's macOS viewport defaults use physical navigation keys and exact modifiers.
     * Consumed press/repeat events never enter pressedKeys, so their releases stay local. */
    private fun navigateViewport(event: NSEvent): Boolean {
        val flags = event.modifierFlags
        if (hasMarkedText() || flags and NSEventModifierFlagCommand == 0uL ||
            flags and (NSEventModifierFlagControl or NSEventModifierFlagOption) != 0uL) return false
        val shift = flags and NSEventModifierFlagShift != 0uL
        val previous = viewport.scrollOffset
        // macOS virtual key codes; keypad aliases must not trigger navigation.
        when (event.keyCode.toInt()) {
            115 -> if (!shift) viewport.scrollTo(session.historySize) else return false // Home
            119 -> if (!shift) viewport.scrollTo(0) else return false // End
            116 -> if (!shift) viewport.scroll(session.rows) else return false // Page Up
            121 -> if (!shift) viewport.scroll(-session.rows) else return false // Page Down
            126 -> viewport.jumpToPrompt(-1) // Up, with or without Shift
            125 -> viewport.jumpToPrompt(1) // Down, with or without Shift
            else -> return false
        }
        if (viewport.scrollOffset != previous) onScrollActivity?.invoke()
        return true
    }

    private fun terminalKey(event: NSEvent): TerminalKey {
        val flags = event.modifierFlags
        val code = event.keyCode.toInt()
        val keypad = nativeKeypad(code)
        val logical = if (keypad == TerminalKeypadKey.ENTER) "Enter" else nativeKey(event.charactersIgnoringModifiers.orEmpty())
        val optionIsAlt = optionAsAlt.applies(flags)
        // Like Ghostty's AppKit translation event, remove only Option for the selected
        // side. Keep Shift/CapsLock, including their punctuation and non-ASCII layout text.
        val characters = if (optionIsAlt && flags and NSEventModifierFlagOption != 0uL)
            event.charactersByApplyingModifiers(flags and NSEventModifierFlagOption.inv())
                ?: event.charactersIgnoringModifiers
        else event.characters
        var text = characters.orEmpty().takeIf { value ->
            value.terminalCodepoints().all { terminalPrintable(it) && it !in 0xf700..0xf8ff }
        }.orEmpty()
        if (session.modifyOtherKeysMode == 2 && text.isEmpty() &&
            logical.terminalCodepoints().singleOrNull()?.let(::terminalPrintable) == true) text = logical
        // The event already carries its layout identity. Re-translating an unmodified
        // event through the current global layout can change a queued key after a
        // layout switch (and makes synthetic events depend on the user's layout).
        val layoutModifiers = NSEventModifierFlagShift or NSEventModifierFlagOption or NSEventModifierFlagCapsLock
        val unshifted = (if (flags and layoutModifiers == 0uL) event.charactersIgnoringModifiers
            else event.charactersByApplyingModifiers(0uL))?.lowercase()?.terminalCodepoints()?.singleOrNull()
        return TerminalKey(logical, control = flags and NSEventModifierFlagControl != 0uL,
            alt = flags and NSEventModifierFlagOption != 0uL, shift = flags and NSEventModifierFlagShift != 0uL,
            meta = flags and NSEventModifierFlagCommand != 0uL, keypad = keypad,
            action = if (event.isARepeat()) TerminalKeyAction.REPEAT else TerminalKeyAction.PRESS,
            text = text, unshiftedCodepoint = unshifted,
            baseCodepoint = nativeBaseCodepoint(code), capsLock = flags and NSEventModifierFlagCapsLock != 0uL,
            consumedModifiers = if (text.isNotEmpty()) (if (flags and NSEventModifierFlagShift != 0uL) 1 else 0) or
                (if (!optionIsAlt && flags and NSEventModifierFlagOption != 0uL) 2 else 0) else 0,
            optionAsAlt = optionIsAlt)
    }

    override fun keyUp(event: NSEvent) {
        if (disposed) return
        val pressed = pressedKeys.remove(event.keyCode) ?: return
        val flags = event.modifierFlags
        sendTypedKey(pressed.copy(action = TerminalKeyAction.RELEASE,
            shift = flags and NSEventModifierFlagShift != 0uL, control = flags and NSEventModifierFlagControl != 0uL,
            alt = flags and NSEventModifierFlagOption != 0uL, meta = flags and NSEventModifierFlagCommand != 0uL,
            capsLock = flags and NSEventModifierFlagCapsLock != 0uL, optionAsAlt = optionAsAlt.applies(flags)))
    }

    override fun flagsChanged(event: NSEvent) {
        if (disposed || hasMarkedText()) return
        viewport.selectionModifiersChanged(event.modifierFlags and NSEventModifierFlagOption != 0uL)
        hoverModifiers = linkEventModifiers(event); refreshLinkHover()
        val identity = when (event.keyCode.toInt()) {
            56 -> "ShiftLeft"; 60 -> "ShiftRight"; 59 -> "ControlLeft"; 62 -> "ControlRight"
            58 -> "AltLeft"; 61 -> "AltRight"; 55 -> "MetaLeft"; 54 -> "MetaRight"; 57 -> "CapsLock"
            else -> return
        }
        val flags = event.modifierFlags
        // Ghostty SurfaceView_AppKit.flagsChanged uses current event flags, not a toggle.
        // Right-side releases can leave the aggregate modifier set by the left key.
        val modifier = when (event.keyCode.toInt()) {
            56, 60 -> NSEventModifierFlagShift
            59, 62 -> NSEventModifierFlagControl
            58, 61 -> NSEventModifierFlagOption
            55, 54 -> NSEventModifierFlagCommand
            else -> NSEventModifierFlagCapsLock
        }
        // NX_DEVICERSHIFTKEYMASK / RCTL / RALT / RCMD from IOLLEvent.h.
        val right = when (event.keyCode.toInt()) {
            60 -> 0x4uL; 62 -> 0x2000uL; 61 -> 0x40uL; 54 -> 0x10uL; else -> 0uL
        }
        val pressed = flags and modifier != 0uL && (right == 0uL || flags and right != 0uL)
        sendTypedKey(TerminalKey(identity, physicalCode = identity,
            action = if (pressed) TerminalKeyAction.PRESS else TerminalKeyAction.RELEASE,
            shift = flags and NSEventModifierFlagShift != 0uL, control = flags and NSEventModifierFlagControl != 0uL,
            alt = flags and NSEventModifierFlagOption != 0uL, meta = flags and NSEventModifierFlagCommand != 0uL,
            capsLock = flags and NSEventModifierFlagCapsLock != 0uL, optionAsAlt = optionAsAlt.applies(flags)))
    }

    override fun insertText(string: Any, replacementRange: CValue<NSRange>) {
        if (disposed) return
        val value = if (string is NSAttributedString) string.string else string.toString()
        unmarkText()
        val pending = keyInterpretation
        if (pending != null) pending.text.add(value) else sendTypedText(value)
        viewport.markAll()
    }
    override fun setMarkedText(string: Any, selectedRange: CValue<NSRange>, replacementRange: CValue<NSRange>) {
        if (disposed) return
        val value = if (string is NSAttributedString) string.string else string.toString()
        if (marked.isNotEmpty() || value.isNotEmpty()) clearSelectionForPreedit()
        marked = value
        markedSelection = selectedRange; viewport.setPreedit(value)
    }
    override fun unmarkText() {
        if (marked.isNotEmpty()) clearSelectionForPreedit()
        marked = ""; viewport.setPreedit("")
    }
    override fun hasMarkedText(): Boolean = marked.isNotEmpty()
    override fun markedRange(): CValue<NSRange> = if (marked.isEmpty()) NSMakeRange(NSNotFound.toULong(), 0u) else NSMakeRange(0u, marked.length.toULong())
    override fun selectedRange(): CValue<NSRange> = markedSelection
    override fun validAttributesForMarkedText(): List<*> = listOf(NSUnderlineStyleAttributeName)
    override fun attributedSubstringForProposedRange(range: CValue<NSRange>, actualRange: CPointer<NSRange>?): NSAttributedString? = null
    override fun characterIndexForPoint(point: CValue<NSPoint>): ULong = 0u
    override fun firstRectForCharacterRange(range: CValue<NSRange>, actualRange: CPointer<NSRange>?): CValue<NSRect> {
        val selectionRange = selectedRange().useContents { location to length }
        val isLookup = range.useContents { length > 0uL && (location != selectionRange.first || length != selectionRange.second) }
        val selection = if (isLookup) viewport.selectionForLookup() else null
        val localRect = if (selection != null) lookupPoint(selection.start).useContents {
            // Ghostty's definition rectangle extends upward from the baseline in screen
            // coordinates. This view is flipped, so its local rectangle starts one cell above.
            NSMakeRect(x - 2.0, y + 2.0 - cellHeight, cellWidth, cellHeight)
        } else {
            // Surface.imePointScaled uses the live cursor even during synchronized output.
            var x = (session.cursorColumn + 0.5) * cellWidth
            val y = session.cursorRow * cellHeight
            val scale = window?.backingScaleFactor ?: 1.0
            val available = bounds.useContents { size.width } - (session.cursorColumn + 1) * cellWidth
            // Preserve Ghostty's explicit width-unit exception: its preedit width stays
            // in backing pixels, while x/y/height are returned in AppKit points.
            var width = minOf(viewport.preeditWidth * cellWidth, available).coerceAtLeast(0.0) * scale
            range.useContents {
                if (length == 0uL && width > 0.0) {
                    width = 0.0
                    if (location != NSNotFound.toULong()) x += location.toDouble() * cellWidth
                }
            }
            NSMakeRect(x, y, width, cellHeight)
        }
        val rect = convertRect(localRect, toView = null)
        return window?.convertRectToScreen(rect) ?: rect
    }

    private fun lookupPoint(start: TerminalCellPosition?): CValue<NSPoint> = if (start == null) NSMakePoint(-1.0, -1.0)
        else NSMakePoint(start.column * cellWidth, start.line * cellHeight + lookupLayout.defaultBaselineOffsetForFont(font))

    override fun quickLookWithEvent(event: NSEvent) {
        if (disposed) return
        val (column, row) = cell(event)
        val word = viewport.lookupWord(column, row)
        if (word == null || word.text.isEmpty()) { super.quickLookWithEvent(event); return }
        showLookup(NSAttributedString.create(string = word.text, attributes = mapOf(NSFontAttributeName to font)), lookupPoint(word.start))
    }
    override fun doCommandBySelector(selector: CPointer<out CPointed>?) {
        if (disposed) return
        val key = when (NSStringFromSelector(selector)) {
            "insertNewline:" -> "Enter"; "insertTab:" -> "Tab"; "deleteBackward:" -> "Backspace"; "cancelOperation:" -> "Escape"
            else -> return
        }
        keyInterpretation?.let { it.command = true; return }
        if (hasMarkedText()) return
        sendTypedKey(TerminalKey(key))
    }

    override fun mouseDown(event: NSEvent) {
        if (disposed) return
        window?.makeFirstResponder(this)
        val pointer = selectionPointer(event)
        val interval = if (clickRepeatIntervalMillis == 0L) NSEvent.doubleClickInterval * 1000.0 else clickRepeatIntervalMillis.toDouble()
        val continued = event.modifierFlags and NSEventModifierFlagShift != 0uL && !session.mouseShiftCapture &&
            viewport.continuePointerSelection(pointer, event.timestamp * 1000.0, interval,
                event.modifierFlags and NSEventModifierFlagOption != 0uL)
        selecting = continued || !mouse(event, 0)
        if (selecting && !continued) viewport.pressPointerSelection(pointer, event.timestamp * 1000.0, interval,
            event.modifierFlags and NSEventModifierFlagCommand != 0uL)
        else if (!selecting) { viewport.resetPointerClicks(); viewport.clearSelection() }
        updateLinkPointer(event)
    }
    override fun mouseDragged(event: NSEvent) {
        if (disposed) return
        if (selecting) viewport.extendPointerSelection(selectionPointer(event), event.modifierFlags and NSEventModifierFlagOption != 0uL)
        else mouse(event, 0, motion = true)
        updateLinkPointer(event)
    }
    override fun pressureChangeWithEvent(event: NSEvent) {
        if (disposed) return
        if (selecting && event.stage == 2L) viewport.deepPressSelection()
        if (event.stage == 2L && !forceClickLookupFired) {
            forceClickLookupFired = true
            if (forceClickLookupEnabled()) quickLookWithEvent(event)
        }
    }
    private fun linkEventModifiers(event: NSEvent): TerminalLinkModifiers {
        val flags = event.modifierFlags
        return TerminalLinkModifiers(flags and NSEventModifierFlagShift != 0uL, flags and NSEventModifierFlagControl != 0uL,
            flags and NSEventModifierFlagOption != 0uL, flags and NSEventModifierFlagCommand != 0uL)
    }
    private fun linkModifiers(event: NSEvent): Boolean = linkEventModifiers(event).binding(true, session)
    private fun updateLinkPointer(event: NSEvent) {
        hoverPoint = convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        hoverModifiers = linkEventModifiers(event); refreshLinkHover()
    }
    private fun clearLinkPointer() { hoverPoint = null; refreshLinkHover() }
    private fun refreshLinkHover() {
        if (!ready || refreshingLinkHover) return
        refreshingLinkHover = true
        try {
            val point = hoverPoint
            val pointer = if (point == null || disposed || hiddenOrHasHiddenAncestor || window == null ||
                !hoverModifiers.hoverAllowed(true, session, viewport.scrollOffset)) null else
                terminalSelectionPointer(point.first, point.second, cellWidth, cellHeight, window?.backingScaleFactor ?: 1.0)
            val target = if (pointer != null && (!selecting || viewport.linkHoverAllowedWhileDragging(pointer)))
                viewport.setLinkHover(pointer.column, pointer.row) else viewport.setLinkHover()
            val cursor = if (target != null) NSCursor.pointingHandCursor else protocolPointerCursor
            if (pointerCursor != cursor) { pointerCursor = cursor; window?.invalidateCursorRectsForView(this) }
            val preview = terminalLinkPreview(target, linkPreviewMode)
            linkPreviewLabel.hidden = preview == null
            linkPreviewLabel.stringValue = preview.orEmpty()
            if (preview != null) {
                linkPreviewLabel.textColor = color(viewport.theme.foreground)
                linkPreviewLabel.backgroundColor = color(viewport.theme.background)
                val width = linkPreviewLabel.attributedStringValue.size().useContents { width } + 12.0
                bounds.useContents { linkPreviewLabel.setFrame(NSMakeRect(0.0, (size.height - 20.0).coerceAtLeast(0.0),
                    minOf(width, size.width), 20.0)) }
            }
        } finally { refreshingLinkHover = false }
    }
    private fun activateLink(link: TerminalLinkTarget): Boolean {
        if (openLink !== defaultOpenLink) return openLink(link.uri)
        val target = if (link.osc8) link.uri else {
            val directory = memScoped {
                val hostname = allocArray<ByteVar>(1024)
                session.localWorkingDirectory(if (gethostname(hostname, 1024u) == 0) hostname.toKString() else "")
            }
            nativeDetectedLinkTarget(link.uri, directory)
        }
        return links.open(target) { link.enabled(session) }
    }
    override fun mouseUp(event: NSEvent) {
        if (disposed) return
        forceClickLookupFired = false
        if (selecting) {
            val pointer = selectionPointer(event)
            val link = if (linkModifiers(event)) viewport.pointerLinkAt(pointer) else null
            if (link != null && activateLink(link)) viewport.cancelSelectionDrag() else viewport.endPointerSelection(pointer)
            copySelectionAutomatically()
        } else mouse(event, 0, release = true)
        selecting = false
        updateLinkPointer(event)
    }
    override fun rightMouseDown(event: NSEvent) {
        if (!secondaryMouseDown(event, 2)) super.rightMouseDown(event)
    }
    override fun rightMouseUp(event: NSEvent) { mouse(event, 2, release = true) }
    override fun rightMouseDragged(event: NSEvent) { mouse(event, 2, motion = true) }
    override fun otherMouseDown(event: NSEvent) { nativeMouseButton(event)?.let { secondaryMouseDown(event, it) } }
    override fun otherMouseUp(event: NSEvent) { nativeMouseButton(event)?.let { mouse(event, it, release = true) } }
    override fun otherMouseDragged(event: NSEvent) { nativeMouseButton(event)?.let { mouse(event, it, motion = true) } }
    override fun mouseMoved(event: NSEvent) {
        if (disposed) return
        if (!selecting) mouse(event, 3, motion = true)
        updateLinkPointer(event)
    }
    override fun mouseEntered(event: NSEvent) { if (!disposed) updateLinkPointer(event) }
    override fun mouseExited(event: NSEvent) { clearLinkPointer() }
    override fun scrollWheel(event: NSEvent) {
        if (disposed) return
        val resetPhases = NSEventPhaseMayBegin or NSEventPhaseBegan or NSEventPhaseCancelled
        if (event.phase and resetPhases != 0uL) { verticalScroll.reset(); horizontalScroll.reset() }
        val shift = event.modifierFlags and NSEventModifierFlagShift != 0uL
        val reporting = session.mouseTracking != 0 && viewport.scrollOffset == 0 && (!shift || session.mouseShiftCapture)
        val alternateScroll = session.alternateScreen && session.alternateScroll && session.mouseTracking == 0 && !shift
        // Wheel reports and cursor keys carry no gesture phase or cancellation.
        // Forwarding a flick's inertial tail queues commands the next gesture cannot
        // retract. Keep momentum for local history, but only direct input for TUIs.
        if ((reporting || alternateScroll) && event.momentumPhase != NSEventPhaseNone) {
            verticalScroll.reset(); horizontalScroll.reset()
            return
        }
        if (!reporting && event.scrollingDeltaY != 0.0) onScrollActivity?.invoke()
        val tick = if (reporting) 1.0 else 3.0
        // With no inertial tail, direct trackpad gestures need more travel per
        // point in TUIs. Keep fractional accumulation and the per-event limit.
        val verticalScale = if (event.hasPreciseScrollingDeltas && (reporting || alternateScroll)) 2.0 else 1.0
        val dy = verticalScroll.consume(event.scrollingDeltaY * verticalScale, event.hasPreciseScrollingDeltas, cellHeight, tick)
        val dx = horizontalScroll.consume(event.scrollingDeltaX, event.hasPreciseScrollingDeltas, cellWidth, tick)
        if (dy != 0) {
            val button = if (dy > 0) 64 else 65
            if (mouse(event, button)) repeat((kotlin.math.abs(dy) - 1).coerceAtMost(63)) { mouse(event, button) }
            else if (!session.sendAlternateScroll(dy, shift = shift)) viewport.scroll(dy)
        }
        if (dx != 0) repeat(kotlin.math.abs(dx).coerceAtMost(64)) { mouse(event, if (dx > 0) 66 else 67) }
    }
    private fun cell(event: NSEvent): Pair<Int, Int> = convertPoint(event.locationInWindow, fromView = null).useContents {
        floor(x / cellWidth).toInt() to floor(y / cellHeight).toInt()
    }
    private fun selectionPointer(event: NSEvent): TerminalSelectionPointer = convertPoint(event.locationInWindow, fromView = null).useContents {
        terminalSelectionPointer(x, y, cellWidth, cellHeight, window?.backingScaleFactor ?: 1.0)
    }
    private fun mouse(event: NSEvent, button: Int, release: Boolean = false, motion: Boolean = false): Boolean {
        if ((release || motion) && button in localMouseButtons) {
            if (release) localMouseButtons.remove(button)
            return false
        }
        if (disposed || viewport.scrollOffset != 0) return false
        val scale = window?.backingScaleFactor ?: 1.0
        val geometry = bounds.useContents {
            if (size.width <= 0 || size.height <= 0) return false
            TerminalMouseGeometry(size.width * scale, size.height * scale, cellWidth * scale, cellHeight * scale)
        }
        return convertPoint(event.locationInWindow, fromView = null).useContents {
            mouseReporter.sendPixels(x * scale, y * scale, geometry, button, release, motion,
                event.modifierFlags and NSEventModifierFlagShift != 0uL, event.modifierFlags and NSEventModifierFlagOption != 0uL,
                event.modifierFlags and NSEventModifierFlagControl != 0uL)
        }
    }
    private fun beforeTypedInput(key: TerminalKey? = null) {
        if (key?.selectionModifier == true) return
        viewport.followOutput()
        if (selectionClearOnTyping || key?.key == "Escape") viewport.clearSelection()
    }
    private fun sendTypedKey(key: TerminalKey): Boolean {
        if (disposed) return true
        if (!session.keyboardInputBlocked) keyFocus.record(key)
        return session.sendKey(key) { beforeTypedInput(key) }
    }
    private fun sendTypedText(text: String) {
        if (disposed || session.keyboardInputBlocked || text.isEmpty()) return
        beforeTypedInput(); session.sendText(text)
    }
    private fun clearSelectionForPreedit() { if (selectionClearOnTyping) viewport.clearSelection() }
    private fun copySelectionAutomatically() = copySelection(TerminalCopyFormat.MIXED, copyOnSelect.locations, clearAfter = false)
    private fun secondaryMouseDown(event: NSEvent, button: Int): Boolean {
        if (disposed) return true
        localMouseButtons.remove(button)
        if (mouse(event, button)) { viewport.resetPointerClicks(); viewport.clearSelection(); return true }
        if (button in 1..2) localMouseButtons.add(button)
        return terminalMouseClick(button, rightClickAction, middleClickAction, selectionClipboardSupported,
            viewport.hasSelection, { copySelection(TerminalCopyFormat.MIXED, clearAfter = false) },
            { viewport.clearSelection() }, ::pasteMouseClipboard)
    }
    private fun pasteMouseClipboard(location: TerminalClipboardLocation) {
        if (disposed || window == null) return
        clipboardPaste.cancel()
        if (session.pasteClipboard(location)) { viewport.followOutput(); return }
        runCatching {
            clipboardPaste.read(pastePolicy, { viewport.followOutput() }) { complete -> copyClipboard.readPasteText(location, complete) }
        }
    }
    private fun copySelection(format: TerminalCopyFormat = copyFormat,
        locations: List<TerminalClipboardLocation> = listOf(TerminalClipboardLocation.STANDARD),
        clearAfter: Boolean = selectionClearOnCopy) {
        if (locations.isEmpty()) return
        viewport.copyData(format, copyTrimTrailingSpaces, copyCodepointMap)?.let {
            val contents = it.contents()
            for (location in locations) runCatching { copyClipboard.write(location, contents) {} }
            if (clearAfter) viewport.clearSelection()
        }
    }
    private fun pasteClipboard() {
        if (disposed) return
        clipboardPaste.cancel()
        if (session.pasteClipboard()) { viewport.followOutput(); viewport.clearSelection(); return }
        readPasteText()?.let { clipboardPaste.paste(it, pastePolicy) }
    }
    public fun scrollTo(offset: Int) {
        val previous = viewport.scrollOffset
        viewport.scrollTo(offset)
        if (viewport.scrollOffset != previous) onScrollActivity?.invoke()
    }
    public fun reveal(match: TerminalSearchMatch): Boolean = viewport.selectMatch(match)
    public fun scrollToSelection(): Boolean {
        val previous = viewport.scrollOffset
        return viewport.scrollToSelection().also {
            if (viewport.scrollOffset != previous) onScrollActivity?.invoke()
        }
    }
    public fun jumpToPrompt(direction: Int): Boolean = viewport.jumpToPrompt(direction).also {
        if (it) onScrollActivity?.invoke()
    }
    public fun clearSelection() { viewport.clearSelection() }
    @ObjCAction public fun copy(sender: NSObject?) { copySelection() }
    @ObjCAction public fun paste(sender: NSObject?) { pasteClipboard() }
    @ObjCAction public fun selectAll(sender: NSObject?) { if (viewport.trySelectAll()) copySelectionAutomatically() }
    @ObjCAction public fun clearScreen(sender: NSObject?) { viewport.clearSelection(); session.clearScreen(); viewport.followOutput() }
    @ObjCAction public fun clearHistory(sender: NSObject?) { viewport.clearSelection(); session.clearHistory(); viewport.followOutput() }

    override fun menuForEvent(event: NSEvent): NSMenu = NSMenu().apply {
        if (rightClickAction == TerminalRightClickAction.CONTEXT_MENU && (viewport.scrollOffset != 0 || session.mouseTracking == 0 ||
            event.modifierFlags and NSEventModifierFlagShift != 0uL && !session.mouseShiftCapture)) {
            val pointer = selectionPointer(event)
            if (viewport.selectForContextMenu(pointer.column, pointer.row, linkModifiers(event))) copySelectionAutomatically()
        }
        fun item(title: String, action: String, enabled: Boolean = true) {
            addItem(NSMenuItem(title, NSSelectorFromString(action), "").also {
                it.target = this@AppKitTerminalView; it.enabled = enabled
            })
        }
        autoenablesItems = false
        item("Copy", "copy:", viewport.hasSelection)
        item("Paste", "paste:", session.clipboardPasteEventsEnabled || NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString) != null)
        item("Select All", "selectAll:")
        addItem(NSMenuItem.separatorItem())
        item("Clear Screen", "clearScreen:")
        item("Clear Scrollback", "clearHistory:", session.historySize > 0)
    }

    public fun pasteFilePaths(paths: List<String>) {
        if (paths.isEmpty() || disposed) return
        require(paths.all { it.startsWith('/') && '\u0000' !in it })
        viewport.followOutput(); viewport.clearSelection()
        session.paste(paths.joinToString(" ", postfix = " ", transform = ::terminalShellQuote))
    }

    private fun draggedPaths(sender: NSDraggingInfoProtocol): List<String> = terminalDroppedFilePaths(sender.draggingPasteboard)

    override fun draggingEntered(sender: NSDraggingInfoProtocol): NSDragOperation =
        dragAndDrop.move(sender, entering = true) ?: if (draggedPaths(sender).isNotEmpty()) NSDragOperationCopy else NSDragOperationNone
    override fun draggingUpdated(sender: NSDraggingInfoProtocol): NSDragOperation =
        dragAndDrop.move(sender, entering = false) ?: if (draggedPaths(sender).isNotEmpty()) NSDragOperationCopy else NSDragOperationNone
    override fun draggingExited(sender: NSDraggingInfoProtocol?) { dragAndDrop.cancel() }
    override fun draggingEnded(sender: NSDraggingInfoProtocol) { dragAndDrop.cancel() }
    override fun wantsPeriodicDraggingUpdates(): Boolean = !disposed && session.kittyDnd.state != null
    override fun prepareForDragOperation(sender: NSDraggingInfoProtocol): Boolean =
        dragAndDrop.prepare(sender) ?: draggedPaths(sender).isNotEmpty()
    override fun performDragOperation(sender: NSDraggingInfoProtocol): Boolean {
        dragAndDrop.drop(sender)?.let { return it }
        val paths = draggedPaths(sender)
        if (paths.isEmpty()) return false
        pasteFilePaths(paths); window?.makeFirstResponder(this); return true
    }
    override fun accessibilityValue(): Any? = viewport.visibleText()
    override fun accessibilityHelp(): String = "Terminal content area"
    override fun accessibilitySelectedText(): String? = viewport.selectedText().takeIf { it.isNotEmpty() }
    override fun accessibilityNumberOfCharacters(): Long = viewport.visibleText().length.toLong()
    override fun accessibilityVisibleCharacterRange(): CValue<NSRange> =
        NSMakeRange(0u, viewport.visibleText().length.toULong())
    override fun accessibilitySelectedTextRange(): CValue<NSRange> {
        val ranges = viewport.accessibleText().selections
        return if (ranges.isEmpty()) NSMakeRange(0u, 0u) else
            NSMakeRange(ranges.first().start.toULong(), (ranges.last().end - ranges.first().start).toULong())
    }
    override fun accessibilitySelectedTextRanges(): List<*> = viewport.accessibleText().selections.map {
        NSValue.valueWithRange(NSMakeRange(it.start.toULong(), (it.end - it.start).toULong()))
    }
    override fun accessibilityLineForIndex(index: Long): Long {
        val text = viewport.visibleText()
        if (index < 0 || index > text.length) return NSNotFound
        return text.take(index.toInt()).count { it == '\n' }.toLong()
    }
    override fun accessibilityStringForRange(range: CValue<NSRange>): String? {
        val text = viewport.visibleText()
        return range.useContents {
            if (location > text.length.toULong() || length > text.length.toULong() - location) return@useContents null
            val start = location.toInt(); val end = start + length.toInt()
            fun splitsSurrogate(index: Int): Boolean = index > 0 && index < text.length &&
                text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
            if (splitsSurrogate(start) || splitsSurrogate(end)) null else text.substring(start, end)
        }
    }
    override fun accessibilityAttributedStringForRange(range: CValue<NSRange>): NSAttributedString? =
        accessibilityStringForRange(range)?.let { NSAttributedString.create(string = it, attributes = mapOf(NSFontAttributeName to font)) }
    public fun dispose() { if (!disposed) {
        disposed = true; selecting = false; clearLinkPointer(); mouseReporter.reset()
        clipboardPaste.dispose()
        accessibilitySelectionTimer?.invalidate(); accessibilitySelectionTimer = null
        hoverWindowObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }; hoverWindowObservation = null
        focusWindowObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }; focusWindowObservation = null
        visibilityBinding.dispose()
        visibilityObservation?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }; visibilityObservation = null
        mouseTrackingArea?.let { removeTrackingArea(it) }; mouseTrackingArea = null
        resizeHoldObservation.dispose(); pendingGridSize = null
        sizeRefreshObservation.dispose()
        windowResizeBinding.dispose()
        dragAndDrop.dispose()
        pointerObservation.dispose(); linkPolicyObservation.dispose()
        progressIndicator.dispose()
        notificationObservation.dispose(); notifications?.dispose(); notifications = null
        clipboardBinding.dispose()
        mimeClipboardBinding.dispose()
        links.dispose()
        onViewportChanged = null; onScrollActivity = null
        viewport.dispose(); softwareImages.clear(); clearSoftwareGlyphs(); releaseGpu(); inputContext()?.discardMarkedText()
    } }
}

private fun nativeMouseButton(event: NSEvent): Int? = when (event.buttonNumber.toInt()) {
    2 -> 1; 3 -> 128; 4 -> 129; else -> null
}

// NSEvent's numeric-pad modifier also marks navigation keys. Hardware virtual key codes
// distinguish the actual keypad and preserve its decimal identity on non-US layouts.
private fun nativeKeypad(code: Int): TerminalKeypadKey? = when (code) {
    82 -> TerminalKeypadKey.ZERO; 83 -> TerminalKeypadKey.ONE; 84 -> TerminalKeypadKey.TWO
    85 -> TerminalKeypadKey.THREE; 86 -> TerminalKeypadKey.FOUR; 87 -> TerminalKeypadKey.FIVE
    88 -> TerminalKeypadKey.SIX; 89 -> TerminalKeypadKey.SEVEN; 91 -> TerminalKeypadKey.EIGHT
    92 -> TerminalKeypadKey.NINE; 65 -> TerminalKeypadKey.DECIMAL; 75 -> TerminalKeypadKey.DIVIDE
    67 -> TerminalKeypadKey.MULTIPLY; 78 -> TerminalKeypadKey.SUBTRACT; 69 -> TerminalKeypadKey.ADD
    76 -> TerminalKeypadKey.ENTER; 81 -> TerminalKeypadKey.EQUAL; 95 -> TerminalKeypadKey.SEPARATOR
    else -> null
}

internal fun terminalDroppedFilePaths(pasteboard: NSPasteboard): List<String> =
    pasteboard.readObjectsForClasses(listOf(NSURL.`class`()),
        mapOf(NSPasteboardURLReadingFileURLsOnlyKey to true)).orEmpty()
        .filterIsInstance<NSURL>().mapNotNull { if (it.fileURL) it.path else null }

private fun nativeKey(chars: String): String = when (chars.firstOrNull()?.code) {
    13, 10 -> "Enter"; 9 -> "Tab"; 25 -> "Tab"; 27 -> "Escape"; 127, 8 -> "Backspace"
    0xf700 -> "ArrowUp"; 0xf701 -> "ArrowDown"; 0xf702 -> "ArrowLeft"; 0xf703 -> "ArrowRight"
    in 0xf704..0xf726 -> "F${chars[0].code - 0xf704 + 1}"
    0xf727 -> "Insert"; 0xf728 -> "Delete"; 0xf729 -> "Home"; 0xf72b -> "End"; 0xf72c -> "PageUp"; 0xf72d -> "PageDown"
    else -> chars
}
