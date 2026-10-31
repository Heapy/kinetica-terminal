#ifndef KINETICA_TERMINAL_KEYBOARD_H
#define KINETICA_TERMINAL_KEYBOARD_H

#include <Carbon/Carbon.h>
#include <limits.h>
#include <stdlib.h>

// Same input-source identity as Ghostty's KeyboardLayout.id. The caller frees
// the UTF-8 copy; the borrowed property must not outlive its retained source.
static inline char *kinetica_copy_keyboard_input_source_id(void) {
    TISInputSourceRef source = TISCopyCurrentKeyboardInputSource();
    if (source == NULL) return NULL;
    CFStringRef identifier = (CFStringRef)TISGetInputSourceProperty(source, kTISPropertyInputSourceID);
    char *result = NULL;
    if (identifier != NULL) {
        CFIndex size = CFStringGetMaximumSizeForEncoding(CFStringGetLength(identifier), kCFStringEncodingUTF8);
        if (size >= 0 && size < LONG_MAX) {
            result = (char *)malloc((size_t)size + 1);
            if (result != NULL && !CFStringGetCString(identifier, result, size + 1, kCFStringEncodingUTF8)) {
                free(result);
                result = NULL;
            }
        }
    }
    CFRelease(source);
    return result;
}

#endif
