// Execute original Ghostty constraints, lookup tables and renderer width selection.
const std = @import("std");
const Glyph = @import("font/Glyph.zig");
const Metrics = @import("font/Metrics.zig");
const attributes = @import("font/nerd_font_attributes.zig");
const cell = @import("renderer/cell.zig");
const terminal = @import("terminal/main.zig");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Constraint = Glyph.RenderOptions.Constraint;
const Range = struct { start: u32, end: u32, constraint: ?Constraint, symbol: bool };
const Geometry = struct { glyph: Glyph.Size, metrics: Metrics, width: u2, constraint: Constraint };
const Row = struct { text: []const u8, columns: u16 };

fn emit(alloc: std.mem.Allocator, value: anytype) !void {
    const bytes = try std.json.Stringify.valueAlloc(alloc, value, .{});
    if (write(1, bytes.ptr, bytes.len) != bytes.len or write(1, "\n", 1) != 1) return error.WriteFailed;
}

test "kinetica glyph constraints oracle" {
    var arena = std.heap.ArenaAllocator.init(std.testing.allocator);
    defer arena.deinit();
    const alloc = arena.allocator();
    var start: u32 = 0;
    var previous_constraint: ?Constraint = attributes.getConstraint(0);
    var previous_symbol = cell.isSymbol(0);
    for (1..0x110001) |value| {
        const cp: u21 = @intCast(value);
        const constraint = attributes.getConstraint(cp);
        const symbol = cell.isSymbol(cp);
        if (value == 0x110000 or !std.meta.eql(constraint, previous_constraint) or symbol != previous_symbol) {
            try emit(alloc, Range{ .start = start, .end = @intCast(value), .constraint = previous_constraint, .symbol = previous_symbol });
            start = @intCast(value); previous_constraint = constraint; previous_symbol = symbol;
        }
    }
    const geometries = try std.json.parseFromSlice([]const Geometry, alloc, @embedFile("glyph-constraint-geometries.json"), .{});
    for (geometries.value) |case| try emit(alloc, .{ .geometry = case.constraint.constrain(case.glyph, case.metrics, case.width) });
    const rows = try std.json.parseFromSlice([]const Row, alloc, @embedFile("glyph-constraint-rows.json"), .{});
    for (rows.value) |case| {
        var t = try terminal.Terminal.init(std.testing.io, alloc, .{ .cols = case.columns, .rows = 1 });
        defer t.deinit(alloc);
        var stream = t.vtStream(); defer stream.deinit(); stream.nextSlice(case.text);
        var state: terminal.RenderState = .empty; defer state.deinit(alloc); try state.update(alloc, &t);
        const raw = state.row_data.get(0).cells.items(.raw);
        const widths = try alloc.alloc(u2, raw.len);
        for (raw, 0..) |_, x| widths[x] = cell.constraintWidth(raw, x, state.cols);
        try emit(alloc, .{ .widths = widths });
    }
}
