// Run the unmodified Ghostty font run iterator and HarfBuzz shaper with embedded fonts.
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
const Run = struct {
    offset: u16,
    cells: u16,
    font_index: u16,
    input: []const Point,
    glyphs: []const font.shape.Cell,
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
            for ([_][:0]const u8{ primary, font.embedded.emoji, font.embedded.emoji_text }) |data| {
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
            const points = try result_alloc.alloc(Point, shaper.codepoints.items.len);
            for (points, shaper.codepoints.items) |*point, cp| point.* = .{ .codepoint = cp.codepoint, .cluster = cp.cluster };
            try runs.append(result_alloc, .{
                .offset = run.offset, .cells = run.cells, .font_index = run.font_index.int(),
                .input = points, .glyphs = try result_alloc.dupe(font.shape.Cell, try shaper.shape(run)),
            });
        }
        const result = try std.json.Stringify.valueAlloc(result_alloc, .{
            .name = case.name, .cell_width = grid.metrics.cell_width,
            .cell_height = grid.metrics.cell_height, .runs = runs.items,
        }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
