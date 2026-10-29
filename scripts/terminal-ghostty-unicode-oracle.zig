// Invoke the pinned, unmodified libghostty-vt Zig module's placeholder iterator and geometry.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, resize: ?[4]u32 = null, scroll: ?i32 = null };
const Case = struct { name: []const u8, columns: u16 = 12, rows: u16 = 6, cell: [2]u32 = .{8, 16}, events: []const Event };
const Run = struct {
    x: u32, y: u32, image: u32, placement: u32, row: u32, col: u32, width: u32,
    render: ?[8]u32 = null, err: ?[]const u8 = null,
};
pub fn main(init: std.process.Init) !void {
    const alloc = init.gpa;
    const cases = try std.json.parseFromSlice([]const Case, alloc, @embedFile("cases.json"), .{});
    defer cases.deinit();
    for (cases.value) |case| {
        var t = try vt.Terminal.init(init.io, alloc, .{ .cols = case.columns, .rows = case.rows, .max_scrollback_bytes = 1000000 });
        defer t.deinit(alloc);
        var stream = t.vtStream();
        defer stream.deinit();
        var cell = case.cell;
        try t.resize(alloc, .{ .cols = case.columns, .rows = case.rows, .cell_size_px = .{ .width = cell[0], .height = cell[1] } });
        var snapshots: std.ArrayList([]const Run) = .empty;
        defer { for (snapshots.items) |runs| alloc.free(runs); snapshots.deinit(alloc); }
        for (case.events) |event| {
            if (event.text) |text| stream.nextSlice(text);
            if (event.resize) |size| {
                cell = .{size[2], size[3]};
                try t.resize(alloc, .{ .cols = @intCast(size[0]), .rows = @intCast(size[1]), .cell_size_px = .{ .width = cell[0], .height = cell[1] } });
            }
            if (event.scroll) |delta| t.scrollViewport(.{ .delta = delta });
            const top = t.screens.active.pages.getTopLeft(.viewport);
            const bottom = t.screens.active.pages.pin(.{ .viewport = .{ .x = t.cols - 1, .y = t.rows - 1 } }).?;
            var iterator = vt.kitty.graphics.unicode.placementIterator(top, bottom);
            var runs: std.ArrayList(Run) = .empty;
            while (iterator.next()) |p| {
                const point = t.screens.active.pages.pointFromPin(.viewport, p.pin).?.viewport;
                var run: Run = .{ .x = point.x, .y = point.y, .image = p.image_id, .placement = p.placement_id,
                    .row = p.row, .col = p.col, .width = p.width };
                if (t.screens.active.kitty_images.imageById(p.image_id)) |image| {
                    if (cell[0] != 0 and cell[1] != 0) {
                        if (p.renderPlacement(&t.screens.active.kitty_images, &image, cell[0], cell[1])) |r| {
                            run.render = .{ r.offset_x, r.offset_y, r.source_x, r.source_y, r.source_width, r.source_height, r.dest_width, r.dest_height };
                        } else |err| { run.err = @errorName(err); }
                    }
                } else { run.err = "NoImage"; }
                try runs.append(alloc, run);
            }
            try snapshots.append(alloc, try runs.toOwnedSlice(alloc));
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        defer alloc.free(result);
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
