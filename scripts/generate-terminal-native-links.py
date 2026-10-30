#!/usr/bin/env python3
"""Run the pinned original macOS URL policy. Never opens a URL or launches a handler."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHA = "83fb95624b0439ee660c2679b43b38c8b63769cd"
upstream = Path(sys.argv[1]).resolve()
source = upstream / "macos/Sources/Helpers/UntrustedURL.swift"
application = upstream / "macos/Sources/Ghostty/Ghostty.App.swift"
helper = ROOT / "scripts/terminal-ghostty-links-oracle.swift"
assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == SHA
assert not subprocess.check_output(["git", "status", "--porcelain", "--", str(source)], cwd=upstream, text=True).strip()
assert not subprocess.check_output(["git", "status", "--porcelain", "--", str(application)], cwd=upstream, text=True).strip()
conversion = application.read_text().split("            let url: URL\n", 1)[1].split("            switch action.kind {", 1)[0]
conversion = "            let url: URL\n" + conversion
resources = []
for name in ["safe.txt", "with space.txt", "日本.txt", "payload", "executable.txt", "script.sh", "script.py"] + [
    "payload." + ext for ext in "action app applescript class command desktop inetloc jar mobileconfig mpkg pkg scpt terminal tool url webloc workflow COMMAND".split()
]:
    resources.append({"name": name, "kind": "file", "mode": 493 if name == "executable.txt" else 420})
resources += [{"name": "directory", "kind": "directory"}, {"name": "bundle.app", "kind": "directory"}]
for name, target in [("safe-link.txt", "safe.txt"), ("unsafe-link.txt", "payload.command"), ("dangling.txt", "missing.txt"),
    ("directory-link", "directory")]:
    resources.append({"name": name, "kind": "symlink", "target": target})
resources.append({"name": "fifo", "kind": "fifo"})
values = ["", "/tmp/file.txt", "../file.txt", "payload.command", "http://example.com", "https://example.com/path",
    "https:relative", "http:///missing-host", "https://", "mailto:", "mailto:user@example.com", "mailto:user@example.com?subject=hi",
    "vscode://file/tmp/example.swift", "ssh://example.com", "javascript:alert(1)", "data:text/plain,test",
    "https://example.com/with space", "https://example.com/a%0Ab", "https://example.com/a//../b", "https://例え.jp/日本",
    "https://user:pass@example.com/", "file://remote.example/tmp/test", "file://<ROOT>/safe.txt?query", "file://<ROOT>/safe.txt#fragment",
    "file://localhost<ROOT>/safe.txt", "file://<ROOT>/directory/../safe.txt", "file://<ROOT>/missing.txt"]
values += ["file://<ROOT>/" + item["name"] for item in resources]
values += ["file://<ROOT>/" + name for name in ["directory/", "directory-link/", "safe.txt/", "safe-link.txt/", "directory/../directory"]]
for char in ["\0", "\n", "\r", "\t", "\x7f", "\x85", "\u061c", "\u200b", "\u200f", "\u2028", "\u2029", "\u202e", "\u2060", "\u2066", "\u2069", "\ufeff"]:
    values.append("https://example.com/before" + char + "after")
values += ["/tmp/preview" + char + "////../payload.command////" for char in ["\x85", "\u2028", "\u2029"]]
inputs = [{"value": value, "kind": "osc8"} for value in values]
detected = values + ["<ROOT>/" + item["name"] for item in resources]
detected += ["./safe.txt", "../resources/with space.txt", "directory/../safe.txt", "~/", "~/missing-kinetica-link.txt",
             "$HOME/safe.txt", "<ROOT>/safe.txt:42", "<ROOT>/with%20space.txt", "<ROOT>/safe.txt#fragment"]
inputs += [{"value": value, "kind": "detected"} for value in detected]
with tempfile.TemporaryDirectory(prefix="kinetica-links-oracle-", dir="/private/tmp") as tmp:
    directory = Path(tmp) / "resources"
    directory.mkdir()
    for item in resources:
        path = directory / item["name"]
        if item["kind"] == "file":
            path.write_text("ordinary test fixture\n")
            path.chmod(item["mode"])
        elif item["kind"] == "directory": path.mkdir()
        elif item["kind"] == "symlink": path.symlink_to(item["target"])
        else: os.mkfifo(path)
    executable = Path(tmp) / "oracle"
    extracted = Path(tmp) / "Conversion.swift"
    extracted.write_text('''import Foundation
private struct Action { enum Kind { case unknown }; let url: String; let kind: Kind = .unknown }
private func openUntrustedURL(_ value: String) -> String { value }
func originalDetectedLinkTarget(_ value: String) -> String {
    let action = Action(url: value)
''' + conversion + '\n fatalError("Expected unknown action")\n}\n')
    subprocess.run(["xcrun", "swiftc", str(source), str(extracted), str(helper), "-o", str(executable)], check=True)
    actual = subprocess.check_output([str(executable)], cwd=directory,
        input=json.dumps([{**item, "value": item["value"].replace("<ROOT>", str(directory))} for item in inputs]).encode())
    def portable(value):
        return value.replace(str(directory), "<ROOT>").replace(tmp, "<WORK>") \
            .replace(str(directory).removeprefix("/private"), "<SHORT_ROOT>") \
            .replace(tmp.removeprefix("/private"), "<SHORT_WORK>").replace(str(Path.home()), "<HOME>")
    cases = [{key: portable(value) for key, value in case.items()} for case in json.loads(actual)]
fixture = {"resources": resources, "cases": cases}
encoded = base64.b64encode(json.dumps(fixture, ensure_ascii=True, separators=(",", ":")).encode()).decode()
output = ROOT / "kinetica-terminal/test@macosArm64/generated/TerminalNativeLinkFixtures.kt"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text('// Generated by scripts/generate-terminal-native-links.py; do not edit.\npackage io.heapy.kinetica.terminal\n\ninternal fun terminalNativeLinkFixtures(): String = "' + encoded + '"\n')
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {"commit": SHA, "cases": len(cases), "resources": len(resources), "sourceSha256": digest(source),
    "applicationSha256": digest(application), "conversionSha256": hashlib.sha256(conversion.encode()).hexdigest(),
    "helperSha256": digest(helper), "generatorSha256": digest(Path(__file__)), "fixturesSha256": digest(output),
    "swift": subprocess.check_output(["xcrun", "swiftc", "--version"], text=True).strip()}
(ROOT / "kinetica-terminal/test-upstream/ghostty/native-links-manifest.json").write_text(json.dumps(manifest, indent=2) + '\n')
print(f'{len(cases)} original-policy cases, {len(resources)} isolated resources')
