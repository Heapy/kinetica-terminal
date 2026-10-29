@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.math.abs

/** A view overlay so progress also works with the software renderer and GPU fallback. */
internal class TerminalNativeProgress(session: TerminalSession) : NSView(NSMakeRect(0.0, 0.0, 0.0, 4.0)) {
    private var animation: NSTimer? = null
    private var phase = 0.0
    internal val presentation = TerminalProgressPresentation(session, TerminalScheduler { delay, action ->
        val timer = NSTimer.timerWithTimeInterval(delay / 1000.0, repeats = false) { action() }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        TerminalDisposable { timer.invalidate() }
    }, ::refresh)

    init {
        hidden = true; autoresizingMask = NSViewWidthSizable
        setAccessibilityElement(true); setAccessibilityRole("AXProgressIndicator")
    }
    override fun isFlipped(): Boolean = true
    override fun hitTest(point: CValue<NSPoint>): NSView? = null
    override fun accessibilityLabel(): String = presentation.label
    override fun accessibilityValue(): Any? = presentation.percentage?.toDouble()
    override fun accessibilityMinValue(): Any? = 0.0
    override fun accessibilityMaxValue(): Any? = 100.0

    private fun refresh() {
        animation?.invalidate(); animation = null; phase = 0.0
        hidden = presentation.report == null
        if (!hidden && presentation.percentage == null) {
            animation = NSTimer.timerWithTimeInterval(1.0 / 30, repeats = true) {
                phase = (phase + 1.0 / 30) % 2
                setNeedsDisplay(true)
            }.also { NSRunLoop.mainRunLoop.addTimer(it, NSRunLoopCommonModes) }
        }
        setNeedsDisplay(true)
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        val report = presentation.report ?: return
        val color = when (report.state) {
            TerminalProgressState.ERROR -> NSColor.systemRedColor
            TerminalProgressState.PAUSE -> NSColor.systemOrangeColor
            else -> NSColor.controlAccentColor
        }
        color.colorWithAlphaComponent(0.2).setFill(); NSRectFill(bounds)
        bounds.useContents {
            val width = size.width * (presentation.percentage?.div(100.0) ?: 0.25)
            val x = if (presentation.percentage == null) (size.width - width) * (1 - abs(1 - phase)) else 0.0
            color.setFill(); NSRectFill(NSMakeRect(x, 0.0, width, size.height))
        }
    }

    fun dispose() { presentation.dispose(); animation?.invalidate(); animation = null; hidden = true; removeFromSuperview() }
}
