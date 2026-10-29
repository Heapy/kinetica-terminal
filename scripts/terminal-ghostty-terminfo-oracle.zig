// Query the unmodified pinned terminfo table and original DCS handler.
const std = @import("std");
const vt = @import("ghostty-vt");
const terminfo = @import("terminfo");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Case = struct {
    name: []const u8, input: []const u8 = "", terminfo_name: ?[]const u8 = null,
    prefix: []const u8 = "", repeated: []const u8 = "", count: usize = 0, suffix: []const u8 = "",
};
var response: std.ArrayList(u8) = .empty;
var allocator: std.mem.Allocator = undefined;
fn writePty(_: *vt.TerminalStream.Handler, bytes: []const u8) void { response.appendSlice(allocator, bytes) catch @panic("capture allocation"); }
fn emit(value: anytype) !void {
    const json = try std.json.Stringify.valueAlloc(allocator, value, .{});
    if (write(1, json.ptr, json.len) != json.len or write(1, "\n", 1) != 1) return error.WriteFailed;
}
fn run(init: std.process.Init, case: Case) !void {
    var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 80, .rows = 24 });
    defer terminal.deinit(init.gpa);
    var stream = terminal.vtStream();
    defer stream.deinit();
    stream.handler.effects.write_pty = &writePty;
    stream.handler.terminfo_name = case.terminfo_name;
    response.clearRetainingCapacity();
    stream.nextSlice(case.input);
    stream.nextSlice(case.prefix);
    for (0..case.count) |_| stream.nextSlice(case.repeated);
    stream.nextSlice(case.suffix);
    try emit(.{ .case = case, .reply = response.items });
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    allocator = arena.allocator();
    const map = comptime terminfo.ghostty.xtgettcapMap();
    try emit(.{ .keys = map.keys(), .values = map.values() });
    for (map.keys()) |key| {
        if (std.mem.eql(u8, key, "544E")) continue;
        const lower = try allocator.dupe(u8, key);
        for (lower) |*byte| byte.* = std.ascii.toLower(byte.*);
        const query = try std.fmt.allocPrint(allocator, "\x1bP+q{s};{s}\x1b\\", .{ key, lower });
        try run(init, .{ .name = key, .input = query });
    }
    const cases = try std.json.parseFromSlice([]const Case, allocator, @embedFile("cases.json"), .{});
    for (cases.value) |case| try run(init, case);
}
