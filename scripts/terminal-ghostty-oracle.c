/* Test-only libghostty-vt oracle. Build against the revision in
 * kinetica-terminal/third-party/ghostty.json. No GUI, shell or PTY is started.
 * stdin: N cols rows history; C fg bg cursor palette[256] (hex RGB configuration);
 * W hex-bytes; R cols rows; K keypad-key mods (append encoded input to replies);
 * M action button mods column row (stateless mouse encoding, unit cells);
 * S (snapshot); U (width ranges).
 * stdout: one JSON snapshot per S. Every API failure exits nonzero.
 */
#include <ghostty/vt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char replies[1024 * 1024];
static size_t replies_len;
static bool holds[4096];
static size_t holds_len;
static void render_hold(GhosttyTerminal terminal, void *userdata, bool held) {
    (void)terminal; (void)userdata;
    if (holds_len == sizeof(holds) / sizeof(holds[0])) exit(2);
    holds[holds_len++] = held;
}
static void check(GhosttyResult result) {
    if (result != GHOSTTY_SUCCESS) { fprintf(stderr, "libghostty error %d\n", result); exit(2); }
}
static void reply(GhosttyTerminal terminal, void *userdata, const uint8_t *data, size_t len) {
    (void)terminal; (void)userdata;
    if (len > sizeof(replies) - replies_len) { fputs("reply limit exceeded\n", stderr); exit(2); }
    memcpy(replies + replies_len, data, len); replies_len += len;
}
static bool device_attributes(GhosttyTerminal terminal, void *userdata, GhosttyDeviceAttributes *out) {
    (void)terminal; (void)userdata;
    // Host identity configuration, like the palette and cursor defaults. Original
    // libghostty still parses requests and formats every response. VT100 + AVO;
    // no image/clipboard features or identifying firmware/unit number advertised.
    *out = (GhosttyDeviceAttributes){
        .primary = {.conformance_level = 1, .features = {2}, .num_features = 1},
        .secondary = {.device_type = 0, .firmware_version = 0, .rom_cartridge = 0},
        .tertiary = {.unit_id = 0},
    };
    return true;
}
static GhosttyString xtversion(GhosttyTerminal terminal, void *userdata) {
    (void)terminal; (void)userdata;
    static const uint8_t name[] = "Kinetica";
    return (GhosttyString){.ptr = name, .len = sizeof(name) - 1};
}
static void json_bytes(const unsigned char *value, size_t len) {
    putchar('"');
    for (size_t i = 0; i < len; i++) {
        unsigned c = value[i];
        if (c == '"' || c == '\\') { putchar('\\'); putchar(c); }
        else if (c < 32) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}
static void json_codepoint(uint32_t c) {
    if (c < 0x10000) printf("\\u%04x", c);
    else { c -= 0x10000; printf("\\u%04x\\u%04x", 0xd800 + (c >> 10), 0xdc00 + (c & 1023)); }
}
static int color(GhosttyStyleColor c) {
    if (c.tag == GHOSTTY_STYLE_COLOR_PALETTE) return 0x1000000 | c.value.palette;
    if (c.tag == GHOSTTY_STYLE_COLOR_RGB) return c.value.rgb.r << 16 | c.value.rgb.g << 8 | c.value.rgb.b;
    return -1;
}
static GhosttyColorRgb rgb(unsigned value) {
    return (GhosttyColorRgb){.r = value >> 16, .g = value >> 8, .b = value};
}
static unsigned packed(GhosttyColorRgb value) { return value.r << 16 | value.g << 8 | value.b; }
static void snapshot(GhosttyTerminal terminal) {
    uint16_t cols, rows, x, y;
    size_t history;
    bool pending, visible;
    GhosttyTerminalScreen active;
    GhosttyString title;
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLS, &cols));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_CURSOR_X, &x));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_CURSOR_PENDING_WRAP, &pending));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, &visible));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title));
    printf("{\"columns\":%u,\"rows\":%u,\"cursor\":[%u,%u],\"pendingWrap\":%s,\"visible\":%s,\"alternate\":%s,\"history\":%zu,\"title\":",
        cols, rows, x, y, pending ? "true" : "false", visible ? "true" : "false", active ? "true" : "false", history);
    json_bytes((const unsigned char *)title.ptr, title.len);
    fputs(",\"replies\":\"", stdout);
    for (size_t i = 0; i < replies_len; i++) printf("%02x", replies[i]);
    fputs("\",\"modes\":[", stdout);
    const GhosttyMode modes[] = {GHOSTTY_MODE_DECCKM, GHOSTTY_MODE_ORIGIN, GHOSTTY_MODE_WRAPAROUND,
        GHOSTTY_MODE_INSERT, GHOSTTY_MODE_LINEFEED, GHOSTTY_MODE_BRACKETED_PASTE, GHOSTTY_MODE_FOCUS_EVENT, GHOSTTY_MODE_SGR_MOUSE,
        GHOSTTY_MODE_GRAPHEME_CLUSTER, GHOSTTY_MODE_SYNC_OUTPUT, GHOSTTY_MODE_CURSOR_BLINKING};
    for (size_t i = 0; i < sizeof(modes) / sizeof(modes[0]); i++) {
        GhosttyTerminalModeConfig config = {.mode = modes[i]};
        check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_MODE, &config));
        printf("%s%s", i ? "," : "", config.value ? "true" : "false");
    }
    fputs("],\"holds\":[", stdout);
    for (size_t i = 0; i < holds_len; i++) printf("%s%s", i ? "," : "", holds[i] ? "true" : "false");
    GhosttyRenderState render_state;
    GhosttyRenderStateCursorVisualStyle cursor_style;
    check(ghostty_render_state_new(NULL, &render_state));
    check(ghostty_render_state_update(render_state, terminal));
    check(ghostty_render_state_get(render_state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &cursor_style));
    ghostty_render_state_free(render_state);
    printf("],\"cursorStyle\":%d,\"colors\":[", cursor_style);
    const GhosttyTerminalData color_fields[] = {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND,
        GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, GHOSTTY_TERMINAL_DATA_COLOR_CURSOR};
    for (size_t i = 0; i < 3; i++) {
        GhosttyColorRgb value;
        check(ghostty_terminal_get(terminal, color_fields[i], &value));
        printf("%s%u", i ? "," : "", packed(value));
    }
    GhosttyColorRgb palette[256], defaults[256];
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, &palette));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, &defaults));
    fputs("],\"palette\":[", stdout);
    bool comma = false;
    for (unsigned i = 0; i < 256; i++) if (packed(palette[i]) != packed(defaults[i])) {
        printf("%s[%u,%u]", comma ? "," : "", i, packed(palette[i])); comma = true;
    }
    fputs("],\"lines\":[", stdout);
    for (size_t row = 0; row < history + rows; row++) {
        GhosttyPoint point = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value.coordinate = {.x = 0, .y = row}};
        GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        GhosttyRow raw_row; bool wrap;
        check(ghostty_terminal_grid_ref(terminal, point, &ref));
        check(ghostty_grid_ref_row(&ref, &raw_row));
        check(ghostty_row_get(raw_row, GHOSTTY_ROW_DATA_WRAP, &wrap));
        printf("%s{\"wrapped\":%s,\"cells\":[", row ? "," : "", wrap ? "true" : "false");
        for (unsigned col = 0; col < cols; col++) {
            point.value.coordinate.x = col;
            check(ghostty_terminal_grid_ref(terminal, point, &ref));
            GhosttyCell cell; GhosttyCellWide wide; GhosttyCellContentTag content; bool protected;
            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
            check(ghostty_grid_ref_cell(&ref, &cell));
            check(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide));
            check(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &content));
            check(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_PROTECTED, &protected));
            check(ghostty_grid_ref_style(&ref, &style));
            int fg = color(style.fg_color), bg = color(style.bg_color);
            if (content == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
                GhosttyColorPaletteIndex index; check(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &index)); bg = 0x1000000 | index;
            } else if (content == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) {
                GhosttyColorRgb rgb; check(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &rgb)); bg = rgb.r << 16 | rgb.g << 8 | rgb.b;
            }
            uint32_t graphemes[256]; size_t length = 0;
            check(ghostty_grid_ref_graphemes(&ref, graphemes, 256, &length));
            unsigned char uri[65536]; size_t uri_len = 0;
            check(ghostty_grid_ref_hyperlink_uri(&ref, uri, sizeof(uri), &uri_len));
            const int underlines[] = {0, 8, 128, 256, 512, 1024};
            if (style.underline < 0 || style.underline > 5) exit(2);
            int flags = style.bold | style.faint << 1 | style.italic << 2 | style.inverse << 4 |
                style.invisible << 5 | style.strikethrough << 6 | underlines[style.underline] |
                style.blink << 11 | style.overline << 12;
            printf("%s[\"", col ? "," : "");
            if (wide != GHOSTTY_CELL_WIDE_SPACER_TAIL) {
                if (!length) json_codepoint(32);
                for (size_t i = 0; i < length; i++) json_codepoint(graphemes[i] == 9 ? 32 : graphemes[i]);
            }
            printf("\",%d,%d,%d,%d,%d,", wide == GHOSTTY_CELL_WIDE_WIDE ? 2 : wide == GHOSTTY_CELL_WIDE_SPACER_TAIL ? 0 : 1,
                fg, bg, flags, color(style.underline_color));
            if (uri_len) json_bytes(uri, uri_len); else fputs("null", stdout);
            printf(",%s]", protected ? "true" : "false");
        }
        fputs("]}", stdout);
    }
    fputs("]}\n", stdout); fflush(stdout);
}
static int hex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
static void encode_key(GhosttyTerminal terminal, const char *command) {
    // Names are test protocol identifiers, not enum ordinals from an unstable C ABI.
    static const struct { const char *name; GhosttyKey key; const char *text; } keys[] = {
#define KP(name, text) {#name, GHOSTTY_KEY_NUMPAD_##name, text}
        KP(0, "0"), KP(1, "1"), KP(2, "2"), KP(3, "3"), KP(4, "4"),
        KP(5, "5"), KP(6, "6"), KP(7, "7"), KP(8, "8"), KP(9, "9"),
        KP(DECIMAL, "."), KP(DIVIDE, "/"), KP(MULTIPLY, "*"), KP(SUBTRACT, "-"), KP(ADD, "+"), KP(ENTER, "\r"),
        KP(UP, ""), KP(DOWN, ""), KP(RIGHT, ""), KP(LEFT, ""), KP(BEGIN, ""),
        KP(HOME, ""), KP(END, ""), KP(INSERT, ""), KP(DELETE, ""), KP(PAGE_UP, ""), KP(PAGE_DOWN, "")
#undef KP
    };
    char name[32]; unsigned mods;
    if (sscanf(command, "%31s %u", name, &mods) != 2 || mods > 15) exit(2);
    size_t index = 0;
    for (; index < sizeof(keys) / sizeof(keys[0]); index++) if (!strcmp(name, keys[index].name)) break;
    if (index == sizeof(keys) / sizeof(keys[0])) exit(2);
    GhosttyKeyEncoder encoder; GhosttyKeyEvent event;
    check(ghostty_key_encoder_new(NULL, &encoder));
    check(ghostty_key_event_new(NULL, &event));
    ghostty_key_encoder_setopt_from_terminal(encoder, terminal);
    ghostty_key_event_set_action(event, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(event, keys[index].key);
    ghostty_key_event_set_utf8(event, keys[index].text, strlen(keys[index].text));
    ghostty_key_event_set_mods(event, (mods & 1 ? GHOSTTY_MODS_SHIFT : 0) |
        (mods & 2 ? GHOSTTY_MODS_ALT : 0) | (mods & 4 ? GHOSTTY_MODS_CTRL : 0) |
        (mods & 8 ? GHOSTTY_MODS_NUM_LOCK : 0));
    char bytes[128]; size_t length;
    check(ghostty_key_encoder_encode(encoder, event, bytes, sizeof(bytes), &length));
    reply(terminal, NULL, (const unsigned char *)bytes, length);
    ghostty_key_event_free(event); ghostty_key_encoder_free(encoder);
}
static void encode_mouse(GhosttyTerminal terminal, const char *command) {
    int action, button, mods, x, y;
    if (sscanf(command, "%d %d %d %d %d", &action, &button, &mods, &x, &y) != 5 ||
        action < 0 || action > 2 || mods < 0 || mods > 7 || x < -4096 || x > 8192 || y < -4096 || y > 8192) exit(2);
    static const GhosttyMouseAction actions[] = {GHOSTTY_MOUSE_ACTION_PRESS, GHOSTTY_MOUSE_ACTION_RELEASE, GHOSTTY_MOUSE_ACTION_MOTION};
    GhosttyMouseButton identity;
    switch (button) {
        case 0: identity = GHOSTTY_MOUSE_BUTTON_LEFT; break;
        case 1: identity = GHOSTTY_MOUSE_BUTTON_MIDDLE; break;
        case 2: identity = GHOSTTY_MOUSE_BUTTON_RIGHT; break;
        case 3: identity = GHOSTTY_MOUSE_BUTTON_UNKNOWN; break;
        case 64: identity = GHOSTTY_MOUSE_BUTTON_FOUR; break;
        case 65: identity = GHOSTTY_MOUSE_BUTTON_FIVE; break;
        case 66: identity = GHOSTTY_MOUSE_BUTTON_SIX; break;
        case 67: identity = GHOSTTY_MOUSE_BUTTON_SEVEN; break;
        case 128: identity = GHOSTTY_MOUSE_BUTTON_EIGHT; break;
        case 129: identity = GHOSTTY_MOUSE_BUTTON_NINE; break;
        default: exit(2);
    }
    uint16_t columns, rows;
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLS, &columns));
    check(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows));
    GhosttyMouseEncoderSize size = {.size = sizeof(size), .screen_width = columns * 8u,
        .screen_height = rows * 16u, .cell_width = 8, .cell_height = 16};
    bool pressed = button != 3 && action != 1;
    GhosttyMouseEncoder encoder; GhosttyMouseEvent event;
    check(ghostty_mouse_encoder_new(NULL, &encoder));
    check(ghostty_mouse_event_new(NULL, &event));
    ghostty_mouse_encoder_setopt_from_terminal(encoder, terminal);
    ghostty_mouse_encoder_setopt(encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    ghostty_mouse_encoder_setopt(encoder, GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED, &pressed);
    ghostty_mouse_event_set_action(event, actions[action]);
    if (button == 3) ghostty_mouse_event_clear_button(event); else ghostty_mouse_event_set_button(event, identity);
    ghostty_mouse_event_set_mods(event, (mods & 1 ? GHOSTTY_MODS_SHIFT : 0) |
        (mods & 2 ? GHOSTTY_MODS_ALT : 0) | (mods & 4 ? GHOSTTY_MODS_CTRL : 0));
    ghostty_mouse_event_set_position(event, (GhosttyMousePosition){x * 8.0f + 1.0f, y * 16.0f + 1.0f});
    char bytes[128]; size_t length;
    check(ghostty_mouse_encoder_encode(encoder, event, bytes, sizeof(bytes), &length));
    reply(terminal, NULL, (const unsigned char *)bytes, length);
    ghostty_mouse_event_free(event); ghostty_mouse_encoder_free(encoder);
}
int main(void) {
    GhosttyTerminal terminal = NULL;
    char *line = NULL; size_t capacity = 0; ssize_t length;
    while ((length = getline(&line, &capacity, stdin)) >= 0) {
        if (length > 16 * 1024 * 1024) return 2;
        if (line[0] == 'U') {
            int previous = -1;
            putchar('[');
            for (uint32_t cp = 0; cp <= 0x10ffff; cp++) {
                int width = ghostty_unicode_codepoint_width(cp);
                if (width != previous) { printf("%s%u,%d", previous < 0 ? "" : ",", cp, width); previous = width; }
            }
            puts("]");
        } else if (line[0] == 'N') {
            unsigned cols, rows; size_t history;
            if (sscanf(line + 1, "%u %u %zu", &cols, &rows, &history) != 3 || cols > 4096 || rows > 4096) return 2;
            if (terminal) ghostty_terminal_free(terminal);
            check(ghostty_terminal_new(NULL, &terminal, cols, rows));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &history));
            if (!history) check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &history));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY, (void *)reply));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_RENDER_HOLD, (void *)render_hold));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES, (void *)device_attributes));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_XTVERSION, (void *)xtversion));
            GhosttyTerminalCursorStyle cursor_style = GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE;
            bool cursor_blink = false;
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, &cursor_style));
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &cursor_blink));
            replies_len = 0;
            holds_len = 0;
        } else if (!terminal) return 2;
        else if (line[0] == 'C') {
            unsigned values[259];
            char *next = line + 1;
            for (size_t i = 0; i < 259; i++) {
                char *end;
                unsigned long value = strtoul(next, &end, 16);
                if (end == next || value > 0xffffff) return 2;
                values[i] = (unsigned)value; next = end;
            }
            const GhosttyTerminalOption options[] = {GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND,
                GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR};
            for (size_t i = 0; i < 3; i++) {
                GhosttyColorRgb value = rgb(values[i]);
                check(ghostty_terminal_set(terminal, options[i], &value));
            }
            GhosttyColorRgb palette[256];
            for (size_t i = 0; i < 256; i++) palette[i] = rgb(values[i + 3]);
            check(ghostty_terminal_set(terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, &palette));
        } else if (line[0] == 'W') {
            size_t n = strcspn(line + 2, "\r\n");
            if (n % 2) return 2;
            unsigned char *bytes = malloc(n / 2 + 1);
            if (!bytes) return 2;
            for (size_t i = 0; i < n; i += 2) {
                int a = hex(line[i + 2]), b = hex(line[i + 3]);
                if (a < 0 || b < 0) return 2;
                bytes[i / 2] = a * 16 + b;
            }
            ghostty_terminal_vt_write(terminal, bytes, n / 2); free(bytes);
        } else if (line[0] == 'R') {
            unsigned cols, rows;
            if (sscanf(line + 1, "%u %u", &cols, &rows) != 2 || cols > 4096 || rows > 4096) return 2;
            check(ghostty_terminal_resize(terminal, cols, rows, 8, 16));
        } else if (line[0] == 'K') encode_key(terminal, line + 1);
        else if (line[0] == 'M') encode_mouse(terminal, line + 1);
        else if (line[0] == 'S') snapshot(terminal);
        else return 2;
    }
    if (terminal) ghostty_terminal_free(terminal);
    free(line);
    return ferror(stdin) ? 2 : 0;
}
