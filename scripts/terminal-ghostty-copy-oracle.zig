// Execute original ScreenFormatter with clipboard codepoint mappings.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Mapping = struct { first: u21, last: u21, replacement: []const u8 };
const Case = struct { name: []const u8, columns: u16, rows: u16, text: []const u8, mappings: []const Mapping };
const Selected = struct { range: [4]u32, rectangle: bool, plain: []const u8, trimmed: []const u8, mapped: []const u8, mappedTrimmed: []const u8 };
fn selected(screen: *vt.Screen, alloc: std.mem.Allocator, selection: vt.Selection,
    rectangle: bool, map: std.MultiArrayList(vt.formatter.CodepointMap)) !Selected {
    var sel = selection;
    defer sel.deinit(screen);
    const start = screen.pages.pointFromPin(.screen, sel.topLeft(screen)).?.screen;
    const end = screen.pages.pointFromPin(.screen, sel.bottomRight(screen)).?.screen;
    var result: [4][]const u8 = undefined;
    for (0..4) |i| {
        var out: std.Io.Writer.Allocating = .init(alloc);
        var formatter: vt.formatter.ScreenFormatter = .init(screen, .{
            .emit = .plain, .unwrap = true, .trim = i % 2 == 1,
            .codepoint_map = if (i >= 2) map else .{},
        });
        formatter.content = .{ .selection = sel };
        try formatter.format(&out.writer);
        result[i] = try out.toOwnedSlice();
    }
    return .{ .range = .{ start.x, start.y, @as(u32, end.x) + 1, end.y }, .rectangle = rectangle,
        .plain = result[0], .trimmed = result[1], .mapped = result[2], .mappedTrimmed = result[3] };
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = case.columns, .rows = case.rows });
        defer terminal.deinit(init.gpa);
        terminal.flags.shell_redraws_prompt = .false;
        var stream = terminal.vtStream();
        defer stream.deinit();
        stream.nextSlice(case.text);
        var map: std.MultiArrayList(vt.formatter.CodepointMap) = .{};
        for (case.mappings) |m| try map.append(alloc, .{ .range = .{ m.first, m.last }, .replacement = .{ .string = m.replacement } });
        const screen = terminal.screens.active;
        const active = screen.pages.pin(.{ .active = .{} }).?;
        const lines = screen.pages.pointFromPin(.screen, active).?.screen.y + terminal.rows;
        var results: std.ArrayList(Selected) = .empty;
        if (screen.selectAll()) |sel| try results.append(alloc, try selected(screen, alloc, sel, false, map));
        for ([_]u32{ 0, @intCast(lines / 2), @intCast(lines - 1) }) |y| {
            for ([_]u16{ 0, terminal.cols / 2, terminal.cols - 1 }) |x| {
                const start = screen.pages.pin(.{ .screen = .{ .x = x, .y = y } }).?;
                const end = screen.pages.pin(.{ .screen = .{ .x = terminal.cols - 1, .y = lines - 1 } }).?;
                for ([_]bool{ false, true }) |rectangle| {
                    try results.append(alloc, try selected(screen, alloc, vt.Selection.init(start, end, rectangle), rectangle, map));
                }
            }
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .selections = results.items }, .{});
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
