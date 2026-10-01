# Kinetica Terminal

A shared Kotlin terminal engine with a native macOS app and browser surfaces.
The browser renders through WebGL2 (Canvas 2D fallback); macOS uses Metal with
AppKit input, windows and controls. The JVM target provides the headless VT engine.

## Run

Use the included Kotlin Toolchain 0.12.2 wrapper (Kotlin 2.4.10). It provisions
the compiler and dependencies. Native builds require Apple Silicon, macOS and Xcode.

```sh
./kotlin run -m native-terminal
bash scripts/package-native-terminal.sh
open 'build/apps/Kinetica Terminal.app'
```

Packaging includes the Icon Composer source and uses `xcrun actool`; install a
version of Xcode that supports `.icon` files. The bundle is signed ad hoc for local use.

```sh
./kotlin build -m browser-terminal -p js -v release
python3 -m http.server 4173 --bind 127.0.0.1
# http://127.0.0.1:4173/samples/browser-terminal/web/index.html
```

The browser demo provides local echo. For a real shell, connect its byte transport
to a server-side PTY; the library does not start a public shell server.

## Develop and verify

```sh
./kotlin test -m kinetica-terminal -p jvm
./kotlin build -m kinetica-terminal -m browser-terminal -p js -v release
node build/artifacts/CompiledWebArtifact/kinetica-terminaljsTestrelease/kotlin-output/kinetica-terminal_test.mjs

# macOS: live TUI tests also require tmux and Neovim.
# KINETICA_TMUX / KINETICA_NVIM can select their executable paths.
./kotlin test -m kinetica-terminal -p macosArm64
./kotlin test -m native-terminal -p macosArm64
```

Browser integration checks use Playwright/Chromium. Run `npm ci` and
`npx playwright install chromium`, start the local HTTP server above, then run
`node scripts/verify-terminal.mjs`. More checks and optional environment overrides
are documented in the [module guide](kinetica-terminal/README.md#run-and-verify).

## Layout and embedding

- `kinetica-terminal/`: shared VT engine, renderers, native PTY, tests and pinned upstream fixtures.
- `samples/native-terminal/`: macOS application with tabs, search, settings and scrollback.
- `samples/browser-terminal/`: browser demo and browser-test harness.
- `scripts/`: app packaging, fixture generation, renderer checks and benchmarks.
- `docs/`: compatibility, performance and usability notes.

The engine and platform views do not depend on the Kinetica UI framework or its
compiler plugin. Use `BrowserTerminalView` or `AppKitTerminalView` directly;
`AppKitTerminalPane` adds search and the automatic scrollbar. API examples and VT
limits are in the [module guide](kinetica-terminal/README.md).

Kinetica DSL integration lives in
[`Heapy/kinetica`](https://github.com/Heapy/kinetica), module
`kinetica-terminal-integration`. For local development, publish this library with
`./kotlin publish -m kinetica-terminal mavenLocal` before building those adapters.
Version 0.1.0 is a local development coordinate, not a published Maven Central release.

## Origin and licenses

Extracted from the terminal worktree developed in
[`Heapy/kinetica`](https://github.com/Heapy/kinetica) on 2026-10-01, based on
framework commit `858e530`. That terminal work was uncommitted, so this repository
starts with a source snapshot rather than a fabricated commit history.

Project code is Apache-2.0. Adapted Ghostty code, Alacritty recordings, Unicode/uucode
and X11 data retain their original notices and pinned provenance under
`kinetica-terminal/third-party`, `test-upstream` and `resources/META-INF/LICENSES`.
See [LICENSE](LICENSE) and the [upstream fixture guide](kinetica-terminal/test-upstream/README.md).
