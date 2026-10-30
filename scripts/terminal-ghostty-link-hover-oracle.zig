// Query the unmodified Ghostty RenderState.linkCells; do not reproduce its matching logic.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, resize: ?[2]u16 = null, scroll: ?i32 = null };
const Case = struct { name: []const u8, columns: u16, rows: u16, events: []const Event };
const Snapshot = struct { columns: u16, rows: u16, cells: []const []const u32 };
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var t = try vt.Terminal.init(init.io, init.gpa, .{ .cols = case.columns, .rows = case.rows, .max_scrollback_bytes = 1000000 });
        defer t.deinit(init.gpa);
        t.flags.shell_redraws_prompt = .false;
        var stream = t.vtStream();
        defer stream.deinit();
        var state: vt.RenderState = .empty;
        defer state.deinit(init.gpa);
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            if (event.text) |text| stream.nextSlice(text);
            if (event.resize) |size| try t.resize(init.gpa, .{ .cols = size[0], .rows = size[1] });
            if (event.scroll) |delta| t.scrollViewport(.{ .delta = -delta });
            try state.update(init.gpa, &t);
            var cells: std.ArrayList([]const u32) = .empty;
            for (0..t.rows) |y| for (0..t.cols) |x| {
                var matches = try state.linkCells(alloc, .{ .x = @intCast(x), .y = @intCast(y) });
                defer matches.deinit(alloc);
                var indices: std.ArrayList(u32) = .empty;
                for (matches.keys()) |point| try indices.append(alloc, @as(u32, point.y) * t.cols + point.x);
                std.mem.sort(u32, indices.items, {}, std.sort.asc(u32));
                try cells.append(alloc, indices.items);
            };
            try snapshots.append(alloc, .{ .columns = t.cols, .rows = t.rows, .cells = cells.items });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
