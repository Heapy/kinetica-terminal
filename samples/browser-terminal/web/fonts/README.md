# Demo fonts

These unmodified fonts are copied by `scripts/generate-browser-fonts.py` from the
pinned Ghostty 1.4.0 and Noto CJK Sans2.004 sources recorded in `manifest.json`.
The manifest includes every font's SHA-256, fetch integrity value and source URL.
Licenses and upstream attribution are copied without modification into `licenses/`.
The original font files also retain their embedded copyright and licensing metadata.

The default demo loads all seven faces from this directory. It does not download
fonts from a CDN. The four JetBrains Mono Nerd Font faces provide regular, bold,
italic and bold italic; Noto supplies color emoji, text emoji and CJK fallback.
The set is approximately 37.1 MB before HTTP compression. Fetch URLs include their
content hash for versioned caching; a deployment should serve them with compression
and caching. `?fonts=system` explicitly selects the
CSS/system-font path, and unavailable or corrupt assets leave that path usable.

Regenerate or verify from the repository root:

```sh
python3 scripts/generate-browser-fonts.py build/upstream/ghostty-1.4.0
python3 scripts/generate-browser-fonts.py build/upstream/ghostty-1.4.0 --check
```

The generator reads Ghostty's committed objects, never working-tree font edits.
It downloads the pinned CJK font/license only when absent from the local build
cache and verifies their fixed SHA-256 values before producing any artifacts.
