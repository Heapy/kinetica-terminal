# Native macOS terminal

Runs `/bin/zsh -l` in a local PTY and displays it using the shared Kotlin terminal engine and
one AppKit view backed by a Metal glyph-atlas renderer, with AppKit drawing as a fallback.
Requires macOS on Apple Silicon.

```sh
./kotlin run -m native-terminal
```

To create a double-clickable app:

```sh
bash scripts/package-native-terminal.sh
open 'build/apps/Kinetica Terminal.app'
```

The surface supports native input composition, Command+C/V, drag selection, and wheel history.
Closing the window disposes the UI and closes/reaps the shell. When the shell exits (`exit`,
Ctrl+D or an error), its own tab/window closes automatically; other sessions remain open.
See [the terminal module](../../kinetica-terminal/README.md)
for API details and the current compatibility limits.
