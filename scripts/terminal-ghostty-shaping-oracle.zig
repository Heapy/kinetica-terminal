// Run the unmodified Ghostty font run iterator and platform shaper with embedded fonts.
// This is a Zig test so original global.io() uses testing.io without starting an app.
const std = @import("std");
const font = @import("font/main.zig");
const terminal = @import("terminal/main.zig");
extern "c" fn write(c_int, [*]const u8, usize) isize;

const Case = struct {
    name: []const u8,
    text: []const u8,
    font: enum { jetbrains_mono, inconsolata, monaspace_neon, arabic } = .jetbrains_mono,
    columns: u16 = 80,
    row: usize = 0,
    cursor: ?usize = null,
    selection: ?[2]u16 = null,
    features: []const []const u8 = &.{},
};
const Point = struct { codepoint: u32, cluster: u32 };
const RawGlyph = struct { glyph: u32, index: u32, x_advance: i32, y_advance: i32, x_offset: i32, y_offset: i32 };
const Run = struct {
    offset: u16,
    cells: u16,
    font_index: u16,
    input: []const Point,
    glyphs: []const font.shape.Cell,
    raw: []const RawGlyph,
};

test "kinetica shaping oracle" {
    const alloc = std.testing.allocator;
    var arena = std.heap.ArenaAllocator.init(alloc);
    defer arena.deinit();
    const result_alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, result_alloc, @embedFile("shaping-cases.json"), .{});
    for (cases.value) |case| {
        var lib = try font.Library.init(alloc);
        defer lib.deinit();
        var collection = font.Collection.init();
        collection.load_options = .{ .library = lib };
        const primary: [:0]const u8 = switch (case.font) {
            .jetbrains_mono => font.embedded.jetbrains_mono,
            .inconsolata => font.embedded.inconsolata,
            .monaspace_neon => font.embedded.monaspace_neon,
            .arabic => font.embedded.arabic,
        };
        // Explicit faces for all styles avoid host discovery and synthetic font policy.
        // These cases compare shaping and run boundaries, not bold/italic rasterization.
        for (std.enums.values(font.Style)) |style| {
            const data_set: []const [:0]const u8 = if (font.options.backend == .coretext)
                // CoreText cannot load the original CBDT Noto Color Emoji face.
                // Keep fallback deterministic with the embedded text face, not host discovery.
                &.{ primary, font.embedded.emoji_text }
            else &.{ primary, font.embedded.emoji, font.embedded.emoji_text };
            for (data_set) |data| {
                _ = try collection.add(alloc, try .init(lib, data, .{ .size = .{ .points = 12 } }), .{
                    .style = style, .fallback = false, .size_adjustment = .none,
                });
            }
        }
        var grid = try font.SharedGrid.init(alloc, .{ .collection = collection });
        defer grid.deinit(alloc);
        var shaper = try font.Shaper.init(alloc, .{ .features = case.features });
        defer shaper.deinit();
        var t = try terminal.Terminal.init(std.testing.io, alloc, .{ .cols = case.columns, .rows = 3 });
        defer t.deinit(alloc);
        var stream = t.vtStream();
        defer stream.deinit();
        stream.nextSlice(case.text);
        var state: terminal.RenderState = .empty;
        defer state.deinit(alloc);
        try state.update(alloc, &t);
        var iterator = shaper.runIterator(.{
            .grid = &grid, .cells = state.row_data.get(case.row).cells.slice(),
            .cursor_x = case.cursor, .selection = case.selection,
        });
        var runs: std.ArrayList(Run) = .empty;
        while (try iterator.next(alloc)) |run| {
            var points: std.ArrayList(Point) = .empty;
            const codepoints = if (font.options.backend == .coretext) shaper.run_state.codepoints.items else shaper.codepoints.items;
            for (codepoints) |cp| {
                if (font.options.backend == .coretext and cp.codepoint == 0) continue; // UTF-16 padding.
                try points.append(result_alloc, .{ .codepoint = cp.codepoint, .cluster = cp.cluster });
            }
            const shaped = try result_alloc.dupe(font.shape.Cell, try shaper.shape(run));
            const raw: []const RawGlyph = if (font.options.backend == .coretext) &.{} else raw: {
                const info = if (run.font_index.special() == null) shaper.hb_buf.getGlyphInfos() else &.{};
                const positions = shaper.hb_buf.getGlyphPositions() orelse &.{};
                const values = try result_alloc.alloc(RawGlyph, info.len);
                for (values, info, positions[0..values.len]) |*entry, glyph, position| entry.* = .{
                    .glyph = glyph.codepoint, .index = glyph.cluster,
                    .x_advance = position.x_advance, .y_advance = position.y_advance,
                    .x_offset = position.x_offset, .y_offset = position.y_offset,
                };
                break :raw values;
            };
            try runs.append(result_alloc, .{
                .offset = run.offset, .cells = run.cells, .font_index = run.font_index.int(),
                .input = points.items, .glyphs = shaped, .raw = raw,
            });
        }
        const result = try std.json.Stringify.valueAlloc(result_alloc, .{
            .name = case.name, .cell_width = grid.metrics.cell_width,
            .cell_height = grid.metrics.cell_height, .runs = runs.items,
        }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
