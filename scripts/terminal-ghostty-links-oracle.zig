// Execute Ghostty's unmodified default URL/path expression with its pinned Oniguruma.
const std = @import("std");
const oni = @import("oniguruma");
const url = @import("url.zig");
extern "c" fn write(c_int, [*]const u8, usize) isize;
fn emit(alloc: std.mem.Allocator, value: anytype) !void {
    const text = try std.json.Stringify.valueAlloc(alloc, value, .{});
    if (write(1, text.ptr, text.len) != text.len or write(1, "\n", 1) != 1) return error.WriteFailed;
}
fn classRanges(alloc: std.mem.Allocator, pattern: []const u8) ![]const [2]u32 {
    var regex = try oni.Regex.init(pattern, .{}, oni.Encoding.utf8, oni.Syntax.default, null);
    defer regex.deinit();
    var region: oni.Region = .{};
    defer region.deinit();
    var ranges: std.ArrayList([2]u32) = .empty;
    var begin: ?u32 = null;
    for (0..0x110000) |value| {
        const cp: u21 = @intCast(value);
        var buffer: [4]u8 = undefined;
        const matches = match: {
            if (cp >= 0xd800 and cp <= 0xdfff) break :match false;
            const length = try std.unicode.utf8Encode(cp, &buffer);
            _ = regex.searchAdvanced(buffer[0..length], 0, length, &region, .{}) catch |err| switch (err) {
                error.Mismatch => break :match false,
                else => return err,
            };
            break :match true;
        };
        if (matches and begin == null) begin = cp;
        if (!matches) if (begin) |start| {
            try ranges.append(alloc, .{ start, cp });
            begin = null;
        };
    }
    if (begin) |start| try ranges.append(alloc, .{ start, 0x110000 });
    return ranges.items;
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    try oni.init(&.{oni.Encoding.utf8});
    defer oni.deinit();
    try emit(alloc, .{ .pattern = url.regex, .wordRanges = try classRanges(alloc, "\\A\\w\\z"), .classWordRanges = try classRanges(alloc, "\\A[\\w]\\z"), .digitRanges = try classRanges(alloc, "\\A\\d\\z") });
    var regex = try oni.Regex.init(url.regex, .{}, oni.Encoding.utf8, oni.Syntax.default, null);
    defer regex.deinit();
    var region: oni.Region = .{};
    defer region.deinit();
    const inputs = try std.json.parseFromSlice([]const []const u8, alloc, @embedFile("cases.json"), .{});
    for (inputs.value) |input| {
        var ranges: std.ArrayList([2]usize) = .empty;
        var start: usize = 0;
        while (start < input.len) {
            _ = regex.searchAdvanced(input, start, input.len, &region, .{}) catch |err| switch (err) {
                error.Mismatch => break,
                else => return err,
            };
            const end: usize = @intCast(region.ends()[0]);
            if (end <= start) return error.EmptyMatch;
            try ranges.append(alloc, .{ @intCast(region.starts()[0]), end });
            start = end;
        }
        try emit(alloc, .{ .input = input, .ranges = ranges.items });
    }
}
