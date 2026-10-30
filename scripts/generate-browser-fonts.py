#!/usr/bin/env python3
"""Copy unmodified, pinned demo fonts and licenses; --check verifies every artifact."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
GHOSTTY = "83fb95624b0439ee660c2679b43b38c8b63769cd"
CJK = "523d033d6cb47f4a80c58a35753646f5c3608a78"  # Noto CJK Sans2.004
CJK_FILES = {
    "Sans/Mono/NotoSansMonoCJKsc-Regular.otf": "ec04cc376b34887cedbdf84074e2e226ed2761eeabdcb9173fc1dd7bfd153ef7",
    "LICENSE": "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2",
}
OUTPUT = ROOT / "samples/browser-terminal/web/fonts"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path, help="Pinned Ghostty 1.4 checkout")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    upstream = args.upstream.resolve()
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=upstream, text=True).strip() == GHOSTTY
    # Read from the commit's object database; local modifications cannot affect artifacts.
    def original(path):
        return subprocess.check_output(["git", "show", f"{GHOSTTY}:{path}"], cwd=upstream)
    def cjk(path):
        cache = ROOT / "build/upstream" / ("noto-cjk-" + CJK) / path
        if not cache.exists():
            cache.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/notofonts/noto-cjk/{CJK}/{path}") as response:
                data = response.read()
            assert digest(data) == CJK_FILES[path], f"Changed Noto CJK source: {path}"
            cache.write_bytes(data)
        data = cache.read_bytes()
        assert digest(data) == CJK_FILES[path], f"Changed Noto CJK cache: {path}"
        return data
    fonts = []
    artifacts = {}
    for suffix, style in [("Regular", 0), ("Bold", 1), ("Italic", 4), ("BoldItalic", 5)]:
        name = f"JetBrainsMonoNerdFont-{suffix}.ttf"
        artifacts[name] = original("src/font/res/" + name)
        fonts.append(dict(file=name, family="JetBrains Mono", style=style, emoji=False,
                          source=f"https://github.com/ghostty-org/ghostty/blob/{GHOSTTY}/src/font/res/{name}"))
    for name, family, emoji in [("NotoColorEmoji.ttf", "Noto Color Emoji", True), ("NotoEmoji-Regular.ttf", "Noto Emoji", False)]:
        artifacts[name] = original("src/font/res/" + name)
        fonts.append(dict(file=name, family=family, style=0, emoji=emoji,
                          source=f"https://github.com/ghostty-org/ghostty/blob/{GHOSTTY}/src/font/res/{name}"))
    name = "NotoSansMonoCJKsc-Regular.otf"
    artifacts[name] = cjk("Sans/Mono/" + name)
    fonts.append(dict(file=name, family="Noto Sans Mono CJK SC", style=0, emoji=False,
                      source=f"https://github.com/notofonts/noto-cjk/blob/{CJK}/Sans/Mono/{name}"))
    for font in fonts:
        data = artifacts[font["file"]]
        font.update(bytes=len(data), sha256=digest(data), integrity="sha256-" + base64.b64encode(hashlib.sha256(data).digest()).decode())
    artifacts["licenses/Ghostty-fonts.md"] = original("src/font/res/README.md")
    for name in ["OFL.txt", "MIT.txt", "BSD-2-Clause.txt"]:
        artifacts["licenses/" + name] = original("src/font/res/" + name)
    artifacts["licenses/Noto-CJK-OFL.txt"] = cjk("LICENSE")
    manifest = dict(ghosttyCommit=GHOSTTY, notoCjkTag="Sans2.004", notoCjkCommit=CJK,
                    generatorSha256=digest(Path(__file__).read_bytes()), fonts=fonts,
                    licenses={name: digest(data) for name, data in artifacts.items() if name.startswith("licenses/")})
    artifacts["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    for name, data in artifacts.items():
        target = OUTPUT / name
        if args.check:
            assert target.read_bytes() == data, f"Stale generated font artifact: {target}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f'{"Verified" if args.check else "Generated"} {len(fonts)} unmodified fonts / {sum(f["bytes"] for f in fonts)} bytes and licenses')


if __name__ == "__main__":
    main()
