// Compare the original semantic line boundaries used by Surface.linkAtPin.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, resize: ?[2]u16 = null };
const Case = struct { name: []const u8, columns: u16, rows: u16, events: []const Event };
const Snapshot = struct { columns: u16, lines: u32, semantics: []const u32, markers: []const u32, ranges: []const [6]u32 };
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = case.columns, .rows = case.rows });
        defer terminal.deinit(init.gpa);
        // Match libghostty-vt's C initializer; OSC 133 may opt in to redraw.
        terminal.flags.shell_redraws_prompt = .false;
        var stream = terminal.vtStream();
        defer stream.deinit();
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            if (event.text) |text| stream.nextSlice(text);
            if (event.resize) |size| try terminal.resize(init.gpa, .{ .cols = size[0], .rows = size[1] });
            const screen = terminal.screens.active;
            const active = screen.pages.pin(.{ .active = .{} }).?;
            const lines = screen.pages.pointFromPin(.screen, active).?.screen.y + terminal.rows;
            var ranges: std.ArrayList([6]u32) = .empty;
            var semantics: std.ArrayList(u32) = .empty;
            var markers: std.ArrayList(u32) = .empty;
            for (0..lines) |y| for (0..terminal.cols) |x| {
                const pin = screen.pages.pin(.{ .screen = .{ .x = @intCast(x), .y = @intCast(y) } }).?;
                const rac = pin.rowAndCell();
                try semantics.append(alloc, @intFromEnum(rac.cell.semantic_content));
                if (x == 0) try markers.append(alloc, @intFromEnum(rac.row.semantic_prompt));
                var selection = screen.selectLine(.{ .pin = pin, .whitespace = null, .semantic_prompt_boundary = true }).?;
                defer selection.deinit(screen);
                const start = screen.pages.pointFromPin(.screen, selection.start()).?.screen;
                const end = screen.pages.pointFromPin(.screen, selection.end()).?.screen;
                try ranges.append(alloc, .{ @intCast(x), @intCast(y), start.x, start.y, @as(u32, end.x) + 1, end.y });
            };
            try snapshots.append(alloc, .{ .columns = terminal.cols, .lines = @intCast(lines), .semantics = semantics.items, .markers = markers.items, .ranges = ranges.items });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
