@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.native.terminal

import io.heapy.kinetica.terminal.*
import kotlinx.cinterop.*
import platform.AppKit.*
import platform.Foundation.*
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.QuartzCore.CACurrentMediaTime
import kotlin.math.ceil

/** Own isolated window/PTY; never attaches to or closes an existing terminal application. */
internal fun benchmarkRenderer(args: Array<String>) {
    require(args.size == 3) { "Usage: native-terminal --benchmark-renderer /absolute/terminal-render-load.py /absolute/report.json" }
    require(args[1].startsWith('/') && args[2].startsWith('/'))
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    application.finishLaunching()
    val session = TerminalSession()
    var readyAt = 0.0
    val inputs = mutableMapOf<Int, Double>()
    val inputPresented = mutableMapOf<Int, Double>()
    val inputSubmitted = mutableMapOf<Int, Double>()
    val frames = mutableMapOf<Long, RenderSample>()
    val marker = Regex("INPUT:([0-9]{6})")
    val observer = object : TerminalRenderObserver {
        override fun onFrameStarted(frameId: Long, viewport: TerminalViewport) {
            val now = CACurrentMediaTime()
            if (readyAt == 0.0 || now < readyAt + 2) return
            check(frames.size < 20000) { "Frame sample limit exceeded" }
            val input = marker.find(viewport.visibleText().lineSequence().first())?.groupValues?.get(1)?.toInt()
            frames[frameId] = RenderSample(now, input, now < readyAt + 8)
        }
        override fun onFrameSubmitted(frame: TerminalFrameMetrics) {
            val sample = frames[frame.frameId] ?: return
            sample.cpu = frame
            sample.submittedAt = CACurrentMediaTime()
            sample.input?.let { input -> inputs[input]?.let { sent ->
                if (input !in inputSubmitted) inputSubmitted[input] = (sample.submittedAt - sent) * 1000
            } }
        }
        override fun onGpuTime(frameId: Long, milliseconds: Double?) { frames[frameId]?.gpu = milliseconds }
        override fun onFramePresented(frameId: Long, hostTimeSeconds: Double) {
            check(NSThread.isMainThread)
            val sample = frames[frameId] ?: return
            sample.presentedAt = hostTimeSeconds
            if (hostTimeSeconds <= 0) return
            check(hostTimeSeconds >= sample.startedAt) { "Presentation clock predates frame preparation" }
            sample.input?.let { input -> inputs[input]?.let { sent ->
                val latency = (hostTimeSeconds - sent) * 1000
                inputPresented[input] = minOf(inputPresented[input] ?: Double.POSITIVE_INFINITY, latency)
            } }
        }
    }
    val view = AppKitTerminalView(session, rendering = TerminalRendering.GPU, renderObserver = observer)
    val font = NSFont.monospacedSystemFontOfSize(14.0, NSFontWeightRegular)
    val cellWidth = NSAttributedString.create(string = "M", attributes = mapOf(NSFontAttributeName to font)).size().useContents { width }
    val cellHeight = ceil(font.ascender - font.descender + font.leading + 2.0)
    val window = NSWindow(NSMakeRect(60.0, 60.0, ceil(cellWidth * 120), cellHeight * 32),
        NSWindowStyleMaskTitled, NSBackingStoreBuffered, false)
    window.releasedWhenClosed = false
    window.title = "Kinetica renderer benchmark (isolated)"
    window.contentView = view
    val targetScreen = NSScreen.screens.filterIsInstance<NSScreen>().maxBy { it.maximumFramesPerSecond }
    // NSWindow's initial screen can follow application activation. Pin every comparison to
    // the same fastest connected display and record it; never compare 60 Hz and 120 Hz runs.
    window.setFrameOrigin(targetScreen.visibleFrame.useContents { NSMakePoint(origin.x + 40, origin.y + 40) })
    window.makeFirstResponder(view)
    window.orderFrontRegardless()
    var exitCode: Int? = null
    val pty = MacOsPty(session, "/usr/bin/python3", listOf(args[1], "--seconds", "10", "--mib-per-second", "4"),
        onExit = { exitCode = it })
    val began = CACurrentMediaTime()
    val visibility = mutableListOf<Map<String, Any>>()
    var lastOcclusion: ULong? = null
    var occludedDuringMeasurement = false
    var nextInput = Double.POSITIVE_INFINITY
    val timer = NSTimer.timerWithTimeInterval(0.005, repeats = true) {
        val now = CACurrentMediaTime()
        val occlusion = window.occlusionState
        if (occlusion != lastOcclusion && visibility.size < 1024) {
            visibility += mapOf("at" to now, "state" to occlusion.toLong(),
                "visible" to (occlusion and NSWindowOcclusionStateVisible != 0uL))
            lastOcclusion = occlusion
        }
        if (readyAt > 0 && now >= readyAt + 2 && now < readyAt + 8 && occlusion and NSWindowOcclusionStateVisible == 0uL)
            occludedDuringMeasurement = true
        if (readyAt == 0.0 && session.screenLine(0).text().startsWith("READY")) {
            readyAt = now; nextInput = now + 2
        }
        if (now >= nextInput && now < readyAt + 8) {
            val id = inputs.size + 1
            inputs[id] = CACurrentMediaTime()
            // The real AppKit committed-text entry point, then a real PTY echo.
            view.insertText("M${id.toString().padStart(6, '0')}\r", NSMakeRange(NSNotFound.toULong(), 0u))
            nextInput = now + 0.125
        }
    }
    NSRunLoop.mainRunLoop.addTimer(timer, NSRunLoopCommonModes)
    try {
        fun pump() = autoreleasepool {
            application.nextEventMatchingMask(NSEventMaskAny, NSDate.dateWithTimeIntervalSinceNow(0.005), NSDefaultRunLoopMode, true)
                ?.let { application.sendEvent(it) }
            application.updateWindows()
        }
        while (exitCode == null && CACurrentMediaTime() - began < 25) pump()
        val drainUntil = CACurrentMediaTime() + 1
        while (CACurrentMediaTime() < drainUntil) pump()
        val measured = frames.values.filter { it.measured && it.cpu != null }
        val presented = measured.map { it.presentedAt }.filter { it > 0 }.sorted()
        val intervals = presented.zipWithNext { a, b -> (b - a) * 1000 }
        val failures = buildList {
            if (exitCode != 0) add("Load PTY failed/timed out: exit=$exitCode")
            if (measured.size <= 30 || presented.size <= 30) add("Insufficient submitted/presented frames")
            if (measured.none { it.gpu != null }) add("No GPU execution timings")
            if (inputs.size < 20) add("Insufficient input samples")
            if (inputPresented.keys != inputs.keys) add("Unpresented input echoes: ${inputs.keys - inputPresented.keys}")
            if (pty.rejectedInputCount != 0L || pty.pendingInputBytes != 0) add("Rejected or pending input")
            if (occludedDuringMeasurement) add("Benchmark window was occluded during measurement")
        }
        val report = mapOf(
            "valid" to failures.isEmpty(), "failures" to failures, "visibility" to visibility,
            "readyAt" to readyAt, "lastEcho" to session.screenLine(0).text(),
            "inputs" to inputs.mapKeys { it.key.toString() },
            "inputSubmissions" to inputSubmitted.mapKeys { it.key.toString() },
            "inputPresentations" to inputPresented.mapKeys { it.key.toString() },
            "engine" to "Kotlin/Native Metal", "os" to NSProcessInfo.processInfo.operatingSystemVersionString,
            "scheduling" to "appkit",
            "device" to (MTLCreateSystemDefaultDevice()?.name ?: "unknown"),
            "screenName" to (window.screen?.localizedName ?: "unknown"),
            "screenNumber" to (window.screen?.deviceDescription?.get("NSScreenNumber") ?: "unknown"),
            "applicationKeyWindow" to (application.keyWindow == window),
            "screenMaxFramesPerSecond" to (window.screen?.maximumFramesPerSecond ?: 0),
            "scale" to window.backingScaleFactor, "columns" to session.columns, "rows" to session.rows,
            "producerMiBPerSecond" to 4, "producerSeconds" to 10, "warmupSeconds" to 2, "measureSeconds" to 6,
            "scope" to "real PTY; AppKit committed text to drawable presentedTime; excludes hardware keyboard latency",
            "receivedBytes" to pty.receivedBytes, "readOperations" to pty.readOperations,
            "framesSubmitted" to measured.size, "framesPresented" to presented.size,
            "gpuSamples" to measured.count { it.gpu != null }, "inputSamples" to inputs.size,
            "presentedFramesPerSecond" to (if (presented.size > 1) (presented.size - 1) / (presented.last() - presented.first()) else 0.0),
            "cpuMillis" to distribution(measured.map { it.cpu!!.cpuMillis }),
            "acquireMillis" to distribution(measured.map { it.cpu!!.acquireMillis }),
            "layoutMillis" to distribution(measured.map { it.cpu!!.layoutMillis }),
            "atlasMillis" to distribution(measured.map { it.cpu!!.atlasMillis }),
            "batchMillis" to distribution(measured.map { it.cpu!!.batchMillis }),
            "submitMillis" to distribution(measured.map { it.cpu!!.submitMillis }),
            "gpuMillis" to distribution(measured.mapNotNull { it.gpu }),
            "presentedIntervalMillis" to distribution(intervals),
            "inputToSubmittedMillis" to distribution(inputSubmitted.values.toList()),
            "inputToPresentedMillis" to distribution(inputPresented.values.toList()),
            "frames" to frames.map { (id, frame) -> frame.json(id) },
        )
        val data = checkNotNull(NSJSONSerialization.dataWithJSONObject(report, NSJSONWritingPrettyPrinted, null))
        check(data.writeToFile(args[2], true)) { "Cannot write ${args[2]}" }
        check(failures.isEmpty()) { "${failures.joinToString("; ")}; evidence: ${args[2]}" }
        println("Renderer benchmark: ${measured.size} submissions, ${presented.size} presentations, ${inputs.size} input echoes; ${args[2]}")
    } finally {
        timer.invalidate()
        var closed = false
        pty.close { closed = true }
        view.dispose(); window.orderOut(null); window.close()
        val closeDeadline = CACurrentMediaTime() + 5
        while (!closed && CACurrentMediaTime() < closeDeadline)
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
        check(closed) { "Benchmark child was not reaped" }
    }
}

private class RenderSample(val startedAt: Double, val input: Int?, val measured: Boolean) {
    var cpu: TerminalFrameMetrics? = null
    var gpu: Double? = null
    var submittedAt = 0.0
    var presentedAt = 0.0
    fun json(id: Long): Map<String, Any> = buildMap {
        put("id", id); put("startedAt", startedAt); put("submittedAt", submittedAt); put("presentedAt", presentedAt)
        put("measured", measured); input?.let { put("input", it) }; gpu?.let { put("gpuMillis", it) }
        cpu?.let { put("cpuMillis", it.cpuMillis); put("drawCalls", it.drawCalls) }
    }
}

private fun distribution(values: List<Double>): Map<String, Any> {
    if (values.isEmpty()) return mapOf("count" to 0)
    check(values.all { it.isFinite() && it >= 0 })
    val sorted = values.sorted()
    fun percentile(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
    return mapOf("count" to values.size, "min" to sorted.first(), "p50" to percentile(.5), "p95" to percentile(.95), "p99" to percentile(.99), "max" to sorted.last())
}
