# Embedded native Nerd Font symbols

`SymbolsNerdFont-Regular.ttf` is the unmodified symbols-only font from Nerd Fonts
v3.4.0, commit `fa7b859994228a9c8759f99c55a8d31ee92a1b5e`. `manifest.json`
records the upstream URL, SHA-256 hashes, and generator provenance. The original
MIT license is preserved here and copied to the runtime license resources (and
the native app bundle by the existing packaging script).

The macOS engine embeds the font as generated Base64 source, so standalone views,
command-line launches, tests, and packaged apps all use the same data without a
resource-directory convention or a runtime download. The font adds 2,440,316 raw
font bytes; the temporary Base64 decode buffers are discarded after creating the
shared native face. That face is loaded lazily and retained for the process lifetime.
Each shaper owns and releases its size-specific references.

The font is never registered or installed in macOS and does not appear as a text
font in the application's font chooser. The chosen text font continues to supply
its own glyphs. For missing private-use symbols, the bundled face takes priority
over system fallback fonts whose private-use assignments may mean something else.
Other characters retain normal CoreText fallback, followed by the embedded face
if it covers the entire unresolved cluster. Unsupported clusters still use `�`.
The existing glyph constraints fit the symbols to the terminal cells in both
Metal and software rendering. Color emoji continue to use the system emoji font.

Regenerate from the checked-in sources, or verify everything offline:

```sh
python3 scripts/generate-native-symbols-font.py
python3 scripts/generate-native-symbols-font.py --check
```

If an original source file is missing, generation downloads only that file from
the pinned commit and verifies its expected hash before writing it. `--check`
never downloads or writes files. Do not edit the generated Kotlin data by hand.
