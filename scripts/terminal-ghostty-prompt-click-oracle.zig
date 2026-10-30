// Original Ghostty cursorIsAtPrompt and promptIterator; coordinate helper appended from Surface.zig.
// The adapter models a completed local click (gesture arbitration is tested in the host views).
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, scroll: ?u32 = null, x: ?u16 = null, y: ?u16 = null };
const Case = struct { name: []const u8, events: []const Event };
const Snapshot = struct { reply: []const u8 };
fn click(alloc: std.mem.Allocator, t: *vt.Terminal, x: u16, y: u16, offset: u32) ![]const u8 {
    if (!t.cursorIsAtPrompt()) return "";
    const screen = t.screens.active;
    const mode = switch (screen.semantic_prompt.click) { .click_events => |v| v, else => return "" };
    var it = screen.cursor.page_pin.promptIterator(.left_up, null);
    const prompt = it.next() orelse return "";
    const active = screen.pages.pin(.{ .active = .{} }).?;
    const history = screen.pages.pointFromPin(.screen, active).?.screen.y;
    const target = screen.pages.pin(.{ .screen = .{ .x = x, .y = history - @min(history, offset) + y } }).?;
    if (target.before(prompt)) return "";
    const row: u32 = switch (mode) { .absolute => @as(u32, y) + 1, .relative => promptClickRelativeRow(&screen.pages, prompt, target) };
    return std.fmt.allocPrint(alloc, "\x1b[<0;{d};{d}M", .{ x + 1, row });
}
pub fn main(init: std.process.Init) !void {
    var arena = std.heap.ArenaAllocator.init(init.gpa);
    defer arena.deinit();
    const alloc = arena.allocator();
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    for (cases.value) |case| {
        var terminal = try vt.Terminal.init(init.io, init.gpa, .{ .cols = 12, .rows = 6 });
        defer terminal.deinit(init.gpa);
        var stream = terminal.vtStream();
        defer stream.deinit();
        var offset: u32 = 0;
        var snapshots: std.ArrayList(Snapshot) = .empty;
        for (case.events) |event| {
            if (event.text) |text| stream.nextSlice(text);
            if (event.scroll) |scroll| offset = scroll;
            const reply = if (event.x) |x| try click(alloc, &terminal, x, event.y.?, offset) else "";
            try snapshots.append(alloc, .{ .reply = reply });
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        if (write(1,result.ptr,result.len) != result.len or write(1,"\n",1) != 1) return error.WriteFailed;
    }
}
