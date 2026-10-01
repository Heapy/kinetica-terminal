@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.QuartzCore.CACurrentMediaTime
import kotlinx.cinterop.useContents
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.*
import kotlin.time.TimeSource

class MacOsRenderObserverTest {
    @Test fun resizePreservesAnAtomicTuiFrameUntilTheApplicationFinishesWriting() {
        NSApplication.sharedApplication()
        val session = TerminalSession()
        val view = AppKitTerminalView(session, rendering = TerminalRendering.SOFTWARE)
        val resized = mutableListOf<Pair<Int, Int>>()
        val subscription = session.onResize { columns, rows -> resized += columns to rows }
        try {
            val initial = session.columns to session.rows
            session.write("COMPLETE\u001b[?2026h\u001b[2J\u001b[HPARTIAL")
            view.setFrameSize(NSMakeSize(800.1, 480.1))
            assertEquals(initial, session.columns to session.rows)
            assertTrue(session.synchronizedOutput, "Pixel-only resize must not expose PARTIAL")
            view.setFrameSize(NSMakeSize(1000.0, 600.0))
            view.setFrameSize(NSMakeSize(900.0, 500.0))
            assertEquals(initial, session.columns to session.rows)
            assertTrue(resized.isEmpty(), "Wait for the atomic output before notifying the PTY")
            session.write("\rFINISHED\u001b[?2026l")
            assertFalse(session.synchronizedOutput)
            assertEquals(1, resized.size, "Coalesce grid changes made during one synchronized frame")
            assertTrue(session.columns > initial.first)
            assertTrue(session.rows > initial.second)
            assertEquals("FINISHED", session.screenLine(0).text())
            view.setFrameSize(NSMakeSize(900.0, 500.0))
            assertEquals(1, resized.size)
        } finally { subscription.dispose(); view.dispose() }
    }

