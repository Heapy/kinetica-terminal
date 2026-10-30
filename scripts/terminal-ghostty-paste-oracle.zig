// Execute the original libghostty-vt text-paste encoder without a terminal or host clipboard.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Case = struct { name: []const u8, text: []const u8 };
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var encoded: [2][]const u8 = undefined;
        for ([_]bool{ false, true }, 0..) |bracketed, index| {
            const mutable = try alloc.dupe(u8, case.text);
            const parts = vt.input.encodePaste(mutable, .{ .bracketed = bracketed });
            encoded[index] = try std.mem.concat(alloc, u8, &parts);
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .text = case.text, .plain = encoded[0], .bracketed = encoded[1] }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
