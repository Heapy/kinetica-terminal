@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

// Policy/path conversion adapted from Ghostty 1.4 UntrustedURL.swift, Ghostty.App.swift
// and Surface.resolvePathForOpening (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned revision and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import platform.AppKit.*
import platform.Foundation.*
import platform.posix.F_OK
import platform.posix.access

internal enum class NativeLinkDenial(val message: String) {
    MALFORMED("The target is not a valid absolute URL."),
    UNSAFE_CHARACTERS("The target contains invisible or line-breaking characters."),
    INVALID_WEB("The web target does not contain a valid host."),
    INACCESSIBLE_FILE("The local target does not exist or is not a regular file or directory."),
    UNSAFE_FILE("Opening this local target could execute code."),
}

internal sealed interface NativeLinkDecision {
    class Allow(val url: NSURL) : NativeLinkDecision
    class Confirm(val url: NSURL) : NativeLinkDecision
    class Deny(val reason: NativeLinkDenial) : NativeLinkDecision
}

private fun unsafeLinkCharacter(char: Char): Boolean = char.code in 0..31 || char.code in 127..159 ||
    char == '\u061c' || char in '\u200b'..'\u200f' || char in '\u2028'..'\u202e' ||
    char in '\u2066'..'\u2069' || char == '\u2060' || char == '\ufeff'

internal fun escapeNativeLinkDisplay(value: String): String = buildString {
    for (char in value) if (unsafeLinkCharacter(char)) append("\\u{${char.code.toString(16).uppercase()}}") else append(char)
}

private fun canonicalNativeFileURL(url: NSURL): NSURL? {
    val resolved = url.URLByStandardizingPath?.URLByResolvingSymlinksInPath ?: return null
    // NSURL infers a directory slash from the filesystem; Swift URL preserves the input's hint.
    val value = resolved.absoluteString ?: return resolved
    return when {
        url.hasDirectoryPath && !value.endsWith('/') -> NSURL.URLWithString("$value/")
        !url.hasDirectoryPath && value.endsWith('/') && resolved.path != "/" -> NSURL.URLWithString(value.dropLast(1))
        else -> resolved
    }
}

internal fun nativeLinkDisplay(value: String): String {
    val url = NSURL.URLWithString(value)
    val normalized = if (url?.scheme != null) {
        if (url.fileURL) canonicalNativeFileURL(url)?.path ?: value else value
    } else NSURL.fileURLWithPath(value.ifEmpty { "." }).URLByStandardizingPath?.path ?: value
    return escapeNativeLinkDisplay(normalized)
}

/** Ghostty Surface.resolvePathForOpening followed by the macOS unknown-link conversion. */
internal fun nativeDetectedLinkTarget(value: String, workingDirectory: String?): String {
    if (value.any(::unsafeLinkCharacter)) return value
    var target = value
    if (!value.startsWith('/') && workingDirectory != null) {
        // Zig's path.resolve is lexical: collapse dot segments before consulting symlinks.
        val components = mutableListOf<String>()
        for (part in "$workingDirectory/$value".split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
            else -> components += part
        }
        val resolved = "/" + components.joinToString("/")
        if (resolved.any(::unsafeLinkCharacter)) return resolved
        if (access(resolved, F_OK) == 0) target = resolved
    }
    if (NSURL.URLWithString(target)?.scheme != null) return target
    val expanded = NSString.create(string = target).stringByStandardizingPath
    if (expanded.any(::unsafeLinkCharacter)) return expanded
    // Swift URL(filePath:) uses the path's slash hint, unlike NSURL's filesystem inference.
    val url = if (expanded.isEmpty()) NSURL.fileURLWithPath(NSFileManager.defaultManager.currentDirectoryPath, isDirectory = true)
        else NSURL.fileURLWithPath(expanded, isDirectory = expanded.endsWith('/'))
    return url.absoluteString ?: target
}

internal fun classifyNativeLink(value: String): NativeLinkDecision {
    fun deny(reason: NativeLinkDenial) = NativeLinkDecision.Deny(reason)
    if (value.isEmpty()) return deny(NativeLinkDenial.MALFORMED)
    if (value.any(::unsafeLinkCharacter)) return deny(NativeLinkDenial.UNSAFE_CHARACTERS)
    val url = NSURL.URLWithString(value) ?: return deny(NativeLinkDenial.MALFORMED)
    val scheme = url.scheme?.lowercase()?.takeIf { it.isNotEmpty() } ?: return deny(NativeLinkDenial.MALFORMED)
    when (scheme) {
        "http", "https" -> return if (url.host.isNullOrEmpty()) deny(NativeLinkDenial.INVALID_WEB) else NativeLinkDecision.Allow(url)
        "mailto" -> return if (NSURLComponents.componentsWithURL(url, false)?.path.isNullOrEmpty())
            deny(NativeLinkDenial.MALFORMED) else NativeLinkDecision.Allow(url)
        "file" -> Unit
        else -> return NativeLinkDecision.Confirm(url)
    }
    if (!url.fileURL || url.query != null || url.fragment != null ||
        !url.host.isNullOrEmpty() && !url.host.equals("localhost", ignoreCase = true)) return deny(NativeLinkDenial.MALFORMED)
    val canonical = canonicalNativeFileURL(url) ?: return deny(NativeLinkDenial.INACCESSIBLE_FILE)
    val resources = canonical.resourceValuesForKeys(listOf(NSURLTypeIdentifierKey, NSURLIsDirectoryKey,
        NSURLIsRegularFileKey, NSURLIsExecutableKey), null) ?: return deny(NativeLinkDenial.INACCESSIBLE_FILE)
    val directory = (resources[NSURLIsDirectoryKey] as? NSNumber)?.boolValue == true
    val regular = (resources[NSURLIsRegularFileKey] as? NSNumber)?.boolValue == true
    if (!directory && !regular) return deny(NativeLinkDenial.INACCESSIBLE_FILE)
    if (canonical.pathExtension?.lowercase() in unsafeLinkExtensions) return deny(NativeLinkDenial.UNSAFE_FILE)
    val type = resources[NSURLTypeIdentifierKey] as? String
    if (type != null && listOf("com.apple.application", "public.executable", "public.script").any {
        NSWorkspace.sharedWorkspace.type(type, conformsToType = it)
    }) return deny(NativeLinkDenial.UNSAFE_FILE)
    if (!directory && (resources[NSURLIsExecutableKey] as? NSNumber)?.boolValue == true) return deny(NativeLinkDenial.UNSAFE_FILE)
    return NativeLinkDecision.Allow(canonical)
}

