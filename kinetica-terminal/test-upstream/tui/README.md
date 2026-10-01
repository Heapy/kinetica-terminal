# Recorded live application workflows

These are input-only PTY recordings from `scripts/verify-terminal-tui.mjs` on
macOS, captured on 2026-09-30. Each JSON file preserves producer output bytes and
resize events in their original order, application versions, and the event
indices of interaction checkpoints. It contains no expected Kinetica screen.
The commands and text files are our controlled test fixtures. No user rc files,
history, existing tmux server, or user terminal session is used.

- `shell.json`: zsh Unicode input, line editing, interrupting a foreground child,
  resize to 100×32, and a new prompt.
- `vim.json`: Vim 9.1 alternate screen, Unicode paste, file writes, scrolling,
  SGR mouse positioning, resize, and restoring the shell.
- `nvim.json`: Neovim 0.12.5 capability queries, Unicode/combining paste, file writes,
  mouse positioning, colored undercurl, search, resize, arrow keys and restoring the shell.
- `less.json`: less 668 search, PageDown, resize, and restoring the shell.
- `tmux.json`: tmux 3.7c independent panes, resize, detach, and restoring the shell.

The `live_*` cases in `../ghostty/scenarios.json` pin each file's SHA-256. The
generator verifies the hash and that the scenario is an exact replay, combining
only adjacent byte chunks between resizes/checkpoints. All expected cells,
cursor/modes, history, palette and replies are produced by the pinned original
libghostty with the documented host palette/cursor/device identity configuration. Neovim's
comparison explicitly accounts for four unavailable extension families; see the
[oracle reply policy](../ghostty/README.md). Its original reference replies are not changed.
Whole-write and bytewise replay run in ordinary common tests without installed
applications, Python, WebSocket, Playwright, Zig or a network connection.

To make new recordings, build the browser sample, serve the repository locally,
and run `node scripts/verify-terminal-tui.mjs`. This live verifier needs Python 3,
system zsh/Vim/less, tmux, Neovim, and Playwright/Chromium. `KINETICA_TMUX` selects a tmux
executable (default `/opt/homebrew/bin/tmux`); `KINETICA_NVIM` selects Neovim (default `nvim`).
The usual browser verifier environment
variables also apply. The report, complete diagnostic captures and screenshots go
to `build/reports/terminal-tui/`. Review new captures before archiving them; retain
only input events, version/size metadata and checkpoint labels/indices, never use
captured Kinetica state to generate oracle expectations.

tmux may redraw its outer layout before applying a queued inner PTY resize. The
live browser test checks the pane's actual kernel size through `stty -f`, then
verifies `stty size` output through the terminal. This follows tmux 3.7c's
[250 ms pane-resize timer](https://github.com/tmux/tmux/blob/3.7c/server-client.c#L1500),
without a fixed readiness sleep. The native test checks the same kernel size from
inside the pane. Both runners use an isolated socket and stop only their own server.

The verified Neovim binary is the official
[0.12.5 macOS arm64 release](https://github.com/neovim/neovim/releases/tag/v0.12.5),
archive `nvim-macos-arm64.tar.gz`, SHA-256
`65fb000099e47ca1b762584c484cc833f40e30851a0ec450d4174e16317c1f9b`.
It runs with `-u NONE -n -i NONE --noplugin`, isolated XDG directories and controlled files.
The live test does not download or install it. A native test also checks the insert cursor
and emitted undercurl cell attributes; Chromium checks the actual green underline pixel.

These workflows exercise real applications but do not prove compatibility with every
plugin, configuration or TUI. Broader keyboard protocols and sustained interactive latency
remain separate readiness requirements.
