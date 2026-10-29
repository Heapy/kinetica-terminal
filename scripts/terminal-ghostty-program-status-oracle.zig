// Original Ghostty parser, effects and replies; no copied parser or registry implementation.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, enabled: ?bool = null };
const Case = struct { name: []const u8, events: []const Event };
const Report = struct {
    state: []const u8, id: ?[]const u8, app: ?[]const u8, kind: ?[]const u8,
    progress: ?u8, title: ?[]const u8, message: ?[]const u8,
};
const Snapshot = struct { replies: []const u8, reports: []const Report };
var alloc: std.mem.Allocator = undefined;
var replies: std.ArrayList(u8) = .empty;
var reports: std.ArrayList(Report) = .empty;
fn copy(value: ?[]const u8) ?[]const u8 {
    return if (value) |v| alloc.dupe(u8,v) catch @panic("OOM") else null;
}
fn writePty(_: *vt.TerminalStream.Handler, data: []const u8) void {
    replies.appendSlice(alloc,data) catch @panic("OOM");
}
fn programStatus(_: *vt.TerminalStream.Handler, report: vt.osc.Command.ProgramStatus.Report) void {
    var title_buf: [192]u8 = undefined;
    var title: std.Io.Writer = .fixed(&title_buf);
    report.writeText(.title,&title) catch unreachable;
    var msg_buf: [2048]u8 = undefined;
    var msg: std.Io.Writer = .fixed(&msg_buf);
    report.writeText(.msg,&msg) catch unreachable;
    reports.append(alloc,.{
        .state = @tagName(report.state), .id = copy(report.readOption(.id)), .app = copy(report.readOption(.app)),
        .kind = if (report.readOption(.kind)) |v| @tagName(v) else null,
        .progress = report.readOption(.progress),
        .title = if (report.readOption(.title) != null) copy(title.buffered()) else null,
        .message = if (report.readOption(.msg) != null) copy(msg.buffered()) else null,
    }) catch @panic("OOM");
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case,alloc,@embedFile("cases.json"),.{});
    for (cases.value) |case| {
        var terminal = try vt.Terminal.init(init.io,init.gpa,.{ .cols = 12, .rows = 6 });
        defer terminal.deinit(init.gpa);
        var stream = terminal.vtStream();
        defer stream.deinit();
        stream.handler.effects.write_pty = writePty;
        stream.handler.effects.program_status = programStatus;
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            replies.clearRetainingCapacity(); reports.clearRetainingCapacity();
            if (event.enabled) |enabled| stream.handler.effects.program_status = if (enabled) programStatus else null;
            if (event.text) |text| stream.nextSlice(text);
            try snapshots.append(alloc,.{ .replies = try alloc.dupe(u8,replies.items), .reports = try alloc.dupe(Report,reports.items) });
        }
        const result = try std.json.Stringify.valueAlloc(alloc,.{ .name = case.name, .snapshots = snapshots.items },.{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