    @Test fun retainsTextDuringContinuousWindowResize() {
        val app = NSApplication.sharedApplication()
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        val tui = getenv("KINETICA_WINDOW_TUI")?.toKString()
        val resizeSteps = (getenv("KINETICA_WINDOW_RESIZE_STEPS")?.toKString()?.toInt() ?: 360).also { require(it in 60..3600) }
        val directory = getenv("KINETICA_WINDOW_CAPTURE_DIR")?.toKString()
        val session = TerminalSession(80, 24)
        if (tui == null) session.write("\u001b[?25l\u001b[38;2;0;255;0mFIXED WIDTH 0123456789")
        var completed = 0
        var recording = false
        val frames = mutableListOf<Map<String, Any>>()
        val changes = mutableListOf<Map<String, Any>>()
        fun statistics(text: String) = mapOf("characters" to text.count { !it.isWhitespace() },
            "nonblankRows" to text.lineSequence().count { it.isNotBlank() })
        val observation = session.observe {
            if (recording) changes += mapOf("time" to CACurrentMediaTime(), "columns" to session.columns,
                "rows" to session.rows, "sync" to session.synchronizedOutput) +
                statistics((0 until session.rows).joinToString("\n") { session.screenLine(it).text() })
        }
        val software = getenv("KINETICA_WINDOW_SOFTWARE")?.toKString() == "1"
        val view = AppKitTerminalView(session, rendering = if (software) TerminalRendering.SOFTWARE else TerminalRendering.GPU, renderObserver = object : TerminalRenderObserver {
            override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {
                if (recording) frames += mapOf("time" to CACurrentMediaTime(), "id" to frameId,
                    "columns" to viewport.columns, "rows" to viewport.rows, "sync" to session.synchronizedOutput) + statistics(viewport.visibleText())
            }
            override fun onFrameSubmitted(frame: TerminalFrameMetrics) { if (software) completed++ }
            override fun onGpuTime(frameId: Long, milliseconds: Double?) { completed++ }
        })
        val window = NSWindow(NSMakeRect(40.0, 40.0, if (tui == null) 640.0 else 900.0, if (tui == null) 240.0 else 500.0),
            NSWindowStyleMaskTitled or NSWindowStyleMaskResizable, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        window.title = "Kinetica continuous resize verification (isolated)"
        window.level = NSFloatingWindowLevel
        window.backgroundColor = NSColor.magentaColor
        val screen = NSScreen.screens.filterIsInstance<NSScreen>().maxBy { it.maximumFramesPerSecond }
        // Leave room below the top edge for the largest frame: setContentSize keeps
        // the top edge fixed and must not grow this capture fixture off the display.
        window.setFrameOrigin(screen.visibleFrame.useContents { NSMakePoint(origin.x + 40, origin.y + size.height - 700) })
        val pty = tui?.let {
            val executable = requireNotNull(getenv("KINETICA_WINDOW_TUI_EXECUTABLE")?.toKString())
            MacOsPty(session, executable = executable, arguments = when (it) {
                "htop" -> listOf("--readonly", "--pid", NSProcessInfo.processInfo.processIdentifier.toString(), "--delay=1")
                "claude" -> listOf("--safe-mode", "--no-chrome", "--tools", "", "--permission-mode", "manual")
                else -> error("Unknown TUI fixture: $it")
            }, workingDirectory = getenv("KINETICA_WINDOW_TUI_DIRECTORY")?.toKString() ?: requireNotNull(directory),
                environment = mapOf("HTOPRC" to "$directory/htoprc", "LANG" to "en_US.UTF-8"))
        }
        fun signal(name: String, values: Map<String, Any>) {
            if (directory == null) return
            require(directory.startsWith('/'))
            val data = checkNotNull(NSJSONSerialization.dataWithJSONObject(values, NSJSONWritingPrettyPrinted, null))
            check(data.writeToFile("$directory/$name", true))
        }
        try {
            runWindowSteps(app, { "completed=$completed" }, sequence {
                window.orderFrontRegardless()
                yield("Initial visible frame" to { completed > 0 && window.occlusionState and NSWindowOcclusionStateVisible != 0uL })
                if (tui != null) {
                    var declinedImports = false
                    var selectedFixture = false
                    var trustedFixture = false
                    var startupInputAfter = CACurrentMediaTime() + 0.5
                    yield("TUI startup" to {
                        val text = (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }
                        signal("tui-startup.json", mapOf("text" to text))
                        if (tui == "htop") "PID" in text else {
                            val canSend = CACurrentMediaTime() >= startupInputAfter
                            if (canSend && !declinedImports && "❯ No, disable external imports" in text) {
                                declinedImports = true; session.sendInput("\r")
                                startupInputAfter = CACurrentMediaTime() + 0.5
                            }
                            if (canSend && "❯ No, exit" in text && "Yes, I trust this folder" in text) {
                                // The driver creates this empty, disposable directory. No prompt
                                // is submitted and tools are disabled for this UI-only fixture.
                                check(getenv("KINETICA_WINDOW_TUI_DIRECTORY")?.toKString()?.contains("/kinetica-tui-resize-") == true)
                                selectedFixture = true; session.sendInput("\u001b[B")
                                startupInputAfter = CACurrentMediaTime() + 0.5
                            }
                            if (canSend && selectedFixture && !trustedFixture && "❯ Yes, I trust this folder" in text) {
                                trustedFixture = true; session.sendInput("\r")
                                startupInputAfter = CACurrentMediaTime() + 0.5
                            }
                            "Claude Code" in text && "? for shortcuts" in text
                        }
                    })
                    val until = CACurrentMediaTime() + 2.0
                    yield("TUI initial frame settled" to { CACurrentMediaTime() >= until })
                    signal("tui-initial.json", mapOf("text" to (0 until session.rows).joinToString("\n") { session.screenLine(it).text() }))
                }
                // Replay AppKit's live-resize lifecycle around programmatic geometry
                // changes, without taking control of the user's mouse.
                view.viewWillStartLiveResize()
                signal("resize_stream.json", mapOf("phase" to "resize_stream", "window" to window.windowNumber,
                    "expected" to "FIXED WIDTH 0123456789", "visible" to true, "backend" to view.renderingBackend,
                    "tui" to (tui ?: ""), "steps" to resizeSteps))
                yield("Window recorder ready" to { directory == null || NSFileManager.defaultManager.fileExistsAtPath("$directory/resize_stream.ready") })
                recording = true
                val start = CACurrentMediaTime()
                var step = 0
                yield("Continuous resize" to {
                    if (step < resizeSteps && CACurrentMediaTime() - start >= step / 60.0) {
                        val wave = if (step % 120 < 60) step % 60 else 60 - step % 60
                        window.setContentSize(if (tui == null) NSMakeSize(540.0 + wave * 6, 200.0 + wave * 3)
                            else NSMakeSize(740.0 + wave * 4, 400.0 + wave * 3))
                        step++
                    }
                    step == resizeSteps
                })
                view.viewDidEndLiveResize()
                recording = false
                signal("tui-frames.json", mapOf("frames" to frames, "changes" to changes, "tui" to (tui ?: "")))
                signal("resize_stream.done", mapOf("steps" to step, "completed" to completed))
                yield("Window recorder verified" to { directory == null || NSFileManager.defaultManager.fileExistsAtPath("$directory/resize_stream.ack") })
                assertTrue(completed > 10, "Resize must continue drawing")
                if (tui != null) assertTrue(frames.map { it["columns"] }.distinct().size > 10,
                    "The TUI must present updated grids, not freeze its initial frame for the whole drag")
                if (tui == null) assertEquals("FIXED WIDTH 0123456789", session.screenLine(0).text())
            }, stepTimeoutSeconds = resizeSteps / 60.0 + 10)
        } finally { observation.dispose(); pty?.dispose(); view.dispose(); window.orderOut(null); window.close() }
    }

    @Test fun keepsGlyphSizeAcrossResizeAndWhileNextFrameIsPending() {
        val app = NSApplication.sharedApplication()
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        val session = TerminalSession(80, 24)
        val marker = "FIXED WIDTH 0123456789"
        session.write("\u001b[?25l\u001b[38;2;0;255;0m$marker")
        val viewport = TerminalViewport(session, {})
        var completed = 0
        val observer = object : TerminalRenderObserver {
            override fun onGpuTime(frameId: Long, milliseconds: Double?) { completed++ }
        }
        val renderer = MetalTerminalDrawing(TerminalRenderProbe(observer)) {}
        val host = NSView(NSMakeRect(0.0, 0.0, 640.0, 240.0))
        host.wantsLayer = true
        host.layer!!.addSublayer(renderer.layer)
        val window = NSWindow(NSMakeRect(40.0, 40.0, 640.0, 240.0),
            NSWindowStyleMaskTitled or NSWindowStyleMaskResizable, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = host
        window.title = "Kinetica resize verification (isolated)"
        window.level = NSFloatingWindowLevel
        // A matching host background would conceal gaps around the retained drawable.
        window.backgroundColor = NSColor.magentaColor
        val screen = NSScreen.screens.filterIsInstance<NSScreen>().maxBy { it.maximumFramesPerSecond }
        window.setFrameOrigin(screen.visibleFrame.useContents { NSMakePoint(origin.x + 40, origin.y + 40) })
        val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
        val cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
        val cellHeight = kotlin.math.ceil(font.ascender - font.descender + font.leading + 2.0)
        fun resize(width: Double, height: Double) {
            window.setContentSize(NSMakeSize(width, height))
            renderer.resize(width, height, window.backingScaleFactor, 14.0, cellWidth, cellHeight)
        }
        fun delay(): () -> Boolean {
            val until = CACurrentMediaTime() + 0.2
            return { CACurrentMediaTime() >= until }
        }
        val captureDirectory = getenv("KINETICA_WINDOW_CAPTURE_DIR")?.toKString()
        fun capture(phase: String): () -> Boolean {
            val directory = captureDirectory ?: return { true }
            require(directory.startsWith('/'))
            val data = checkNotNull(NSJSONSerialization.dataWithJSONObject(mapOf(
                "phase" to phase, "window" to window.windowNumber, "expected" to marker,
                "visible" to (window.occlusionState and NSWindowOcclusionStateVisible != 0uL),
                "scale" to window.backingScaleFactor, "completed" to completed,
                "contentWidth" to window.contentView!!.bounds.useContents { size.width },
                "contentHeight" to window.contentView!!.bounds.useContents { size.height },
                "backgroundRGB" to viewport.theme.background,
            ), NSJSONWritingPrettyPrinted, null))
            check(data.writeToFile("$directory/$phase.json", true))
            return { NSFileManager.defaultManager.fileExistsAtPath("$directory/$phase.ack") }
        }
        var view: AppKitTerminalView? = null
        try {
            runWindowSteps(app, { "GPU completions=$completed" }, sequence {
                window.orderFrontRegardless()
                yield("Visible window" to { window.occlusionState and NSWindowOcclusionStateVisible != 0uL })
                resize(640.0, 240.0)
                renderer.draw(viewport)
                yield("Initial GPU frame" to { completed > 0 })
                yield("Initial frame settled" to delay())
                yield("Initial pixels" to capture("resize_initial"))
                for ((name, size) in listOf("wide" to (960.0 to 360.0), "narrow" to (480.0 to 180.0))) {
                    val submitted = renderer.submittedFrames
                    resize(size.first, size.second)
                    // Hold the previous drawable deterministically, as happens while a GPU
                    // frame is in flight. Offscreen readback cannot detect compositor stretching.
                    yield("Resize before next frame" to delay())
                    assertEquals(submitted, renderer.submittedFrames)
                    yield("Retained drawable pixels" to capture("resize_held_$name"))
                    val before = completed
                    viewport.markAll()
                    renderer.draw(viewport)
                    yield("Resized GPU frame" to { completed > before })
                    yield("Resized frame settled" to delay())
                    yield("New drawable pixels" to capture("resize_drawn_$name"))
                }
                renderer.dispose()
                view = AppKitTerminalView(session, rendering = TerminalRendering.GPU, renderObserver = observer)
                window.contentView = view
                // Also exercise actual NSView measurement, grid resize and redraw scheduling.
                for ((name, size) in listOf("initial" to (640.0 to 240.0), "wide" to (960.0 to 360.0), "narrow" to (480.0 to 180.0))) {
                    val before = completed
                    window.setContentSize(NSMakeSize(size.first, size.second))
                    yield("AppKit resized frame" to { completed > before })
                    yield("AppKit frame settled" to delay())
                    assertEquals(kotlin.math.floor(size.first / cellWidth).toInt(), session.columns)
                    assertEquals(marker, session.screenLine(0).text())
                    yield("AppKit resized pixels" to capture("resize_appkit_$name"))
                }
            })
        } finally { view?.dispose(); renderer.dispose(); viewport.dispose(); window.orderOut(null); window.close() }
    }

    @Test fun retainsLastCompletedFrameAcrossOcclusionWithoutFurtherWrites() {
        val app = NSApplication.sharedApplication()
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
        val session = TerminalSession(80, 24)
        val frameText = mutableMapOf<Long, String>()
        val presentations = mutableListOf<Pair<String, Double>>()
        var submitted = 0
        var completed = 0
        val completedFrames = mutableSetOf<Long>()
        val observer = object : TerminalRenderObserver {
            override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {
                frameText[frameId] = viewport.visibleText().lineSequence().first()
            }
            override fun onFrameSubmitted(frame: TerminalFrameMetrics) { submitted++ }
            override fun onGpuTime(frameId: Long, milliseconds: Double?) { completed++; completedFrames += frameId }
            override fun onFramePresented(frameId: Long, hostTimeSeconds: Double) {
                presentations += (frameText[frameId].orEmpty() to hostTimeSeconds)
            }
        }
        val view = AppKitTerminalView(session, rendering = TerminalRendering.GPU, renderObserver = observer)
        var metalLayer: platform.QuartzCore.CAMetalLayer? = null
        val window = NSWindow(NSMakeRect(40.0, 40.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        window.title = "Kinetica window verification (isolated)"
        // Keep unrelated normal application windows out of this visibility test.
        window.level = NSFloatingWindowLevel
        val screen = NSScreen.screens.filterIsInstance<NSScreen>().maxBy { it.maximumFramesPerSecond }
        window.setFrameOrigin(screen.visibleFrame.useContents { NSMakePoint(origin.x + 40, origin.y + 40) })
        val cover = NSWindow(window.frame.useContents {
            NSMakeRect(origin.x - 32, origin.y - 32, size.width + 64, size.height + 64)
        }, NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
        cover.setReleasedWhenClosed(false)
        cover.opaque = true
        cover.backgroundColor = NSColor.blackColor
        cover.level = window.level + 1
        fun visible() = window.occlusionState and NSWindowOcclusionStateVisible != 0uL
        val visibilityEvents = mutableListOf<String>()
        val visibilityObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            NSWindowDidChangeOcclusionStateNotification, window, NSOperationQueue.mainQueue,
        ) {
            visibilityEvents += "visible=${visible()}, dirty=${view.needsDisplay}, attached=${view.window == window}, " +
                "submitted=$submitted, model=${session.screenLine(0).text()}"
        }
        fun diagnostics() = "visible=${visible()}, ordered=${window.visible}, cover=${cover.visible}, " +
                "activeSpace=${window.onActiveSpace}, metalAttached=${metalLayer?.let { it == view.layer }}, " +
                "appHidden=${app.hidden}, started=${frameText.size}, submitted=$submitted, " +
                "windowFrame=${NSStringFromRect(window.frame)}, coverFrame=${NSStringFromRect(cover.frame)}, " +
                "completed=$completed, lastPresentations=${presentations.takeLast(5)}, model=${session.screenLine(0).text()}, " +
                "visibilityEvents=$visibilityEvents"
        fun delay(seconds: Double): () -> Boolean {
            val until = CACurrentMediaTime() + seconds
            return { CACurrentMediaTime() >= until }
        }
        fun completedText(text: String) = completedFrames.any { frameText[it] == text }
        fun drained(): () -> Boolean {
            var previous = submitted
            var since = CACurrentMediaTime()
            return {
                if (previous != submitted || completed < submitted) { previous = submitted; since = CACurrentMediaTime() }
                completed == submitted && CACurrentMediaTime() - since >= 0.5
            }
        }
        val captureDirectory = getenv("KINETICA_WINDOW_CAPTURE_DIR")?.toKString()
        fun capture(phase: String, expected: String): () -> Boolean {
            val directory = captureDirectory ?: return { true }
            require(directory.startsWith('/'))
            val data = checkNotNull(NSJSONSerialization.dataWithJSONObject(mapOf(
                "phase" to phase, "window" to window.windowNumber, "expected" to expected,
                "visible" to visible(), "submitted" to submitted, "completed" to completed,
                "frames" to frameText.map { (id, text) -> mapOf("id" to id, "text" to text, "completed" to (id in completedFrames)) },
                "presentations" to presentations.map { (text, time) -> mapOf("text" to text, "time" to time) },
            ), NSJSONWritingPrettyPrinted, null))
            check(data.writeToFile("$directory/$phase.json", true))
            return { NSFileManager.defaultManager.fileExistsAtPath("$directory/$phase.ack") }
        }
        try {
            runWindowSteps(app, ::diagnostics, sequence {
                session.write("\u001b[?25l\u001b[2J\u001b[HBEFORE")
                window.orderFrontRegardless()
                yield("Initial frame" to { visible() && completedText("BEFORE") })
                // GPU completion precedes WindowServer ordering. Let the initial
                // visible window settle before immediately covering it; the external
                // pixel verifier naturally introduces this delay while capturing.
                yield("Initial window settled" to delay(0.5))
                yield("Capture initial window" to capture("initial", "BEFORE"))
                metalLayer = view.layer as platform.QuartzCore.CAMetalLayer
                // Floating fixtures can establish coverage without stealing focus.
                // Activation is asynchronous and can be denied while the user works
                // in another app; it is not part of the rendering contract.
                // Covering and ordering out exercise different AppKit invalidation paths.
                for (hide in listOf(false, true)) {
                    if (hide) window.orderOut(null) else {
                        cover.setFrame(window.frame.useContents {
                            NSMakeRect(origin.x - 32, origin.y - 32, size.width + 64, size.height + 64)
                        }, true)
                        cover.orderFrontRegardless()
                    }
                    yield("Window occluded (hide=$hide)" to { !visible() })
                    val loadUntil = CACurrentMediaTime() + 0.5
                    yield("Output while occluded" to {
                        if (CACurrentMediaTime() < loadUntil) {
                            session.write("load 世界 é 😀\r\n".repeat(128)); false
                        } else true
                    })
                    val marker = if (hide) "AFTER HIDE" else "AFTER COVER"
                    session.write("\u001b[2J\u001b[H$marker")
                    yield("Drain hidden output and pending window updates" to drained())
                    assertEquals(marker, session.screenLine(0).text())
                    val hiddenIdle = submitted
                    yield("Occluded idle window" to delay(0.2))
                    assertEquals(hiddenIdle, submitted, "An occluded idle terminal must not keep retrying")
                    if (hide) window.orderFrontRegardless() else cover.orderOut(null)
                    // No writes, forced display, resize or cursor animation may rescue a lost redraw.
                    // A cached surface may be reused on uncover without a new drawable
                    // presentation callback. The optional verifier checks WindowServer pixels.
                    yield("Latest completed output after restore (hide=$hide)" to {
                        visible() && completedText(marker)
                    })
                    yield("Drain restored frame and pending window updates" to drained())
                    yield("Capture restored window" to capture(if (hide) "after_hide" else "after_cover", marker))
                    val idle = submitted
                    yield("Idle window" to delay(0.2))
                    assertEquals(idle, submitted, "Restored idle terminal must stop drawing")
                }
            })
        } finally {
            NSNotificationCenter.defaultCenter.removeObserver(visibilityObserver)
            view.dispose(); cover.orderOut(null); cover.close(); window.orderOut(null); window.close()
        }
    }

    @Test fun observesVisibleFramesAndStopsAfterDisposalAcrossHideAndResize() {
        val app = NSApplication.sharedApplication()
        val session = TerminalSession(80, 24)
        val submitted = mutableSetOf<Long>()
        val completed = mutableSetOf<Long>()
        val presented = mutableSetOf<Long>()
        var maxInFlight = 0
        var callbacks = 0
        var frameStarts = 0
        var frameText = ""
        val observer = object : TerminalRenderObserver {
            override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) { frameStarts++; frameText = viewport.visibleText() }
            override fun onFrameSubmitted(frame: TerminalFrameMetrics) {
                callbacks++; submitted += frame.frameId
                maxInFlight = maxOf(maxInFlight, submitted.count { it !in completed })
                assertTrue(frame.cpuMillis.isFinite() && frame.cpuMillis >= 0)
            }
            override fun onGpuTime(frameId: Long, milliseconds: Double?) {
                assertTrue(NSThread.isMainThread); callbacks++; completed += frameId
            }
            override fun onFramePresented(frameId: Long, hostTimeSeconds: Double) {
                assertTrue(NSThread.isMainThread); callbacks++; presented += frameId
            }
        }
        val view = AppKitTerminalView(session, rendering = TerminalRendering.GPU, renderObserver = observer)
        session.write("\u001b[?25l")
        val window = NSWindow(NSMakeRect(20.0, 20.0, 800.0, 480.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
        window.setReleasedWhenClosed(false); window.contentView = view
        window.orderFrontRegardless()
        fun pump() {
            app.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.005), NSDefaultRunLoopMode, true)
                ?.let { app.sendEvent(it) }
            app.updateWindows()
        }
        fun await(condition: () -> Boolean) {
            val start = TimeSource.Monotonic.markNow()
            while (!condition() && start.elapsedNow().inWholeSeconds < 10) pump()
            assertTrue(condition(), "Metal presentation did not progress: submitted=${submitted.size}, completed=${completed.size}, presented=${presented.size}")
        }
        val timer = NSTimer.timerWithTimeInterval(0.002, repeats = true) { session.write("frame 界\r\n".repeat(8)) }
        NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
        try {
            await { presented.size >= 12 }
            assertEquals(1, maxInFlight, "Reusable buffers must not have concurrent GPU users")
            window.orderOut(null)
            repeat(10) { pump() }
            val before = presented.size
            window.setContentSize(NSMakeSize(900.0, 520.0))
            window.orderFrontRegardless()
            session.write("AFTER RESTORE\r\n")
            await { presented.size >= before + 5 }
            assertEquals(1, maxInFlight)
            timer.invalidate()
            fun pumpFor(millis: Long) {
                val start = TimeSource.Monotonic.markNow()
                while (start.elapsedNow().inWholeMilliseconds < millis) pump()
            }
            // Completion/presentation delivery can trail window restoration. Check
            // new frame requests, not late observer callbacks for existing frames.
            await { completed.containsAll(submitted) }
            pumpFor(500)
            val idle = frameStarts
            pumpFor(200)
            assertEquals(idle, frameStarts, "An idle terminal must stop requesting frames")
            session.write("\u001b[2J\u001b[HOLD")
            await { frameText.startsWith("OLD") }
            val beforeHold = frameStarts
            session.write("\u001b[?2026h\u001b[2J\u001b[HNEW")
            view.setNeedsDisplay(true); view.displayIfNeeded()
            await { frameStarts > beforeHold }
            assertEquals("NEW", session.screenLine(0).text())
            assertTrue(frameText.startsWith("OLD"), "Capture the held viewport, not newer session content")
            session.write("\u001b[?2026l")
            await { frameText.startsWith("NEW") }
            // Pending GPU/presentation callbacks may arrive after disposal but must not call the observer.
            view.dispose()
            val atDispose = callbacks
            pumpFor(200)
            assertEquals(atDispose, callbacks)
        } finally { timer.invalidate(); view.dispose(); window.orderOut(null); window.close() }
    }
}

/** Exercise AppKit's actual event loop rather than approximating its window/transaction handling. */
private fun runWindowSteps(app: NSApplication, diagnostics: () -> String, steps: Sequence<Pair<String, () -> Boolean>>, stepTimeoutSeconds: Double = 10.0) {
    val iterator = steps.iterator()
    var step: Pair<String, () -> Boolean>? = null
    var deadline = 0.0
    var failure: Throwable? = null
    lateinit var timer: NSTimer
    fun stop() {
        timer.invalidate()
        app.stop(null)
        // stop() from a timer takes effect only after dispatching an actual NSEvent.
        app.postEvent(checkNotNull(NSEvent.otherEventWithType(NSEventTypeApplicationDefined,
            NSMakePoint(0.0, 0.0), 0u, 0.0, 0, null, 0, 0, 0)), false)
    }
    timer = NSTimer.timerWithTimeInterval(0.005, repeats = true) {
        try {
            if (step == null) {
                if (iterator.hasNext()) { step = iterator.next(); deadline = CACurrentMediaTime() + stepTimeoutSeconds }
                else stop()
            }
            step?.let { current ->
                if (current.second()) step = null
                else check(CACurrentMediaTime() < deadline) { "${current.first}: ${diagnostics()}" }
            }
        } catch (error: Throwable) { failure = error; stop() }
    }
    NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
    try { app.run() } finally { timer.invalidate() }
    failure?.let { throw it }
    check(step == null && !iterator.hasNext()) { "AppKit stopped before the test completed" }
}
