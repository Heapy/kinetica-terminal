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

Both host factories accept `clipboardPolicy` for OSC 52. The default denies application access;
`TerminalClipboardPolicy.WRITE_ONLY` enables copying from a remote program, while a custom policy
can request asynchronous user approval. Surface disposal cancels the binding's pending requests.

`notificationPolicy` controls OSC 9/777 desktop delivery and defaults to denial. Both factories
expose `onView` so an application can call `requestNotificationPermission` from a user action.
The surfaces display OSC 9;4 progress with a 15-second stale-report timeout and dispose its
timers on unmount. A native application's notification-center delegate retains control over
foreground notification presentation.

Apps apply `kinetica-ui.module-template.yaml` for the Kinetica compiler plugin and
Maven-local repository. Build the matching development framework with
`bash scripts/bootstrap-kinetica.sh /path/to/kinetica` before compiling apps. See the root
README for the current local-only framework coordinates.
