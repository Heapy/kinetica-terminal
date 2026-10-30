// Execute the original Ghostty ENQ parser dispatch and bounded answerback effect.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, response: ?[]const u8 = null };
const Case = struct { name: []const u8, events: []const Event };
const Snapshot = struct { replies: []const u8 };
var response: []const u8 = "";
fn enquiry(_: *vt.TerminalStream.Handler) []const u8 { return response; }
fn hex(alloc: std.mem.Allocator, bytes: []const u8) ![]const u8 {
    const result = try alloc.alloc(u8, bytes.len * 2);
    for (bytes, 0..) |byte, i| { result[i*2] = "0123456789abcdef"[byte >> 4]; result[i*2+1] = "0123456789abcdef"[byte & 15]; }
    return result;
}
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
            if (event.response) |value| {
                if (std.mem.eql(u8, value, "-")) stream.handler.effects.enquiry = null else {
                    const bytes = try alloc.alloc(u8, value.len / 2);
                    _ = try std.fmt.hexToBytes(bytes, value);
                    response = bytes;
                    stream.handler.effects.enquiry = enquiry;
                }
            }
            if (event.text) |text| stream.nextSlice(text);
            try snapshots.append(alloc, .{
                .replies = try hex(alloc, replies.items),
            });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