private val unsafeLinkExtensions = setOf("action", "app", "applescript", "class", "command", "desktop", "inetloc",
    "jar", "mobileconfig", "mpkg", "pkg", "scpt", "terminal", "tool", "url", "webloc", "workflow")

/** All decisions are handled here: refusal never retries an unrestricted opener. */
internal class NativeTerminalLinkOpener(
    private val scheduler: TerminalScheduler,
    private val present: (NativeLinkDecision, String, (Boolean) -> Unit) -> TerminalDisposable,
    private val launch: (NSURL) -> Unit,
    private val canConfirm: () -> Boolean = { true },
) : TerminalDisposable {
    private var disposed = false
    private var token: Any? = null
    private var pending: TerminalDisposable? = null
    private fun cancel() { token = null; pending?.dispose(); pending = null }

    fun open(value: String): Boolean = open(value) { true }

    fun open(value: String, permitted: () -> Boolean): Boolean {
        if (disposed) return true
        cancel()
        if (!permitted()) return true
        val decision = classifyNativeLink(value)
        if (decision is NativeLinkDecision.Allow) { launch(decision.url); return true }
        val current = Any(); token = current
        pending = scheduler.schedule(1) {
            if (!disposed && token === current) {
                pending = null
                val dialog = present(decision, nativeLinkDisplay(value)) { confirmed ->
                    if (!disposed && token === current) {
                        token = null; pending = null
                        if (confirmed && decision is NativeLinkDecision.Confirm && canConfirm() && permitted()) launch(decision.url)
                    }
                }
                if (!disposed && token === current) pending = dialog else dialog.dispose()
            }
        }
        return true
    }

    override fun dispose() { if (!disposed) { disposed = true; cancel() } }
}

internal fun nativeLinkAlert(decision: NativeLinkDecision, display: String): NSAlert {
    val alert = NSAlert()
    alert.alertStyle = NSAlertStyleWarning
    when (decision) {
        is NativeLinkDecision.Confirm -> {
            val application = NSWorkspace.sharedWorkspace.URLForApplicationToOpenURL(decision.url)?.lastPathComponent?.removeSuffix(".app")
            val handler = application?.let { "“${escapeNativeLinkDisplay(it)}”" } ?: "the default application"
            alert.messageText = "Open Link from Terminal Output?"
            alert.informativeText = "This link will open in $handler. Continue only if you recognize and trust the destination."
            alert.addButtonWithTitle("Cancel"); alert.addButtonWithTitle("Open Link")
        }
        is NativeLinkDecision.Deny -> {
            alert.messageText = "Kinetica Blocked This Link"
            alert.informativeText = decision.reason.message
            alert.addButtonWithTitle("OK"); alert.addButtonWithTitle("Copy Link")
        }
        is NativeLinkDecision.Allow -> error("Allowed links need no alert")
    }
    val scroll = NSScrollView(NSMakeRect(0.0, 0.0, 480.0, 96.0))
    scroll.borderType = NSBezelBorder; scroll.hasVerticalScroller = true; scroll.autohidesScrollers = true
    val text = NSTextView(scroll.contentView().bounds)
    text.editable = false; text.selectable = true; text.richText = false
    text.font = NSFont.monospacedSystemFontOfSize(NSFont.systemFontSize, NSFontWeightRegular)
    text.string = display; text.textContainerInset = NSMakeSize(6.0, 6.0)
    text.textContainer?.widthTracksTextView = true
    text.textContainer?.containerSize = NSMakeSize(480.0, Double.MAX_VALUE)
    scroll.documentView = text; alert.accessoryView = scroll
    return alert
}

internal fun nativeLinkAlertResponse(decision: NativeLinkDecision, response: Long, display: String,
    copy: (String) -> Unit): Boolean {
    if (decision is NativeLinkDecision.Deny && response == NSAlertSecondButtonReturn) copy(display)
    return decision is NativeLinkDecision.Confirm && response == NSAlertSecondButtonReturn
}

internal fun presentNativeLinkDecision(owner: NSWindow?, decision: NativeLinkDecision, display: String,
    complete: (Boolean) -> Unit): TerminalDisposable {
    // User activation needs a mounted view. Do not create an orphan modal dialog after detach.
    if (owner == null || decision is NativeLinkDecision.Allow) { complete(false); return TerminalDisposable {} }
    val alert = nativeLinkAlert(decision, display)
    var active = true
    alert.beginSheetModalForWindow(owner) { response ->
        if (active) {
            active = false
            complete(nativeLinkAlertResponse(decision, response, display) { copied ->
                NSPasteboard.generalPasteboard.clearContents()
                NSPasteboard.generalPasteboard.setString(copied, NSPasteboardTypeString)
            })
        }
    }
    return TerminalDisposable {
        if (active) { active = false; owner.endSheet(alert.window, NSModalResponseCancel); alert.window.orderOut(null) }
    }
}
