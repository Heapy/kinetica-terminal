# Kinetica terminal components

This module joins the standalone terminal engine to the Kinetica UI framework.
It is part of the terminal repository: the framework has no dependency on terminal code.
The engine's `TerminalSession`, PTY and Metal/WebGL surfaces remain framework-independent.

`terminal(sessionId, fontSize, fontFamily, semantics)` emits a host node.
Register `appKitTerminalHosts` or `browserTerminalHosts` with the renderer, supplying a
session map. Reconciliation retains a surface while its tag/key stays the same, updates
font properties in place and disposes the view on unmount. It never owns the session or
PTY. A session change requires a new host key. AppKit `controls = true` adds the overlay
scrollbar; the app provides its search bar with Kinetica components.

`appKitTerminalHosts(onView = …)` exposes the mounted view for terminal operations such
as selection and search revelation; the callback receives null on unmount. Framework
focus requests target the terminal inside its containing pane.

Apps apply `kinetica-ui.module-template.yaml` for the Kinetica compiler plugin and
Maven-local repository. Build the matching development framework with
`bash scripts/bootstrap-kinetica.sh /path/to/kinetica` before compiling apps. See the root
README for the current local-only framework coordinates.
