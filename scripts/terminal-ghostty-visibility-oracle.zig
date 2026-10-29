// Execute the original Ghostty visibility parser, mode state and response encoder.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, visible: ?bool = null };
const Case = struct { name: []const u8, events: []const Event };
const Snapshot = struct { replies: []const u8, visible: bool, reporting: bool };
var replies: std.ArrayList(u8) = .empty;
var reply_allocator: std.mem.Allocator = undefined;
fn writePty(_: *vt.TerminalStream.Handler, data: []const u8) void {
    replies.appendSlice(reply_allocator, data) catch @panic("OOM");
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    reply_allocator = alloc;
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        replies.clearRetainingCapacity();
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 12, .rows = 6 });
        defer terminal.deinit(init.gpa);
        var stream = terminal.vtStream();
        defer stream.deinit();
        stream.handler.effects.write_pty = writePty;
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            if (event.visible) |visible| {
                if (terminal.flags.visible != visible) {
                    terminal.flags.visible = visible;
                    // Host adapter: Surface changes the flag; Termio.visibilityReport gates
                    // notifications on mode 2033. Reuse the original handler's report path,
                    // without injecting bytes into a possibly incomplete terminal sequence.
                    if (terminal.modes.get(.report_visibility))
                        stream.handler.vt(.device_status, .{ .request = .visibility });
                }
            }
            if (event.text) |text| stream.nextSlice(text);
            try snapshots.append(alloc, .{
                .replies = try alloc.dupe(u8, replies.items),
                .visible = terminal.flags.visible,
                .reporting = terminal.modes.get(.report_visibility),
            });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
