// Execute the pinned original Style.fg; no copied foreground-resolution algorithm.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Case = struct { name: []const u8, default: u32, palette: [256]u32, policy: ?i32, raw: []const i32 };
fn rgb(value: u32) vt.color.RGB {
    return .{ .r = @intCast(value >> 16), .g = @intCast((value >> 8) & 255), .b = @intCast(value & 255) };
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var palette: vt.color.Palette = undefined;
        for (case.palette, 0..) |value, i| palette[i] = rgb(value);
        const policy: ?vt.Style.BoldColor = if (case.policy) |value|
            (if (value == -1) .bright else .{ .color = rgb(@intCast(value)) }) else null;
        var resolved: std.ArrayList(u32) = .empty;
        for ([_]bool{ false, true }) |bold| {
            for (case.raw) |raw| {
                const style: vt.Style = .{
                    .flags = .{ .bold = bold },
                    .fg_color = if (raw < 0) .none else if (raw >= 1 << 24)
                        .{ .palette = @intCast(raw & 255) } else .{ .rgb = rgb(@intCast(raw)) },
                };
                const fg = style.fg(.{ .default = rgb(case.default), .palette = &palette, .bold = policy });
                try resolved.append(alloc, (@as(u32, fg.r) << 16) | (@as(u32, fg.g) << 8) | fg.b);
            }
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .resolved = resolved.items }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
