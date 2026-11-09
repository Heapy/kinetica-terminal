@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.terminal

import kotlinx.cinterop.*
import platform.AppKit.NSFont
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import kotlin.io.encoding.Base64

/** A process-local face, loaded once on demand; never registered with the system font manager. */
private val terminalSymbolsFont: NSFont by lazy {
    val bytes = Base64.decode(terminalSymbolsFontData())
    val data = bytes.usePinned { checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())) }
    try {
        val provider = checkNotNull(CGDataProviderCreateWithCFData(data))
        try {
            val graphicsFont = checkNotNull(CGFontCreateWithDataProvider(provider))
            try {
                val font = checkNotNull(CTFontCreateWithGraphicsFont(graphicsFont, 14.0, null, null))
                // Transfer the Core Foundation ownership to Kotlin/Objective-C ARC.
                CFBridgingRelease(font) as NSFont
            } finally { CGFontRelease(graphicsFont) }
        } finally { CGDataProviderRelease(provider) }
    } finally { CFRelease(data) }
}

/** Caller owns the returned reference, just like any CTFontCreate result. */
internal fun terminalCreateSymbolsFont(size: Double): CTFontRef {
    val retained = checkNotNull(CFBridgingRetain(terminalSymbolsFont))
    return try {
        checkNotNull(CTFontCreateCopyWithAttributes(retained.reinterpret(), size, null, null))
    } finally { CFRelease(retained) }
}
