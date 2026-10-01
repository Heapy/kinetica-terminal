import AppKit
import CoreImage
import ScreenCaptureKit

// Test-only capture of the single window identified by our native fixture.
// Never captures a display, other applications, audio, or user input.
final class Frames: NSObject, SCStreamOutput {
    let directory: URL
    let tui: String
    let queue = DispatchQueue(label: "terminal-window-pixels")
    var samples = [[String: Any]]()
    var reference: (x: Int, y: Int, width: Int, height: Int, count: Int, chromeY: Int, chromeHeight: Int)?
    var failures = 0
    var initialTuiInk: Int?
    let images = CIContext()
    init(directory: URL, tui: String) { self.directory = directory; self.tui = tui }
    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let info = attachments.first, let status = info[.status] as? Int,
              status == SCFrameStatus.complete.rawValue,
              let pixels = buffer.imageBuffer else { return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        let width = CVPixelBufferGetWidth(pixels), height = CVPixelBufferGetHeight(pixels)
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        guard let base = CVPixelBufferGetBaseAddress(pixels)?.assumingMemoryBound(to: UInt8.self) else { return }
        var x0 = width, y0 = height, x1 = -1, y1 = -1, count = 0, magenta = 0, tuiInk = 0
        var chromeY0 = height, chromeY1 = -1
        for y in 0..<height {
            var chromeRow = 0
            for x in 0..<width {
                let i = y * stride + x * 4
                let b = Int(base[i]), g = Int(base[i + 1]), r = Int(base[i + 2])
                if y >= 96 && max(r, g, b) > 120 { tuiInk += 1 }
                if g > 120 && g > r * 2 && g > b * 2 {
                    x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y); count += 1
                }
                if r > 140 && b > 140 && g < 100 { magenta += 1 }
                // The fixed title text is an independent AppKit coordinate reference.
                // Window capture can move the entire surface, including its title bar,
                // by one resize step; that is distinct from terminal-only displacement.
                if y < 120 && x >= 170 && x < 750 && r > 60 && abs(r - g) < 3 && abs(g - b) < 3 { chromeRow += 1 }
            }
            // Exclude the full-width horizontal highlight at the window's top edge.
            if chromeRow > 0 && chromeRow < 500 { chromeY0 = min(chromeY0, y); chromeY1 = max(chromeY1, y) }
        }
        let inkWidth = max(0, x1 - x0 + 1), inkHeight = max(0, y1 - y0 + 1)
        let chromeHeight = max(0, chromeY1 - chromeY0 + 1)
        if reference == nil && count > 0 && chromeHeight > 0 { reference = (x0, y0, inkWidth, inkHeight, count, chromeY0, chromeHeight) }
        let badText = reference.map { abs(x0 - $0.x) > 1 || abs((y0 - chromeY0) - ($0.y - $0.chromeY)) > 1 || abs(inkWidth - $0.width) > 1 || abs(inkHeight - $0.height) > 1 || abs(count - $0.count) > 20 || abs(chromeHeight - $0.chromeHeight) > 1 } ?? true
        if initialTuiInk == nil { initialTuiInk = tuiInk }
        let blankTui = tuiInk < max(100, (initialTuiInk ?? 0) / 10)
        let severeTuiDrop = samples.last.map { tuiInk < ($0["tuiInk"] as! Int) * 4 / 10 } ?? false
        let bad = tui.isEmpty ? (badText || magenta > 4) : (blankTui || severeTuiDrop)
        var sample: [String: Any] = ["time": buffer.presentationTimeStamp.seconds, "inkWidth": inkWidth,
            "inkHeight": inkHeight, "inkCount": count, "x": x0, "y": y0, "magentaPixels": magenta, "badText": badText,
            "contentRect": String(describing: info[.contentRect]), "scale": String(describing: info[.contentScale])]
        sample["chromeY"] = chromeY0; sample["chromeHeight"] = chromeHeight
        sample["tuiInk"] = tuiInk; sample["blankTui"] = blankTui; sample["severeTuiDrop"] = severeTuiDrop
        sample["wholeWindowOffsetY"] = reference.map { chromeY0 - $0.chromeY } ?? 0
        sample["boundingRect"] = String(describing: info[.boundingRect])
        sample["screenRect"] = String(describing: info[.screenRect])
        if samples.isEmpty || (bad && failures < 8) || (!tui.isEmpty && samples.count % 120 == 0) {
            let name = "stream-\(samples.count).png"
            if let image = images.createCGImage(CIImage(cvPixelBuffer: pixels), from: CGRect(x: 0, y: 0, width: width, height: height)),
               let data = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) {
                try? data.write(to: directory.appendingPathComponent(name))
                sample["png"] = name
            }
        }
        samples.append(sample)
        if bad { failures += 1 }
        if samples.count == 1 {
            try? Data("ready\n".utf8).write(to: directory.appendingPathComponent("resize_stream.ready"))
        }
    }
}

@main enum Main {
    static func main() async throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let request = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("resize_stream.json"))) as! [String: Any]
        let windowID = (request["window"] as! NSNumber).uint32Value
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
        guard let window = content.windows.first(where: { $0.windowID == windowID }) else { throw NSError(domain: "Fixture window missing", code: 1) }
        let config = SCStreamConfiguration()
        config.width = 2048; config.height = 1280
        config.scalesToFit = false
        config.ignoreShadowsSingleWindow = true
        config.ignoreGlobalClipSingleWindow = true
        config.showsCursor = false
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.minimumFrameInterval = CMTime(value: 1, timescale: 120)
        config.queueDepth = 8
        let frames = Frames(directory: directory, tui: request["tui"] as? String ?? "")
        let stream = SCStream(filter: SCContentFilter(desktopIndependentWindow: window), configuration: config, delegate: nil)
        try stream.addStreamOutput(frames, type: .screen, sampleHandlerQueue: frames.queue)
        try await stream.startCapture()
        let deadline = Date().addingTimeInterval(Double(request["steps"] as? Int ?? 360) / 60 + 10)
        while !FileManager.default.fileExists(atPath: directory.appendingPathComponent("resize_stream.done").path) && Date() < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        try await Task.sleep(for: .milliseconds(300))
        try await stream.stopCapture()
        let report: [String: Any] = frames.queue.sync {
            ["valid": frames.samples.count >= 100 && frames.failures == 0 && FileManager.default.fileExists(atPath: directory.appendingPathComponent("resize_stream.done").path), "failures": frames.failures,
             "frames": frames.samples, "backend": request["backend"] ?? "unknown", "scope": "Single-window WindowServer stream, not physical scanout"]
        }
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
            .write(to: directory.appendingPathComponent("stream-report.json"))
        print("Captured \(frames.samples.count) window frames; \(frames.failures) anomalous frames")
        if !(report["valid"] as! Bool) { exit(1) }
    }
}
