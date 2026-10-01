#!/usr/bin/env python3
"""Verify native terminal window pixels across occlusion or resize; requires screen capture permission."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import shutil
import tempfile
import time


ROOT = Path(__file__).resolve().parent.parent
TEST = "io.heapy.kinetica.terminal.MacOsRenderObserverTest.retainsLastCompletedFrameAcrossOcclusionWithoutFurtherWrites"
PHASES = {"initial": "BEFORE", "after_cover": "AFTER COVER", "after_hide": "AFTER HIDE"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, default=ROOT / "build/tasks/_kinetica-terminal_linkMacosArm64TestDebug/kinetica-terminal_test.kexe")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/terminal-window")
    parser.add_argument("--scenario", choices=("occlusion", "resize", "continuous-resize"), default="occlusion")
    parser.add_argument("--tui", choices=("htop", "claude"), help="Run the real TUI in the continuous-resize fixture")
    parser.add_argument("--duration", type=int, default=6, help="Continuous resize duration in seconds (1–60)")
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This verifier requires a logged-in macOS desktop")
    if args.tui and args.scenario != "continuous-resize":
        parser.error("--tui requires --scenario continuous-resize")
    if not 1 <= args.duration <= 60:
        parser.error("--duration must be between 1 and 60 seconds")
    tui_environment = {"KINETICA_WINDOW_RESIZE_STEPS": str(args.duration * 60)}
    if args.tui:
        executable = shutil.which(args.tui)
        if not executable:
            parser.error(f"{args.tui} is not installed")
        tui_environment.update({"KINETICA_WINDOW_TUI": args.tui, "KINETICA_WINDOW_TUI_EXECUTABLE": executable})
    args.reports.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=args.reports)).resolve()
    report = {"valid": False, "os": platform.mac_ver()[0], "captures": [], "failures": [],
              "scope": "WindowServer window images and offline Vision OCR; not physical display latency"}
    process = None
    tui_directory = tempfile.TemporaryDirectory(prefix="kinetica-tui-resize-") if args.tui else None
    if tui_directory:
        tui_environment["KINETICA_WINDOW_TUI_DIRECTORY"] = tui_directory.name
    test = TEST if args.scenario == "occlusion" else "io.heapy.kinetica.terminal.MacOsRenderObserverTest.keepsGlyphSizeAcrossResizeAndWhileNextFrameIsPending"
    phases = PHASES if args.scenario == "occlusion" else {
        f"resize_{phase}": "FIXED WIDTH 0123456789" for phase in (
            "initial", "held_wide", "drawn_wide", "held_narrow", "drawn_narrow", "appkit_initial", "appkit_wide", "appkit_narrow")}
    if args.scenario == "continuous-resize":
        test = "io.heapy.kinetica.terminal.MacOsRenderObserverTest.retainsTextDuringContinuousWindowResize"
        phases = {"resize_stream": "FIXED WIDTH 0123456789"}
    reference = None
    report["scenario"] = args.scenario
    report["tui"] = args.tui
    report["durationSeconds"] = args.duration
    try:
        binary = args.binary.resolve(strict=True)
        ocr_source = ROOT / "scripts/terminal-window-ocr.m"
        report["sourcesSha256"] = {
            str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (ocr_source, ROOT / "scripts/terminal-window-stream.swift", Path(__file__).resolve(), ROOT / "kinetica-terminal/test@macosArm64/MacOsRenderObserverTest.kt",
                         ROOT / "kinetica-terminal/src@macosArm64/AppKitTerminal.kt", ROOT / "kinetica-terminal/src@macosArm64/MetalTerminalDrawing.kt",
                         ROOT / "kinetica-terminal/src/TerminalSurface.kt")
        }
        report["binarySha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
        ocr = directory / "window-ocr"
        subprocess.run(["/usr/bin/clang", "-fobjc-arc", "-framework", "Foundation", "-framework", "Vision", "-framework", "AppKit",
                        str(ocr_source), "-o", str(ocr)], check=True, timeout=30)
        if args.scenario == "continuous-resize":
            subprocess.run(["/usr/bin/swiftc", "-parse-as-library", "-O", "-module-cache-path", str(directory / "module-cache"), str(ROOT / "scripts/terminal-window-stream.swift"),
                            "-o", str(directory / "window-stream")], check=True, timeout=60)
        with (directory / "native.log").open("w") as log:
            # The automation runner may suppress its own log colors. Do not pass that
            # preference to interactive TUI fixtures: exercise their real color output.
            environment = dict(os.environ)
            for key in ("NO_COLOR", "CLICOLOR", "CLICOLOR_FORCE", "FORCE_COLOR"):
                environment.pop(key, None)
            process = subprocess.Popen([str(binary), f"--ktest_filter={test}"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                       env={**environment, **tui_environment, "KINETICA_WINDOW_CAPTURE_DIR": str(directory)})
            deadline = time.monotonic() + 60 + args.duration
            for phase, expected in phases.items():
                metadata = directory / f"{phase}.json"
                while not metadata.exists():
                    if process.poll() is not None:
                        raise RuntimeError(f"Native test exited {process.returncode} before {phase}; see native.log")
                    if time.monotonic() >= deadline:
                        raise TimeoutError(f"No capture request for {phase}")
                    time.sleep(0.02)
                request = json.loads(metadata.read_text())
                if request["phase"] != phase or request["expected"] != expected or not request["visible"]:
                    raise RuntimeError(f"Invalid capture request: {request}")
                started = time.monotonic()
                capture = {"phase": phase, "expected": expected, "window": request["window"], "attempts": []}
                report["captures"].append(capture)
                if args.scenario == "continuous-resize":
                    subprocess.run([str(directory / "window-stream"), str(directory)], check=True, timeout=args.duration + 20)
                    capture["streamReport"] = "stream-report.json"
                    (directory / f"{phase}.ack").write_text("verified\n")
                    continue
                while True:
                    png = directory / f"{phase}.png"
                    # Select only the explicitly identified test window, never the whole desktop.
                    snapshot = subprocess.run(["/usr/sbin/screencapture", "-x", "-o", "-l", str(int(request["window"])), str(png)],
                                              timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                    if snapshot.returncode != 0:
                        elapsed = time.monotonic() - started
                        capture["attempts"].append({"elapsedSeconds": elapsed, "captureError": snapshot.stderr.strip()})
                        if elapsed >= 5 or process.poll() is not None:
                            raise RuntimeError(f"{phase}: window capture failed: {snapshot.stderr.strip()}")
                        time.sleep(0.1)
                        continue
                    ocr_args = [str(ocr), str(png)]
                    if args.scenario == "resize":
                        ocr_args.append(str(round(request["contentHeight"] * request["scale"])))
                    recognized = json.loads(subprocess.check_output(ocr_args, text=True, timeout=10))
                    elapsed = time.monotonic() - started
                    capture["attempts"].append({"elapsedSeconds": elapsed, "recognized": recognized})
                    if expected in [" ".join(item["text"].split()) for item in recognized]:
                        capture["png"] = png.name
                        capture["pngSha256"] = hashlib.sha256(png.read_bytes()).hexdigest()
                        if args.scenario == "resize":
                            pixels = next(item["inkPixels"] for item in recognized if " ".join(item["text"].split()) == expected)
                            if pixels is None:
                                raise RuntimeError(f"{phase}: no green fixture glyphs in captured pixels")
                            if reference is None:
                                reference = pixels
                            capture["deltaPixels"] = {key: pixels[key] - reference[key] for key in reference}
                            # Compare actual ink, not the approximate OCR bounding box.
                            # Allow one pixel for drawable-size rounding at cell edges.
                            if any(abs(delta) > 1 for delta in capture["deltaPixels"].values()):
                                raise RuntimeError(f"{phase}: glyph geometry changed: {capture['deltaPixels']}")
                            background = next(item["background"] for item in recognized if " ".join(item["text"].split()) == expected)
                            rgb = [(request["backgroundRGB"] >> shift) & 255 for shift in (16, 8, 0)]
                            if len(background) != 9 or any(any(abs(a - b) > 2 for a, b in zip(point["rgb"], rgb)) for point in background):
                                raise RuntimeError(f"{phase}: exposed background differs from {rgb}: {background}")
                        # Release this stage only after its actual window pixels were verified.
                        (directory / f"{phase}.ack").write_text("verified\n")
                        print(f"{phase}: {expected}", flush=True)
                        break
                    if elapsed >= 5 or process.poll() is not None:
                        raise RuntimeError(f"{phase}: expected window text {expected!r}, recognized {recognized!r}")
                    time.sleep(0.1)
            code = process.wait(timeout=max(1, deadline - time.monotonic()))
            report["nativeExitCode"] = code
            if code != 0:
                raise RuntimeError(f"Native lifecycle assertions failed: exit {code}; see native.log")
            report["valid"] = True
    except Exception as error:
        report["failures"].append(str(error))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if tui_directory:
            tui_directory.cleanup()
        (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Window verification {'passed' if report['valid'] else 'failed'}: {directory / 'report.json'}", flush=True)
    for failure in report["failures"]:
        print(failure, file=sys.stderr)
    return 0 if report["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
