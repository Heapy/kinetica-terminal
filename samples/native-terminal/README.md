# Native macOS terminal

Runs `/bin/zsh -l` in a local PTY and displays it using the shared Kotlin terminal engine and
one AppKit view backed by a Metal glyph-atlas renderer, with AppKit drawing as a fallback.
Requires macOS on Apple Silicon.

Shell sessions enable grapheme clustering by default: skin-tone modifiers, flags,
and ZWJ emoji sequences occupy a single two-cell cluster. Terminal applications can
toggle this behavior with DEC mode 2027; a terminal reset restores the enabled default.

```sh
./kotlin run -m native-terminal
```

To create a double-clickable app:

```sh
bash scripts/package-native-terminal.sh
open 'build/apps/Kinetica Terminal.app'
```

The packaged app includes **Open Kinetica Here** in Finder's **Services** menu
(also available from the context menu of a selected folder). Install the app in
`/Applications` or `~/Applications` and launch it once. If the action is hidden,
enable it in System Settings → Keyboard → Keyboard Shortcuts → Services.
macOS may need a logout/login to refresh newly installed services.

Select one or more folders to open a separate terminal window in each directory.
This also launches Kinetica when it is closed, without an extra home-directory
window. Existing sessions stay open. Folder paths, including spaces, Unicode and
shell metacharacters, are passed directly as the PTY's working directory.
The service accepts existing local folders only; regular files and remote URLs
are ignored. An empty or invalid selection reports an error through Services.
Unbundled `./kotlin run` builds do not advertise the Finder service.

The application reports `Kinetica <version>` through XTVERSION and `--version`, and
sets `TERM_PROGRAM=Kinetica` and `TERM_PROGRAM_VERSION` for new shells. Packaged apps
read `CFBundleShortVersionString`; unbundled development runs use `TERMINAL_VERSION`
in `src/TerminalVersion.kt`. Packaging queries the compiled executable's `--version`
to write that same version into `Info.plist`, including when using `--skip-build`.
Kinetica Terminal → About Kinetica Terminal shows the same version and the app's license.

The surface supports native input composition, Command+C/V, drag selection, and wheel history.
Closing the window disposes the UI and closes/reaps the shell. When the shell exits (`exit`,
Ctrl+D or an error), its own tab/window closes automatically; other sessions remain open.
New tabs inherit an existing local directory reported by OSC 7, falling back to the shell's
PTY directory. Reports naming remote hosts are never treated as local directories.
For shells emitting OSC 133, Shell → Previous Prompt / Next Prompt navigates between
retained prompt regions. The terminal does not install or modify shell integration scripts.
See [the terminal module](../../kinetica-terminal/README.md)
for API details and the current compatibility limits.

Unhandled Kotlin exceptions are saved before termination to individual `failure-*.log`
files in `~/Library/Logs/Kinetica Terminal/`. Reports include the Kotlin stack, causes,
suppressed exceptions, timestamps, and app/OS details. They are readable only by the
current user and remain across app restarts; old reports can be deleted manually.
The logger does not record terminal contents or environment variables, although exception
messages can contain application data. Native faults such as segmentation faults still
require the macOS crash report. If saving fails, the original exception goes to stderr.
