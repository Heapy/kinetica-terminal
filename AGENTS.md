# Kinetica Terminal

- Build with the checked-in Kotlin Toolchain wrapper (`./kotlin`), not Gradle.
- Common VT state, parsing and rendering geometry live in `kinetica-terminal/src`.
  Keep browser/AppKit dependencies confined to platform source sets.
- The standalone engine and views must not depend on the Kinetica UI framework.
  Kinetica DSL adapters live in `kinetica-terminal-ui`; application windows, menus,
  tabs and command routing use the external Kinetica framework.
- Bootstrap unpublished development framework APIs with `scripts/bootstrap-kinetica.sh`.
  Never add a dependency on the UI framework to the terminal engine.
- Preserve pinned upstream revisions, fixtures, license notices and generated-data provenance.
  Regenerate data using `scripts/`; do not hand-edit generated tables.
- Native changes need relevant macOS tests; shared engine changes need JVM and JS checks.
  Commands and live TUI prerequisites are documented in README.md.
- Never replace or terminate the user's installed app or live shell sessions as part of tests.
  Use isolated test windows and temporary files.
