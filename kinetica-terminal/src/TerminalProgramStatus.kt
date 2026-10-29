// Parsing adapted from Ghostty's osc/parsers/program_status.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64

public enum class TerminalProgramState { IDLE, WORKING, DONE, BLOCKED, ERROR, CLEAR }
public enum class TerminalProgramBlockKind { PERMISSION, QUESTION, AUTH }

/** Validated OSC 7501 report. Text is plain, untrusted UTF-8 without C0/C1 controls. */
public data class TerminalProgramStatus(
    val state: TerminalProgramState,
    val id: String? = null,
    val app: String? = null,
    val kind: TerminalProgramBlockKind? = null,
    val progress: Int? = null,
    val title: String? = null,
    val message: String? = null,
)

/** Snapshot after a report or lifecycle cleanup; [report] is null for process/prompt cleanup. */
public data class TerminalProgramStatusChange(
    val report: TerminalProgramStatus?,
    val records: List<TerminalProgramStatus>,
)

internal class TerminalProgramStatusRegistry {
    private val records = linkedMapOf<String?,TerminalProgramStatus>()
    private val observers = mutableListOf<(TerminalProgramStatusChange) -> Unit>()
    val snapshot: List<TerminalProgramStatus> get() = records.values.toList()

    fun observe(observer: (TerminalProgramStatusChange) -> Unit): TerminalDisposable {
        val listener: (TerminalProgramStatusChange) -> Unit = { observer(it) }
        observers += listener
        return TerminalDisposable {
            observers.remove(listener)
            if (observers.isEmpty()) records.clear()
        }
    }

    fun app(id: String?): String? {
        var current = id
        while (true) {
            records[current]?.app?.let { return it }
            if (current == null) return null
            current = current.substringBeforeLast('/', "").ifEmpty { null }
        }
    }

    fun accept(payload: String, terminator: Int, reply: (String) -> Unit) {
        if (observers.isEmpty()) return
        if (payload == "?") {
            reply("\u001b]7501;?:states=idle,working,done,blocked,error:kinds=permission,question,auth" +
                if (terminator == 7) "\u0007" else "\u001b\\")
            return
        }
        parseProgramStatus(payload)?.let(::apply)
    }

    private fun apply(report: TerminalProgramStatus) {
        if (report.state == TerminalProgramState.CLEAR) {
            if (report.id == null) records.clear()
            else records.keys.removeAll { it == report.id || it?.startsWith(report.id + "/") == true }
        } else {
            // Updates refresh recency. Capacity eviction removes only the oldest record,
            // not its children (parents need not exist).
            records.remove(report.id)
            if (records.size == 256) records.remove(records.keys.first())
            records[report.id] = report
        }
        notify(report)
    }

    fun reset() { if (observers.isNotEmpty()) apply(TerminalProgramStatus(TerminalProgramState.CLEAR)) }

    fun finish() {
        if (records.values.removeAll { it.state == TerminalProgramState.WORKING || it.state == TerminalProgramState.BLOCKED })
            notify(null)
    }

    private fun notify(report: TerminalProgramStatus?) {
        val change = TerminalProgramStatusChange(report, snapshot)
        observers.toList().forEach { it(change) }
    }
}

internal fun parseProgramStatus(payload: String): TerminalProgramStatus? {
    if (payload.encodeToByteArray().size > 4087) return null
    val values = mutableMapOf<String,String>()
    for (pair in payload.split(':')) {
        val equals = pair.indexOf('=')
        if (equals < 0) continue
        val key = pair.substring(0, equals).trim(::statusWhitespace)
        if (key.encodeToByteArray().size > 16) return null
        val value = pair.substring(equals + 1).trim(::statusWhitespace)
        // Validate raw values before alphabet filtering or duplicate replacement.
        when (key) {
            "id" -> {
                if (value.length > 128) return null
                val segments = value.split('/')
                if (segments.size > 8 || segments.any { it.length > 32 || !statusName(it) }) return null
            }
            "app", "title", "msg" -> {
                val limit = when (key) { "app" -> 32; "title" -> 256; else -> 2732 }
                if (value.encodeToByteArray().size > limit) return null
            }
        }
        if (value.all { statusNameChar(it) || it in ",/=" }) values[key] = value
    }
    val state = TerminalProgramState.entries.firstOrNull { it.name.lowercase() == values["state"] } ?: return null
    val title: String?
    val message: String?
    try {
        title = statusText(values["title"], 192)
        message = statusText(values["msg"], 2048)
    } catch (_: IllegalArgumentException) { return null }
      catch (_: CharacterCodingException) { return null }
    val progress = values["progress"]?.let { value ->
        // Arbitrarily many leading zeros are valid; avoid Int overflow.
        var n = 0
        for (char in value) {
            if (char !in '0'..'9') return@let null
            n = n * 10 + (char - '0')
            if (n > 100) return@let null
        }
        n.takeIf { value.isNotEmpty() }
    }
    return TerminalProgramStatus(state, values["id"], values["app"]?.takeIf(::statusName),
        if (state == TerminalProgramState.BLOCKED) TerminalProgramBlockKind.entries.firstOrNull { it.name.lowercase() == values["kind"] } else null,
        if (state == TerminalProgramState.WORKING || state == TerminalProgramState.BLOCKED) progress else null,
        title, message)
}

private fun statusWhitespace(char: Char): Boolean = char == ' ' || char in '\t'..'\r'
private fun statusNameChar(char: Char): Boolean = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "_.+-"
private fun statusName(value: String): Boolean = value.isNotEmpty() && value.all(::statusNameChar)

private fun statusText(value: String?, limit: Int): String? {
    if (value.isNullOrEmpty()) return null
    val bytes = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value)
    require(bytes.size <= limit)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    require(text.none { it.code < 32 || it.code in 0x7f..0x9f })
    return text
}
