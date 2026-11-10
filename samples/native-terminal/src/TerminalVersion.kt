package app.native.terminal

import platform.Foundation.NSBundle

// Source for unbundled runs and packaging: package-native-terminal.sh queries the built binary's --version.
internal const val TERMINAL_VERSION = "0.5.0"

internal fun terminalApplicationVersion(
    bundleVersion: String? = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String,
): String = bundleVersion?.takeIf { value -> value.length in 1..128 && value.all { it in '!'..'~' } } ?: TERMINAL_VERSION
