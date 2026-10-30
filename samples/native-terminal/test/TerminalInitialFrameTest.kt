@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*
import kotlin.time.TimeSource

class TerminalInitialFrameTest {
    @Test fun presentsShellOutputInTheApplicationWindow() {
        val app = NSApplication.sharedApplication()
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        app.finishLaunching()
        val contentFrames = mutableSetOf<Long>()
        var presented = false
        val owner = TerminalApplication(app, settings = TerminalSettingsStore(null),
            renderObserver = object : TerminalRenderObserver {
                override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {
                    if ("KINETICA_RENDER_READY" in viewport.visibleText()) contentFrames += frameId
                }
                override fun onFramePresented(frameId: Long, hostTimeSeconds: Double) {
                    if (hostTimeSeconds > 0 && contentFrames.remove(frameId)) presented = true
                }
            })
        val terminal = owner.open()
        terminal.window.level = NSFloatingWindowLevel
        terminal.window.orderFrontRegardless()
        fun pump() {
            app.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.01), NSDefaultRunLoopMode, true)?.let(app::sendEvent)
            app.updateWindows()
        }
        try {
            terminal.session.sendInput("printf 'KINETICA_RENDER_%s\\n' READY\r")
            val began = TimeSource.Monotonic.markNow()
            while (!presented && began.elapsedNow().inWholeSeconds < 10) pump()
            assertTrue(presented, "Shell output must reach the screen: visible=${terminal.window.visible}, " +
                "occlusion=${terminal.window.occlusionState}, backend=${terminal.view.renderingBackend}, failure=${terminal.view.renderingFailure}")
            assertEquals("metal", terminal.view.renderingBackend)
            assertNull(terminal.view.renderingFailure)
        } finally {
            owner.dispose()
            val began = TimeSource.Monotonic.markNow()
            while (owner.shell.closingWindowCount > 0 && began.elapsedNow().inWholeSeconds < 10) pump()
            terminal.window.close()
        }
    }
}
