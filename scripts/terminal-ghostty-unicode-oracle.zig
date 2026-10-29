// Invoke the pinned, unmodified libghostty-vt Zig module's render-state placeholder scanner and geometry.
const std = @import("std");
const vt = @import("ghostty-vt");
extern "c" fn write(c_int, [*]const u8, usize) isize;
const Event = struct { text: ?[]const u8 = null, resize: ?[4]u32 = null, scroll: ?i32 = null };
const Case = struct { name: []const u8, columns: u16 = 12, rows: u16 = 6, cell: [2]u32 = .{8, 16}, events: []const Event };
const Run = struct {
    x: u32, y: u32, image: u32, placement: u32, row: u32, col: u32, width: u32,
    render: ?[8]u32 = null, err: ?[]const u8 = null, visible: bool = false,
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
        var render_state: vt.RenderState = .empty;
        defer render_state.deinit(alloc);
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
            try render_state.update(alloc, &t);
            const range = render_state.rowDataRange();
            const rows = render_state.row_data.items(.placeholders)[range.start..range.end];
            const base = render_state.viewportY(range.start);
            var runs: std.ArrayList(Run) = .empty;
            for (rows, 0..) |row_runs, y| {
                for (row_runs.items) |p| {
                    var run: Run = .{ .x = p.x, .y = @intCast(base + @as(isize, @intCast(y))),
                        .image = p.image_id, .placement = p.placement_id,
                        .row = p.row, .col = p.col, .width = p.width, .visible = p.state == .visible };
                    if (p.state == .visible) {
                        const g = p.geometry;
                        run.render = .{ g.offset_x, g.offset_y, g.source_x, g.source_y,
                            g.source_width, g.source_height, g.width, g.height };
                    } else if (t.screens.active.kitty_images.imageById(p.image_id)) |image| {
                        // Keep diagnostics for hidden raw runs, without replacing the original scanner.
                        if (cell[0] != 0 and cell[1] != 0) {
                            if (t.screens.active.kitty_images.placeholderTarget(p.image_id, p.placement_id)) |target| {
                                const placement: vt.kitty.graphics.unicode.Placement = .{
                                    .pin = t.screens.active.pages.getTopLeft(.viewport),
                                    .image_id = p.image_id, .placement_id = p.placement_id,
                                    .row = p.row, .col = p.col, .width = p.width, .height = 1,
                                };
                                if (placement.renderPlacement(&target.placement, &image, cell[0], cell[1])) |g| {
                                    if (g.dest_width != 0 and g.dest_height != 0) return error.UnexpectedHiddenPlacement;
                                    run.render = .{ g.offset_x, g.offset_y, g.source_x, g.source_y,
                                        g.source_width, g.source_height, g.dest_width, g.dest_height };
                                } else |err| { run.err = @errorName(err); }
                            } else { run.err = "PlacementMissingPlacement"; }
                        }
                    } else { run.err = "NoImage"; }
                    try runs.append(alloc, run);
                }
            }
            try snapshots.append(alloc, try runs.toOwnedSlice(alloc));
        }
        const result = try std.json.Stringify.valueAlloc(alloc, .{ .name = case.name, .snapshots = snapshots.items }, .{});
        defer alloc.free(result);
        if (write(1, result.ptr, result.len) != result.len or write(1, "\n", 1) != 1) return error.WriteFailed;
    }
}
