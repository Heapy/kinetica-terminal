@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.native.terminal

import kotlinx.cinterop.*
import platform.AppKit.NSFont
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.CTFontManagerRegisterGraphicsFont
import kotlin.io.encoding.Base64

internal const val DEFAULT_TERMINAL_FONT = "JetBrainsMono-Regular"

/** Embedded in the executable so packaged apps, development runs and tests use the same faces. */
private val registeredTerminalFonts: List<String> by lazy {
    val fonts = bundledTerminalFontData()
    for ((name, encoded) in fonts) {
        val bytes = Base64.decode(encoded)
        val data = bytes.usePinned { checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())) }
        try {
            val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
            try {
                val font = checkNotNull(CGFontCreateWithDataProvider(provider))
                try {
                    // Registration lasts for this process only. An installed copy with the
                    // same PostScript name is already usable and must not fail startup.
                    check(CTFontManagerRegisterGraphicsFont(font, null) || NSFont.fontWithName(name, 14.0) != null) {
                        "Could not register bundled font $name"
                    }
                } finally { CGFontRelease(font) }
            } finally { CGDataProviderRelease(provider) }
        } finally { CFRelease(data) }
    }
    fonts.keys.toList()
}

internal fun registerTerminalFonts(): List<String> = registeredTerminalFonts
