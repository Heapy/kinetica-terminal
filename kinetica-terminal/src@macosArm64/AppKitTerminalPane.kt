@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

/** NSScrollView owns the private fading machinery of overlay-style NSScroller.
 * Use native legacy thumb/tracking with a transparent track when hosted independently. */
private class TerminalOverlayScroller : NSScroller(NSMakeRect(0.0, 0.0, 15.0, 480.0)) {
    var tracking = false
        private set
    override fun isOpaque(): Boolean = false
    override fun drawKnobSlotInRect(slotRect: CValue<NSRect>, highlight: Boolean) = Unit
    override fun mouseDown(event: NSEvent) {
        tracking = true
        try { super.mouseDown(event) } finally { tracking = false }
    }
}

/** Native controls overlay the terminal's Metal layer without changing its drawable width. */
public class AppKitTerminalPane(public val terminal: AppKitTerminalView) :
    NSView(NSMakeRect(0.0, 0.0, 800.0, 480.0)), NSTextFieldDelegateProtocol {
    private val overlayScroller = TerminalOverlayScroller()
    public val scroller: NSScroller get() = overlayScroller
    public val findField: NSSearchField = NSSearchField(NSMakeRect(8.0, 5.0, 250.0, 24.0))
    private val findBar = NSView(NSMakeRect(0.0, 0.0, 800.0, 34.0))
    private val countLabel = NSTextField.labelWithString("")
    private val previous = NSButton(NSMakeRect(0.0, 0.0, 28.0, 24.0))
    private val next = NSButton(NSMakeRect(0.0, 0.0, 28.0, 24.0))
    private val done = NSButton(NSMakeRect(0.0, 0.0, 54.0, 24.0))
    private val caseButton = NSButton(NSMakeRect(0.0, 0.0, 42.0, 24.0))
    private var results = TerminalSearchResults(emptyList(), false)
    public var resultIndex: Int = -1
        private set
    public val resultCount: Int get() = results.matches.size
    public val finding: Boolean get() = !findBar.hidden
    private var timer: NSTimer? = null
    private var scrollHideTimer: NSTimer? = null
    private var monitor: Any? = null
    private var ready = false
    private var disposed = false
    private val observation = terminal.session.observe { if (finding && findField.stringValue.isNotEmpty()) scheduleSearch(false) }

    init {
        wantsLayer = true
        addSubview(terminal); addSubview(scroller); addSubview(findBar)
        scroller.scrollerStyle = NSScrollerStyleLegacy
        scroller.wantsLayer = true; scroller.hidden = true
        scroller.target = this; scroller.action = NSSelectorFromString("scrollChanged:")
        scroller.setAccessibilityLabel("Terminal scrollback")
        findField.placeholderString = "Find in terminal"
        findField.delegate = this; findField.target = this; findField.action = NSSelectorFromString("findNext:")
        findField.setAccessibilityLabel("Find in terminal")
        for (control in listOf(findField, countLabel, previous, next, done, caseButton)) findBar.addSubview(control)
        fun button(button: NSButton, title: String, action: String, label: String) {
            button.title = title; button.bezelStyle = NSBezelStyleRounded
            button.target = this; button.action = NSSelectorFromString(action); button.setAccessibilityLabel(label)
        }
        button(previous, "‹", "findPrevious:", "Previous match")
        button(next, "›", "findNext:", "Next match")
        button(done, "Done", "hideFind:", "Close search")
        button(caseButton, "Aa", "caseChanged:", "Match case")
        caseButton.setButtonType(NSButtonTypePushOnPushOff)
        findBar.hidden = true
        terminal.onViewportChanged = ::updateScroll
        terminal.onScrollActivity = ::showScroller
        monitor = NSEvent.addLocalMonitorForEventsMatchingMask(NSEventMaskKeyDown) { event ->
            if (event != null && finding && window != null && event.window == window) {
                when (event.keyCode.toInt()) {
                    53 -> { hideFind(null); return@addLocalMonitorForEventsMatchingMask null }
                    36, 76 -> if (window?.firstResponder !== terminal) {
                        if (event.modifierFlags and NSEventModifierFlagShift != 0uL) findPrevious(null) else findNext(null)
                        return@addLocalMonitorForEventsMatchingMask null
                    }
                }
            }
            event
        }
        ready = true; layoutControls(); updateScroll()
    }

    override fun isFlipped(): Boolean = true
    override fun scrollWheel(event: NSEvent) { terminal.scrollWheel(event) }
    override fun setFrameSize(newSize: CValue<NSSize>) { super.setFrameSize(newSize); if (ready) layoutControls() }
    private fun layoutControls() = bounds.useContents {
        val barHeight = if (finding) 34.0 else 0.0
        val width = size.width.coerceAtLeast(1.0)
        terminal.setFrame(NSMakeRect(0.0, barHeight, width, (size.height - barHeight).coerceAtLeast(1.0)))
        scroller.setFrame(NSMakeRect(width - 15.0, barHeight, 15.0, (size.height - barHeight).coerceAtLeast(1.0)))
        findBar.setFrame(NSMakeRect(0.0, 0.0, width, 34.0))
        val fieldWidth = (width - 270.0).coerceAtLeast(80.0)
        findField.setFrame(NSMakeRect(8.0, 5.0, fieldWidth, 24.0))
        countLabel.setFrame(NSMakeRect(fieldWidth + 24.0, 8.0, 90.0, 20.0))
        caseButton.setFrame(NSMakeRect(width - 146.0, 5.0, 40.0, 24.0))
        previous.setFrame(NSMakeRect(width - 106.0, 5.0, 28.0, 24.0))
        next.setFrame(NSMakeRect(width - 78.0, 5.0, 28.0, 24.0))
        done.setFrame(NSMakeRect(width - 50.0, 5.0, 48.0, 24.0))
    }
    private fun updateScroll() {
        if (disposed) return
        val history = terminal.scrollbackLines
        scroller.enabled = history > 0
        scroller.knobProportion = terminal.session.rows.toDouble() / (history + terminal.session.rows)
        scroller.doubleValue = if (history == 0) 1.0 else 1.0 - terminal.scrollOffset.toDouble() / history
        val background = terminal.session.theme.background
        val luminance = (background ushr 16 and 255) * 299 + (background ushr 8 and 255) * 587 + (background and 255) * 114
        scroller.knobStyle = if (luminance < 128000) NSScrollerKnobStyleLight else NSScrollerKnobStyleDark
        if (history == 0) hideScroller()
    }
    private fun showScroller() {
        if (disposed || terminal.scrollbackLines == 0) return
        scroller.hidden = false
        scheduleScrollerHide()
    }
    private fun scheduleScrollerHide() {
        scrollHideTimer?.invalidate()
        scrollHideTimer = NSTimer.timerWithTimeInterval(1.0, repeats = false) {
            scrollHideTimer = null
            // Native scroller tracking runs a nested event loop. Never hide the control
            // underneath a held mouse button, even if the thumb stops moving briefly.
            if (overlayScroller.tracking) scheduleScrollerHide() else hideScroller()
        }.also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
    }
    private fun hideScroller() {
        scrollHideTimer?.invalidate(); scrollHideTimer = null
        scroller.hidden = true
    }
    @ObjCAction public fun scrollChanged(sender: NSObject?) {
        val offset = terminal.scrollOffset
        val page = (terminal.session.rows - 1).coerceAtLeast(1)
        val target = when (scroller.hitPart) {
            NSScrollerDecrementLine -> offset + 1
            NSScrollerIncrementLine -> offset - 1
            NSScrollerDecrementPage -> offset + page
            NSScrollerIncrementPage -> offset - page
            else -> ((1.0 - scroller.doubleValue) * terminal.scrollbackLines).roundToInt()
        }
        terminal.scrollTo(target); updateScroll(); showScroller()
    }
    @ObjCAction public fun showFind(sender: NSObject?) {
        if (disposed) return
        findBar.hidden = false; layoutControls()
        window?.makeFirstResponder(findField); findField.selectText(null)
        refreshSearch(true)
    }
    @ObjCAction public fun hideFind(sender: NSObject?) {
        timer?.invalidate(); timer = null
        findBar.hidden = true; terminal.clearSelection(); layoutControls(); window?.makeFirstResponder(terminal)
    }
    @ObjCAction public fun findNext(sender: NSObject?) {
        if (!finding) { showFind(sender); return }
        navigate(1)
    }
    @ObjCAction public fun findPrevious(sender: NSObject?) {
        if (!finding) { showFind(sender); return }
        navigate(-1)
    }
    @ObjCAction public fun caseChanged(sender: NSObject?) { refreshSearch(true) }
    override fun controlTextDidChange(obj: NSNotification) { scheduleSearch(true) }
    private fun scheduleSearch(reset: Boolean) {
        if (reset) { timer?.invalidate(); timer = null }
        if (timer != null || disposed) return
        timer = NSTimer.timerWithTimeInterval(0.15, repeats = false) { timer = null; refreshSearch(reset) }
            .also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
    }
    /** Also used by hosts that provide their own search controls. */
    public fun find(query: String, caseSensitive: Boolean = false) {
        findField.stringValue = query; caseButton.state = if (caseSensitive) NSControlStateValueOn else NSControlStateValueOff
        refreshSearch(true)
    }
    private fun refreshSearch(reset: Boolean) {
        timer?.invalidate(); timer = null
        results = terminal.session.search(findField.stringValue, caseButton.state == NSControlStateValueOn)
        resultIndex = if (results.matches.isEmpty()) -1 else if (reset) results.matches.lastIndex else resultIndex.coerceIn(0, results.matches.lastIndex)
        revealResult(showScroll = reset)
    }
    private fun navigate(direction: Int) {
        if (timer != null) refreshSearch(false)
        if (results.matches.isEmpty()) return
        resultIndex = (resultIndex + direction + results.matches.size) % results.matches.size
        revealResult()
    }
    private fun revealResult(showScroll: Boolean = true) {
        if (resultIndex >= 0) terminal.reveal(results.matches[resultIndex]) else terminal.clearSelection()
        if (showScroll && resultIndex >= 0) showScroller()
        countLabel.stringValue = if (findField.stringValue.isEmpty()) "" else
            "${resultIndex + 1} / ${results.matches.size}${if (results.truncated) "+" else ""}"
        next.enabled = resultCount > 0; previous.enabled = resultCount > 0
    }
    public fun dispose() {
        if (disposed) return
        disposed = true; observation.dispose(); timer?.invalidate(); timer = null
        hideScroller()
        monitor?.let { NSEvent.removeMonitor(it) }; monitor = null
        findField.delegate = null; terminal.onViewportChanged = null; terminal.onScrollActivity = null; terminal.dispose()
    }
}
